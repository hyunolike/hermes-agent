package com.hermes.shared.config

data class DemoCourse(val uuid: String, val label: String)

/**
 * 쇼케이스가 보여 줄 고정 코스.
 *
 * 서버는 이 uuid 들을 특별 취급하지 않는다 — 데모 코스도 다른 코스와 똑같이
 * 한적 호출 3회를 탄다. 응답을 녹화해 폴백으로 쓰지 않는 이유는, 녹화본을 쓰면
 * "사실의 출처는 언제나 백엔드"라는 규정이 조용히 깨지기 때문이다(스펙 §7).
 *
 * 고르는 기준은 수가 아니라 종류다: (a) 목적지가 매우혼잡이고 대안이 붙은 코스,
 * (b) 대안이 비어 있는 코스, (c) recommendedDate 가 다른 날을 가리키는 코스.
 * 셋이 각각 번들의 다른 문서를 인용해야 하므로 인용 검증이 실제로 도는지가
 * 데모에서 드러난다.
 */
class DemoCourses(val courses: List<DemoCourse>) {

    companion object {
        /** `uuid|라벨, uuid|라벨` 형식. 라벨은 생략 가능하다. */
        fun parse(raw: String): DemoCourses = DemoCourses(
            raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { entry ->
                    val parts = entry.split("|", limit = 2).map { it.trim() }
                    DemoCourse(uuid = parts[0], label = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: parts[0])
                },
        )
    }
}
