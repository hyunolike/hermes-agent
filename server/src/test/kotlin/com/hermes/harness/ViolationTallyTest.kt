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

        val tally = ViolationTally.aggregate(perRun, explained = perRun.size)

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

        val tally = ViolationTally.aggregate(perRun, explained = runs)

        assertThat(tally.occurrences.getValue(Behaviour.INVENTED_PLACE)).isEqualTo(6)
        assertThat(tally.runsWithViolation.getValue(Behaviour.INVENTED_PLACE)).isEqualTo(3)
        assertThat(tally.runsWithViolation.getValue(Behaviour.INVENTED_PLACE)).isLessThanOrEqualTo(runs)
    }

    @Test
    fun `rate 의 분모는 runs 가 아니라 explained 다`() {
        // EvalMain 의 실제 상황을 재현한다: 5회 실행 중 4회는 Refused Failed 등으로
        // 끝나 점검할 설명이 없다(EvalMain 은 그 실행에 emptyList() 를 쌓는다).
        // 나머지 1회만 Explained 로 끝났고 그 실행에 위반이 하나 있었다.
        // runs 를 분모로 쓰면 1/5 = 20% 로 보이지만, 점검 가능했던 실행은 1건뿐이므로
        // 진짜 위반율은 1/1 = 100% 다 — 리뷰가 지적한 바로 그 왜곡.
        val runs = 5
        val explained = 1
        val perRun = listOf(
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(Behaviour.LLM_CHOSE),
        )

        val tally = ViolationTally.aggregate(perRun, explained)

        assertThat(tally.runsWithViolation.getValue(Behaviour.LLM_CHOSE)).isEqualTo(1)
        assertThat(tally.rate(Behaviour.LLM_CHOSE)).isEqualTo(1.0)
        assertThat(tally.rate(Behaviour.LLM_CHOSE)).isNotEqualTo(1.0 / runs)
    }

    @Test
    fun `explained 가 0 이면 rate 는 0 퍼센트가 아니라 null 로 측정 불가를 표시한다`() {
        // 5회 모두 Refused Failed 로 끝나 점검할 설명이 하나도 없는 상황. rate 가
        // 0.0 을 내면 "위반 없음"과 "잴 수 없음"이 같은 숫자가 되어, 표를 읽는
        // 사람이 전멸한 실행을 무결점 실행으로 오독한다.
        val perRun = List(5) { emptyList<Behaviour>() }

        val tally = ViolationTally.aggregate(perRun, explained = 0)

        Behaviour.entries.forEach { behaviour ->
            assertThat(tally.rate(behaviour)).isNull()
        }
    }
}
