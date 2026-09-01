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
        /**
         * `uuid|라벨, uuid|라벨` 형식. 라벨은 생략 가능하다.
         *
         * uuid 가 (트림 후) 비어 있는 항목은 버린다 — 빈 uuid 로 만든 항목이
         * 목록에 남으면, 이 목록을 읽는 도달성 검사가 "존재한 적 없는 코스"를
         * BROKEN 으로 보고해 진짜 알람을 흐린다. `"|"`, `"|라벨"` 처럼 uuid
         * 자리가 비어 있으면 항목째 제외한다.
         */
        fun parse(raw: String): DemoCourses = DemoCourses(
            raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { entry ->
                    val parts = entry.split("|", limit = 2).map { it.trim() }
                    val uuid = parts[0]
                    if (uuid.isEmpty()) return@mapNotNull null
                    DemoCourse(uuid = uuid, label = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: uuid)
                },
        )
    }
}
