package com.hermes.harness

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

// 필드 순서가 곧 직렬화 순서다(ObjectNode 는 LinkedHashMap 기반) — facts JSON 은
// 요청마다 달라지는 유일한 부분이므로, 같은 픽스처에서 매번 같은 바이트가 나와야
// 실행 간 비교와 (Anthropic 경로의) 캐시 적중이 의미를 가진다.
private val ITEM_FIELDS =
    listOf("attractionId", "name", "visitOrder", "timeLabel", "grade", "reason", "travelMinutesFromPrev")

private val ALTERNATIVE_FIELDS =
    listOf(
        "attractionId", "name", "grade", "concentration", "distanceKm",
        "relationScore", "score", "recommendReason", "travelMinutes",
    )

private val DIAGNOSIS_FIELDS = listOf("concentration", "percentile", "grade", "message")

/**
 * 픽스처의 `backendResponses` (엔드포인트 문자열을 키로 갖는 응답들)를 판정기와
 * 프로바이더가 함께 보는 평평한 facts 객체 하나로 정규화한다.
 *
 * `main` 소스셋에 둔 이유: `EvalMain` 은 `harness` 소스셋에 있어 단위 테스트가
 * 닿지 않는다 — 이 정규화가 사실은 이 태스크에서 가장 위험한 로직인데(판정기와
 * 프로바이더가 서로 다른 facts 모양을 보면 검사 전체가 무력해진다) 테스트가 닿는
 * 곳에 둬야 실수로 깨졌을 때 잡힌다.
 */
object FactsNormalizer {

    private val MAPPER = ObjectMapper()

    private fun project(node: JsonNode, fields: List<String>): ObjectNode {
        val out = MAPPER.createObjectNode()
        fields.forEach { field ->
            out.set<JsonNode>(field, node.get(field) ?: error("expected field '$field' on $node"))
        }
        return out
    }

    /**
     * `GET /api/v1/attractions/1001` 은 뺀다 — 유일하게 고유한 필드인 `area` 를
     * 설명이 쓰지 않으므로 스펙이 이 호출 자체를 쳐냈다(harness/scenarios/
     * travel-context-explanation.md, queries/why-this-place-today.md 참고).
     *
     * 응답마다 `{success, error, data}` 봉투를 쓰므로 `data` 안까지 들어가 읽는다.
     */
    fun normalize(fixture: JsonNode): ObjectNode {
        val backendResponses = fixture.get("backendResponses") ?: error("fixture missing backendResponses")

        fun dataOf(endpoint: String): JsonNode =
            (backendResponses.get(endpoint) ?: error("fixture missing endpoint: $endpoint"))
                .get("data") ?: error("endpoint '$endpoint' response has no data: $endpoint")

        val courseData = dataOf("GET /api/v1/courses/{uuid}")
        val alternativesData = dataOf("GET /api/v1/attractions/1001/alternatives?date=2026-08-15&radius=15")
        val congestionData = dataOf("GET /api/v1/attractions/1001/congestion?date=2026-08-15")
        val diagnosis = congestionData.get("diagnosis") ?: error("congestion response has no diagnosis")

        val items = MAPPER.createArrayNode()
        (courseData.get("items") ?: error("course response has no items"))
            .forEach { items.add(project(it, ITEM_FIELDS)) }

        val alternatives = MAPPER.createArrayNode()
        alternativesData.forEach { alternatives.add(project(it, ALTERNATIVE_FIELDS)) }

        val congestion = project(diagnosis, DIAGNOSIS_FIELDS)
        congestion.set<JsonNode>("betterDates", congestionData.get("betterDates") ?: MAPPER.createArrayNode())

        val facts = MAPPER.createObjectNode()
        facts.set<JsonNode>("items", items)
        facts.set<JsonNode>("alternatives", alternatives)
        facts.put("targetDate", courseData.get("targetDate").asText())
        facts.put("title", courseData.get("title").asText())
        facts.put("congestionReductionRate", courseData.get("congestionReductionRate").asInt())
        facts.put("summary", courseData.get("summary").asText())
        facts.set<JsonNode>("recommendedDate", courseData.get("recommendedDate") ?: MAPPER.nullNode())
        facts.set<JsonNode>("congestion", congestion)

        return facts
    }
}
