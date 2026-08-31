package com.hermes.harness

/**
 * `runsWithViolation`: 실행당 최대 1(그 행동이 그 실행에서 한 번이라도 있었는가) —
 * 모든 여섯 행이 같은 분모(`runs`)를 공유하므로 프로바이더 비교의 1차 지표다.
 *
 * `occurrences`: 원시 발생 횟수. INVENTED_PLACE 는 한 실행에서 지어낸 이름을
 * 여러 개 낼 수 있어 이 값이 `runs` 를 넘을 수 있다 — 나머지 다섯은 `check()` 가
 * 실행당 최대 1건만 내므로(firstOrNull) 사실상 `runsWithViolation` 과 같다.
 * 분모가 다른 두 지표를 같은 표에 숫자만 나란히 두면 "비교"가 아니게 된다.
 */
data class Tally(val runsWithViolation: Map<Behaviour, Int>, val occurrences: Map<Behaviour, Int>)

object ViolationTally {

    /**
     * @param perRunViolations 실행마다 그 실행에서 있었던 위반의 전체 다중집합
     * (같은 Behaviour 가 여러 번 있을 수 있다). 위반이 없던 실행은 빈 리스트.
     */
    fun aggregate(perRunViolations: List<List<Behaviour>>): Tally {
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

        return Tally(runsWithViolation, occurrences)
    }
}
