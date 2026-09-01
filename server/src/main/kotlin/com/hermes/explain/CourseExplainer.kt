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
