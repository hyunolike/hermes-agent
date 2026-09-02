package com.hermes.explain

import com.hermes.facts.FactsSource
import com.hermes.facts.HanjeokUnavailableException
import com.hermes.llm.Explanation
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

data class CourseExplanation(val explanation: Explanation, val factsJson: String, val cached: Boolean)

/**
 * facts 조회 · 설명 생성 · 캐시를 잇는 application 진입점.
 *
 * HTTP 를 모른다 — presentation 이 이것을 부른다. 하네스도 서버 없이 같은 경로를
 * 부를 수 있어야 하므로 여기에 웹 타입이 들어오면 안 된다.
 *
 * `ExplanationUnavailableException` 은 이 파일과 같은 패키지(com.hermes.explain)
 * 에 있으므로 import 하지 않는다 — presentation 패키지의 것이 아니다. 이 클래스가
 * application 층에 있는 이상 presentation 을 import 하면 이 프로젝트가 테스트로
 * 강제하는 의존 방향이 뒤집힌다.
 *
 * **같은 코스에 대한 동시 요청은 유료 호출을 하나만 낸다.** 캐시는 *끝난* 호출만
 * 막는다 — 진행 중인 호출은 못 막으므로, 처음 보는 코스에 요청이 몰리면 전부
 * 캐시 미스로 각자 모델을 부른다. Cloud Run 기본 동시성이 80 이라 콜드 코스 하나가
 * 80 번 청구될 수 있다. 그래서 uuid 마다 진행 중인 호출을 하나만 두고 나머지는
 * 그것을 기다린다(single-flight).
 *
 * 실패도 함께 기다린 쪽에 그대로 전달하되 **캐시하지는 않는다** — 그리고 끝나면
 * 자리를 반드시 비운다. 안 비우면 한 번 실패한 코스가 영원히 그 실패에 묶인다.
 *
 * **캐시 적중이어도 한적 호출 3회는 그대로 나간다.** 아래 `explain` 을 보면
 * `factsSource.fetch(courseUuid)` 가 캐시 조회보다 먼저다 — 응답이 언제나 `facts`
 * 를 실어야 하므로(클라이언트는 courseUuid 만 보내고 재계산할 방법이 없다) facts
 * 는 매 요청 다시 받아야 한다. 캐시가 건너뛰는 건 유료 LLM 호출(`service.explain`)
 * 하나뿐이다. 용량이나 요청 한도를 잡는 사람은 "캐시 적중률이 높으니 한적 부하는
 * 낮다"고 가정하면 안 된다 — 한적 쪽 3콜 부하는 캐시와 무관하게 요청 수에 비례한다.
 */
class CourseExplainer(
    private val factsSource: FactsSource,
    private val service: ExplanationService,
    private val cache: ExplanationCache,
) {

    private val log = LoggerFactory.getLogger(CourseExplainer::class.java)

    /** uuid → 진행 중인 설명 생성. 끝나면 즉시 제거된다. */
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Explanation>>()

    fun explain(courseUuid: String): CourseExplanation {
        val facts = try {
            factsSource.fetch(courseUuid)
        } catch (e: HanjeokUnavailableException) {
            log.warn("facts unavailable for course {}", courseUuid, e)
            throw ExplanationUnavailableException("facts: ${e.message}")
        }

        cache.get(courseUuid)?.let {
            return CourseExplanation(explanation = it, factsJson = facts.json, cached = true)
        }

        // facts 는 요청마다 새로 받은 것을 쓴다 — 기다린 쪽도 자기 facts 를 싣는다.
        // 공유되는 것은 유료 호출 하나, 즉 설명뿐이다.
        return CourseExplanation(
            explanation = explanationFor(courseUuid, facts),
            factsJson = facts.json,
            cached = false,
        )
    }

    private fun explanationFor(courseUuid: String, facts: BackendFacts): Explanation {
        var leader = false
        val pending = inFlight.computeIfAbsent(courseUuid) {
            leader = true
            CompletableFuture()
        }

        if (!leader) {
            // 남의 호출을 기다린다. 그쪽이 실패하면 같은 예외를 받는다 — 같은 순간의
            // 요청이 서로 다른 결과를 받으면 재현할 수 없는 버그가 된다.
            return try {
                pending.join()
            } catch (e: java.util.concurrent.CompletionException) {
                throw e.cause ?: e
            }
        }

        try {
            when (val outcome = service.explain(facts)) {
                is Explained -> {
                    // 실패는 캐시하지 않는다 — 일시적 장애가 그 코스에 영구히 눌어붙는다.
                    cache.put(courseUuid, outcome.explanation)
                    pending.complete(outcome.explanation)
                    return outcome.explanation
                }
                is Unavailable -> {
                    log.warn("explanation unavailable for course {}: {}", courseUuid, outcome.reason)
                    val failure = ExplanationUnavailableException(outcome.reason)
                    pending.completeExceptionally(failure)
                    throw failure
                }
            }
        } catch (e: Throwable) {
            // 예상 못 한 예외로 빠져나가도 기다리는 쪽을 매달아 두지 않는다.
            pending.completeExceptionally(e)
            throw e
        } finally {
            // 자리를 비우는 것이 실패를 캐시하지 않는다는 규칙의 나머지 절반이다.
            inFlight.remove(courseUuid, pending)
        }
    }
}
