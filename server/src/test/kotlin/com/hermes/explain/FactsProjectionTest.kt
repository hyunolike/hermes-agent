package com.hermes.explain

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FactsProjectionTest {

    private val mapper = ObjectMapper()

    private val course = mapper.readTree(
        """
        {"targetDate":"2026-08-15","title":"혼잡한 경복궁을 피하는 하루",
         "congestionReductionRate":34,"summary":"요약",
         "recommendedDate":{"date":"2026-08-19","congestionReductionRate":41},
         "items":[{"attractionId":1001,"name":"경복궁","visitOrder":1,"timeLabel":"오전 10:00",
                   "grade":"VERY_CROWDED","reason":"첫 방문지","travelMinutesFromPrev":null}]}
        """.trimIndent(),
    )

    private val alternatives = mapper.readTree(
        """
        [{"attractionId":1003,"name":"북촌 한옥마을","grade":"NORMAL","concentration":62.0,
          "distanceKm":0.6,"relationScore":0.9,"score":0.704,"recommendReason":"여유롭다","travelMinutes":8}]
        """.trimIndent(),
    )

    private val congestion = mapper.readTree(
        """
        {"diagnosis":{"concentration":87.3,"percentile":92,"grade":"VERY_CROWDED","message":"붐빈다"},
         "betterDates":[{"date":"2026-08-19","concentration":48.6,"grade":"RELAXED"}]}
        """.trimIndent(),
    )

    @Test
    fun `검사기가 읽는 두 경로가 최상위에 있다`() {
        // ForbiddenBehaviours 는 /items 와 /alternatives 를 최상위에서 읽는다.
        // 여기가 어긋나면 평가에서 모든 지명이 오탐되고 순서 검사가 무력해진다.
        val facts = FactsProjection.assemble(course, alternatives, congestion)

        assertThat(facts.at("/items/0/name").asText()).isEqualTo("경복궁")
        assertThat(facts.at("/alternatives/0/name").asText()).isEqualTo("북촌 한옥마을")
    }

    @Test
    fun `백분위와 점수가 살아남는다`() {
        // percentile 없이는 congestion-diagnosis.md 를, score 없이는
        // alternative-scoring.md 를 설명할 근거가 사라진다.
        val facts = FactsProjection.assemble(course, alternatives, congestion)

        assertThat(facts.at("/congestion/percentile").asInt()).isEqualTo(92)
        assertThat(facts.at("/alternatives/0/score").asDouble()).isEqualTo(0.704)
    }

    @Test
    fun `같은 입력이면 바이트 단위로 같은 JSON 이 나온다`() {
        val a = FactsProjection.assemble(course, alternatives, congestion).toString()
        val b = FactsProjection.assemble(course, alternatives, congestion).toString()

        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `필드가 빠지면 조용히 넘어가지 않고 그 필드를 지목해 실패한다`() {
        val itemMissingName = mapper.readTree("""{"attractionId":1001,"visitOrder":1}""")

        assertThatThrownBy { FactsProjection.project(itemMissingName, FactsProjection.ITEM_FIELDS) }
            .hasMessageContaining("name")
    }

    /**
     * 예보 커버리지가 없는 목적지. `hasCongestionData:false` 이고 `diagnosis` 자체가
     * 없으며, HTTP 는 200 이다 — 이건 실패가 아니라 제품 상태다
     * (concepts/congestion-diagnosis.md: "Missing coverage is a product state, not a
     * failure. An explanation layer must handle this branch").
     *
     * 실측: 이 분기가 없던 동안 운영 서버는 그 코스를 503 으로 돌려보내며 로그에
     * "hanjeok response did not carry the expected fields" 를 남겼다 — 멀쩡한 코스를
     * 백엔드 장애로 취급한 것이다.
     */
    private val congestionWithoutCoverage = mapper.readTree(
        """
        {"hasCongestionData":false,
         "attraction":{"id":19983,"name":"경복궁"},
         "message":"이 장소는 집중률 예측 데이터가 제공되지 않아요.",
         "nearbyDiagnosable":[{"id":1,"name":"어딘가"}]}
        """.trimIndent(),
    )

    @Test
    fun `진단이 없어도 사실을 만든다`() {
        val facts = FactsProjection.assemble(course, alternatives, congestionWithoutCoverage)

        assertThat(facts.at("/congestion/hasCongestionData").asBoolean()).isFalse()
        assertThat(facts.at("/congestion/message").asText()).contains("제공되지 않아요")
    }

    @Test
    fun `진단이 없으면 등급도 백분위도 싣지 않는다`() {
        // 자리를 비워 두는 대신 0 이나 빈 문자열을 넣으면, 모델은 그것을 값으로 읽고
        // "백분위 0" 같은 문장을 쓴다. 없는 것은 없어야 한다.
        val congestion = FactsProjection.assemble(course, alternatives, congestionWithoutCoverage).get("congestion")

        assertThat(congestion.has("grade")).isFalse()
        assertThat(congestion.has("percentile")).isFalse()
        assertThat(congestion.has("concentration")).isFalse()
    }

    @Test
    fun `진단이 있으면 커버리지 있음으로 싣는다`() {
        // 두 분기를 모델이 구분할 수 있어야 한다 — 플래그가 한쪽에만 있으면
        // 그 부재를 해석해야 하고, 부재는 해석하기 나쁜 신호다.
        val facts = FactsProjection.assemble(course, alternatives, congestion)

        assertThat(facts.at("/congestion/hasCongestionData").asBoolean()).isTrue()
        assertThat(facts.at("/congestion/grade").asText()).isEqualTo("VERY_CROWDED")
    }
}
