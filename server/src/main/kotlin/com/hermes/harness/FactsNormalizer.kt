package com.hermes.harness

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.hermes.explain.FactsProjection

/**
 * 픽스처의 `backendResponses`(엔드포인트 문자열을 키로 갖는 응답들)를 facts 로 만든다.
 *
 * 투영 자체는 하지 않는다 — `FactsProjection` 에 위임한다. 운영 경로가 같은 것을
 * 부르므로, 하네스가 측정한 프롬프트와 서버가 보내는 프롬프트가 같은 모양이다.
 */
object FactsNormalizer {

    /**
     * `GET /api/v1/attractions/1001` 은 뺀다 — 유일하게 고유한 필드인 `area` 를
     * 설명이 쓰지 않으므로 스펙이 이 호출 자체를 쳐냈다.
     */
    fun normalize(fixture: JsonNode): ObjectNode {
        val backendResponses = fixture.get("backendResponses") ?: error("fixture missing backendResponses")

        fun dataOf(endpoint: String): JsonNode =
            (backendResponses.get(endpoint) ?: error("fixture missing endpoint: $endpoint"))
                .get("data") ?: error("endpoint '$endpoint' response has no data")

        return FactsProjection.assemble(
            course = dataOf("GET /api/v1/courses/{uuid}"),
            alternatives = dataOf("GET /api/v1/attractions/1001/alternatives?date=2026-08-15&radius=15"),
            congestion = dataOf("GET /api/v1/attractions/1001/congestion?date=2026-08-15"),
        )
    }
}
