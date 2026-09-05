package com.hermes.harness

import com.hermes.context.BundleLoader
import com.hermes.llm.Explanation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 운영과 하네스에서 실제로 나온 문장들. 새 규칙이 이 중 무엇을 잡고 무엇을
 * 그냥 두는지가 곧 그 규칙의 정확도다.
 */
class RealSentenceRegressionTest {
    private val bundle = BundleLoader.load()
    private val factsJson = """{"items":[{"attractionId":1,"name":"경복궁","visitOrder":1,"grade":"VERY_CROWDED"}],"alternatives":[]}"""

    private fun flagged(text: String) = ForbiddenBehaviours
        .check(Explanation(text, listOf("concepts/congestion-diagnosis.md")), factsJson, bundle)
        .map { it.behaviour }
        .contains(Behaviour.MISSTATED_ORDER_REASON)

    @Test
    fun `실제로 틀렸던 문장들을 잡는다`() {
        assertThat(flagged("방문 순서는 여유 시간을 최대화하기 위해 최적의 경로로 배치되었습니다.")).isTrue()
    }

    @Test
    fun `실제로 옳았던 문장들을 잡지 않는다`() {
        val correct = listOf(
            "방문 순서가 총 이동 시간을 최소화하도록 설계되었습니다.",
            "이 순서는 출발 장소에서 경로의 전체 이동 시간을 최소화하는 목적을 가지고 있습니다.",
            "제시된 방문 순서는 서울 내 이동 시간을 최소화하는 경로입니다.",
            "코스의 순서는 총 여행 시간을 최소화하기 위해 정해졌으며, 각 장소의 방문 시간은 이동 거리와 경과 시간을 기반으로 구성되었습니다.",
            "경복궁을 시작으로 하여 창덕궁과 후원, 북촌한옥마을로 이어지는 일정입니다.",
            "이 일정은 이동 시간을 가장 효율적으로 줄이며 경복궁을 대체할 수 있는 코스로 선정되었습니다.",
            "첫 장소인 경복궁에서 출발하여 가장 가까운 순서대로 정해진 코스입니다.",
            // 첫 판이 오탐한 둘. 같은 목적을 "시간"이라는 낱말 없이 말한다.
            "제안된 코스에서는 경복궁을 포함한 세 장소 모두 혼잡도가 매우 높은 날이지만, 가까운 거리를 이용해 순서를 최적화하였습니다.",
            "각 장소에 도달하는 데 걸리는 시간을 최소화하도록 순서가 정해졌습니다.",
            // "목적지"의 "목적"이 목적 주장으로 오인됐던 문장.
            "경로의 첫 번째 목적지로서 덕수궁은 혼잡도가 98%로, 매우혼잡 등급에 해당합니다.",
            "코스는 경복궁에서 출발하여 최단 이동 시간을 기준으로 창덕궁과 북촌한옥마을 순서로 배치되었습니다.",
        )

        assertThat(correct.filter { flagged(it) })
            .describedAs("옳은 문장을 위반으로 세면 규칙이 좋은 설명을 막는다")
            .isEmpty()
    }
}
