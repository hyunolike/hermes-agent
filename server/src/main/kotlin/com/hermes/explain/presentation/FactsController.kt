package com.hermes.explain.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import com.hermes.explain.ExplanationUnavailableException
import com.hermes.facts.FactsSource
import com.hermes.facts.HanjeokUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.util.RawValue

/**
 * `facts` 는 **Jackson 3**(`tools.jackson`)의 `RawValue` 다. Jackson 2 쪽 동명
 * 클래스를 쓰면 이 웹 계층의 변환기가 그것을 평범한 POJO 로 보고 `{}` 로
 * 직렬화한다 — 에러 없이, 사실이 통째로 사라진 채로. 실제로 이 파일의 첫 버전이
 * 그랬고 테스트가 잡았다. `ExplainResponse` 가 같은 이유로 같은 선택을 한다.
 */
data class FactsResponse(val courseUuid: String, val facts: RawValue)

/**
 * 사실만 준다. LLM 을 타지 않는다.
 *
 * 스펙 §6.1 은 "503이면 설명 블록만 사라지고 코스는 그대로 읽힌다"고 약속했는데,
 * `POST /agent/explain` 하나가 설명과 facts 를 함께 내고 모든 실패가 503 이므로
 * LLM 만 실패해도 프론트에는 코스를 그릴 facts 가 없었다 — 사라지는 것은 설명
 * 블록이 아니라 화면 전체다. 그 약속을 지킬 수 있게 하는 것이 이 엔드포인트다.
 *
 * 따라온 것이 본론보다 낫다: 코스 화면이 LLM 왕복(3~5초)을 기다리지 않고 즉시
 * 그려진다. progressive enhancement 가 말로만이 아니라 실제로 그렇게 동작한다.
 *
 * **`CourseExplainer` 를 경유하지 않는다.** 그쪽은 설명 캐시를 조회하고 실패 시
 * 유료 호출까지 간다. 이 엔드포인트의 존재 이유가 "LLM 없이"이므로 의존을
 * `FactsSource` 하나로 좁혀, 나중에 누가 재사용하려 해도 컴파일 단계에서 막힌다.
 */
@RestController
class FactsController(
    private val source: FactsSource,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(FactsController::class.java)

    @GetMapping("/agent/facts/{courseUuid}")
    fun facts(@PathVariable courseUuid: String): FactsResponse {
        val facts = try {
            source.fetch(courseUuid)
        } catch (e: HanjeokUnavailableException) {
            // CourseExplainer 와 같은 변환을 한다 — 클라이언트가 보는 실패 계약이
            // 엔드포인트마다 달라지면 프론트가 분기를 두 벌 갖게 된다.
            log.warn("facts unavailable for course {}", courseUuid, e)
            throw ExplanationUnavailableException("facts: ${e.message}")
        }

        // 유효한 JSON 인지만 확인하고 파싱 결과는 버린다. 재직렬화하면
        // FactsProjection 이 확정한 필드 순서가 흔들린다(BackendFacts 계약).
        mapper.readTree(facts.json)

        return FactsResponse(courseUuid = facts.courseUuid, facts = RawValue(facts.json))
    }
}
