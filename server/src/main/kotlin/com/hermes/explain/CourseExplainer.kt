package com.hermes.explain

import com.hermes.facts.FactsSource
import com.hermes.facts.HanjeokUnavailableException
import com.hermes.llm.Explanation
import org.slf4j.LoggerFactory

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

        return when (val outcome = service.explain(facts)) {
            is Explained -> {
                // 실패는 캐시하지 않는다 — 일시적 장애가 그 코스에 영구히 눌어붙는다.
                cache.put(courseUuid, outcome.explanation)
                CourseExplanation(explanation = outcome.explanation, factsJson = facts.json, cached = false)
            }
            is Unavailable -> {
                log.warn("explanation unavailable for course {}: {}", courseUuid, outcome.reason)
                throw ExplanationUnavailableException(outcome.reason)
            }
        }
    }
}
