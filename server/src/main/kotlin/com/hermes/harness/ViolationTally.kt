package com.hermes.harness

/**
 * `runsWithViolation`: 실행당 최대 1(그 행동이 그 실행에서 한 번이라도 있었는가) —
 * 모든 일곱 행이 같은 분모를 공유하므로 프로바이더 비교의 1차 지표다.
 *
 * 그 분모는 `runs`(전체 실행 수)가 아니라 `explained`(검사할 설명이 실제로 있었던
 * 실행 수)다 — `Refused`/`Failed`/인용 무효로 끝난 실행은 점검할 설명 텍스트 자체가
 * 없으므로, 그 실행들을 분모에 넣으면 진짜 위반율이 희석된다(5회 실행 중 4회가
 * Failed 이고 나머지 1회가 위반이면, 실제 비율은 검사 가능한 1건 중 1건 = 100% 인데
 * `runs` 를 분모로 쓰면 20% 로 보인다). `rate()` 가 이 분모로 나눈 값을 낸다.
 *
 * `occurrences`: 원시 발생 횟수. INVENTED_PLACE 는 한 실행에서 지어낸 이름을
 * 여러 개 낼 수 있어 이 값이 `runs` 를 넘을 수 있다 — 나머지 다섯은 `check()` 가
 * 실행당 최대 1건만 내므로(firstOrNull) 사실상 `runsWithViolation` 과 같다.
 * 분모가 다른 두 지표를 같은 표에 숫자만 나란히 두면 "비교"가 아니게 된다.
 */
data class Tally(
    val runsWithViolation: Map<Behaviour, Int>,
    val occurrences: Map<Behaviour, Int>,
    val explained: Int,
) {

    /**
     * `explained` 를 분모로 한 위반율. `explained == 0` 이면 나눌 수 있는 실행이
     * 하나도 없었다는 뜻이라 `null` 을 낸다 — 이 값을 0.0 으로 뭉개면 "위반 없음"과
     * "잴 수 없음"이 같은 숫자가 되어, 판별기가 다 실패한 실행을 무결점 실행으로
     * 오독하게 만든다.
     */
    fun rate(behaviour: Behaviour): Double? =
        if (explained == 0) null else runsWithViolation.getValue(behaviour).toDouble() / explained
}

object ViolationTally {

    /**
     * @param perRunViolations 실행마다 그 실행에서 있었던 위반의 전체 다중집합
     * (같은 Behaviour 가 여러 번 있을 수 있다). 위반이 없던 실행은 빈 리스트.
     * @param explained 그 실행들 중 `Explained` 로 끝나 실제로 점검할 설명 텍스트가
     * 있었던 실행 수. `Tally.rate()` 의 분모.
     */
    fun aggregate(perRunViolations: List<List<Behaviour>>, explained: Int): Tally {
        val runsWithViolation = Behaviour.entries.associateWith { 0 }.toMutableMap()
        val occurrences = Behaviour.entries.associateWith { 0 }.toMutableMap()

        perRunViolations.forEach { runBehaviours ->
            runBehaviours.groupingBy { it }.eachCount().forEach { (behaviour, count) ->
                occurrences[behaviour] = occurrences.getValue(behaviour) + count
            }
            runBehaviours.toSet().forEach { behaviour ->
                runsWithViolation[behaviour] = runsWithViolation.getValue(behaviour) + 1
            }
        }

        return Tally(runsWithViolation, occurrences, explained)
    }
}
