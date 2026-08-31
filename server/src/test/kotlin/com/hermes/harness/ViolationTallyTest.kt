package com.hermes.harness

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ViolationTallyTest {

    @Test
    fun `한 실행 안의 중복 위반은 occurrences 에는 여러 번, runsWithViolation 에는 한 번만 센다`() {
        val perRun = listOf(
            listOf(Behaviour.INVENTED_PLACE, Behaviour.INVENTED_PLACE, Behaviour.LLM_CHOSE),
            listOf(Behaviour.INVENTED_PLACE),
            emptyList(),
        )

        val tally = ViolationTally.aggregate(perRun)

        assertThat(tally.occurrences.getValue(Behaviour.INVENTED_PLACE)).isEqualTo(3)
        assertThat(tally.runsWithViolation.getValue(Behaviour.INVENTED_PLACE)).isEqualTo(2)
        assertThat(tally.occurrences.getValue(Behaviour.LLM_CHOSE)).isEqualTo(1)
        assertThat(tally.runsWithViolation.getValue(Behaviour.LLM_CHOSE)).isEqualTo(1)
        assertThat(tally.runsWithViolation.getValue(Behaviour.DEFERRED_DESTINATION)).isEqualTo(0)
    }

    @Test
    fun `occurrences 는 runs 를 넘을 수 있어도 runsWithViolation 은 넘지 않는다`() {
        // INVENTED_PLACE 가 실제로 겪는 상황을 재현한다 — 한 실행에서 지어낸
        // 이름을 여러 개 낼 수 있어 원시 발생 횟수가 runs 를 넘는다. 나머지
        // 다섯 행은 실행당 최대 1건이라 이 둘이 언제나 같다 — 그래서 서로 다른
        // 분모를 가진 두 지표를 한 표에 나란히 두면 "비교"가 아니게 된다.
        val runs = 3
        val perRun = List(runs) { listOf(Behaviour.INVENTED_PLACE, Behaviour.INVENTED_PLACE) }

        val tally = ViolationTally.aggregate(perRun)

        assertThat(tally.occurrences.getValue(Behaviour.INVENTED_PLACE)).isEqualTo(6)
        assertThat(tally.runsWithViolation.getValue(Behaviour.INVENTED_PLACE)).isEqualTo(3)
        assertThat(tally.runsWithViolation.getValue(Behaviour.INVENTED_PLACE)).isLessThanOrEqualTo(runs)
    }
}
