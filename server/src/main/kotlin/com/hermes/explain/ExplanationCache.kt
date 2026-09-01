package com.hermes.explain

import com.hermes.llm.Explanation
import java.util.Collections

/**
 * `courseUuid` → 설명. 코스는 불변이므로 같은 uuid 는 같은 설명이다.
 *
 * Redis 공유 캐시를 넣지 않는다(08-17 설계문 §8) — 재시작이 드물고, 필요해지면
 * 그때 붙인다. 인메모리이므로 인스턴스가 늘면 적중률이 나뉜다는 것은 알려진 대가다.
 */
class ExplanationCache(private val maxEntries: Int = 1000) {

    private val entries: MutableMap<String, Explanation> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Explanation>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Explanation>): Boolean =
                size > maxEntries
        },
    )

    fun get(courseUuid: String): Explanation? = entries[courseUuid]

    fun put(courseUuid: String, explanation: Explanation) {
        entries[courseUuid] = explanation
    }

    fun size(): Int = entries.size
}
