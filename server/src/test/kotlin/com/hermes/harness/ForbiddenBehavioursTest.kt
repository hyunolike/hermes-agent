package com.hermes.harness

import com.hermes.context.BundleLoader
import com.hermes.llm.Explanation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForbiddenBehavioursTest {

    private val bundle = BundleLoader.load()

    private val factsJson = """
        {"items":[
          {"attractionId":1001,"name":"경복궁","visitOrder":1,"timeLabel":"오전 10:00","grade":"VERY_CROWDED"},
          {"attractionId":1003,"name":"북촌 한옥마을","visitOrder":2,"timeLabel":"오전 11:38","grade":"NORMAL"},
          {"attractionId":1002,"name":"서촌 골목길","visitOrder":3,"timeLabel":"오후 1:14","grade":"RELAXED"}],
         "alternatives":[{"attractionId":1003,"name":"북촌 한옥마을"},{"attractionId":1002,"name":"서촌 골목길"}]}
    """.trimIndent()

    private fun explanation(text: String, vararg citations: String) =
        Explanation(text, citations.toList().ifEmpty { listOf("concepts/congestion-diagnosis.md") })

    @Test
    fun `사실에 근거한 설명은 위반이 없다`() {
        val violations = ForbiddenBehaviours.check(
            explanation("경복궁은 이 날 매우 붐비지만 첫 방문지로 두었어요. 북촌 한옥마을은 한산합니다."),
            factsJson, bundle,
        )

        assertThat(violations).isEmpty()
    }

    @Test
    fun `facts 에 없는 관광지를 지어내면 잡는다`() {
        val violations = ForbiddenBehaviours.check(
            explanation("경복궁 대신 창덕궁을 추천합니다."), factsJson, bundle,
        )

        assertThat(violations.map { it.behaviour }).contains(Behaviour.INVENTED_PLACE)
        assertThat(violations.first { it.behaviour == Behaviour.INVENTED_PLACE }.evidence).contains("창덕궁")
    }

    @Test
    fun `LLM 이 골랐다는 서술을 잡는다`() {
        val violations = ForbiddenBehaviours.check(
            explanation("제가 골라 드린 코스입니다."), factsJson, bundle,
        )

        assertThat(violations.map { it.behaviour }).contains(Behaviour.LLM_CHOSE)
    }

    @Test
    fun `목적지를 뒤로 미뤘다는 서술을 잡는다`() {
        // bestOrder 는 listOf(originId) + best 를 반환한다. 목적지는 언제나
        // 첫 방문지이고 미뤄지는 일이 없다.
        val violations = ForbiddenBehaviours.check(
            explanation("경복궁은 붐벼서 오후로 미뤘어요."), factsJson, bundle,
        )

        assertThat(violations.map { it.behaviour }).contains(Behaviour.DEFERRED_DESTINATION)
    }

    @Test
    fun `시간대 혼잡도로 방문 시각을 설명하면 잡는다`() {
        // timeLabel 은 출발 10:00 + 장소당 90분 + 실측 이동시간이다.
        // 혼잡도와 아무 관계가 없다.
        val violations = ForbiddenBehaviours.check(
            explanation("오전에는 한산해서 이 시간에 배치했습니다."), factsJson, bundle,
        )

        assertThat(violations.map { it.behaviour }).contains(Behaviour.TIME_OF_DAY_REASON)
    }

    @Test
    fun `번들 밖 인용을 잡는다`() {
        val violations = ForbiddenBehaviours.check(
            explanation("경복궁은 붐빕니다.", "concepts/made-up.md"), factsJson, bundle,
        )

        assertThat(violations.map { it.behaviour }).contains(Behaviour.UNCITED_CLAIM)
    }

    // --- Fix round 1 ---

    @Test
    fun `Unavailable 사유 문자열로 UNCITED_CLAIM 을 판별한다`() {
        // ExplanationService 는 인용이 유효할 때만 Explained 를 반환하므로,
        // check() 의 UNCITED_CLAIM 분기는 EvalMain 의 정상 경로에서는 절대
        // 실행되지 않는다. 실제 신호는 Unavailable.reason 에 있고, EvalMain 은
        // 이 판별 함수로 그 문자열을 읽어 집계한다.
        assertThat(ForbiddenBehaviours.unavailableReasonIndicatesUncitedClaim("no citations")).isTrue()
        assertThat(
            ForbiddenBehaviours.unavailableReasonIndicatesUncitedClaim(
                "citations not in bundle: concepts/made-up.md",
            ),
        ).isTrue()
        assertThat(ForbiddenBehaviours.unavailableReasonIndicatesUncitedClaim("refusal (unknown)")).isFalse()
        assertThat(ForbiddenBehaviours.unavailableReasonIndicatesUncitedClaim("openrouter http 500")).isFalse()
    }

    @Test
    fun `timeLabel 을 그대로 서술한 사실 문장은 TIME_OF_DAY_REASON 이 아니다`() {
        // 시간대 어구만 있고 혼잡도 인과 표현이 없다 — timeLabel 을 그대로 옮긴
        // 사실 서술이다.
        val violations = ForbiddenBehaviours.check(
            explanation("오후에는 서촌 골목길에 도착해요."), factsJson, bundle,
        )

        assertThat(violations.map { it.behaviour }).doesNotContain(Behaviour.TIME_OF_DAY_REASON)
    }

    @Test
    fun `시간대 어구와 혼잡 인과 연결어가 같은 문장에 있으면 TIME_OF_DAY_REASON 을 잡는다`() {
        val violations = ForbiddenBehaviours.check(
            explanation("오후에는 혼잡 때문에 이 시간에 왔어요."), factsJson, bundle,
        )

        assertThat(violations.map { it.behaviour }).contains(Behaviour.TIME_OF_DAY_REASON)
    }

    @Test
    fun `목적지가 아닌 장소를 뒤로 배치했다는 서술은 DEFERRED_DESTINATION 이 아니다`() {
        // 대안에 대한 사실 서술이다 — 목적지(visitOrder 1)는 언급되지 않는다.
        val violations = ForbiddenBehaviours.check(
            explanation("대안 두 곳은 뒤로 배치했어요."), factsJson, bundle,
        )

        assertThat(violations.map { it.behaviour }).doesNotContain(Behaviour.DEFERRED_DESTINATION)
    }

    @Test
    fun `목적지 이름과 미룸 표현이 다른 문장에 있으면 DEFERRED_DESTINATION 을 잡지 않는다`() {
        val violations = ForbiddenBehaviours.check(
            explanation("경복궁은 첫 방문지예요. 대안 두 곳은 뒤로 배치했어요."), factsJson, bundle,
        )

        assertThat(violations.map { it.behaviour }).doesNotContain(Behaviour.DEFERRED_DESTINATION)
    }
}
