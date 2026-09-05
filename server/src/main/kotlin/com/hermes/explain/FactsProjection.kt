package com.hermes.explain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * facts JSON 의 모양을 정하는 단일 출처.
 *
 * 평가 하네스(픽스처에서)와 운영(한적 HTTP 에서)이 **둘 다 여기를 부른다.**
 * 각자 투영하면 하네스가 측정한 프롬프트와 운영이 보내는 프롬프트가 달라지고,
 * 그러면 평가 수치가 운영에 대해 아무것도 말해주지 않는다.
 *
 * 필드 순서가 곧 직렬화 순서다(ObjectNode 는 LinkedHashMap 기반). facts 는
 * 요청마다 달라지는 유일한 부분이므로, 같은 입력에서 같은 바이트가 나와야
 * 실행 간 비교가 의미를 가진다.
 */
object FactsProjection {

    private val MAPPER = ObjectMapper()

    val ITEM_FIELDS: List<String> =
        listOf("attractionId", "name", "visitOrder", "timeLabel", "grade", "reason", "travelMinutesFromPrev")

    val ALTERNATIVE_FIELDS: List<String> =
        listOf(
            "attractionId", "name", "grade", "concentration", "distanceKm",
            "relationScore", "score", "recommendReason", "travelMinutes",
        )

    val DIAGNOSIS_FIELDS: List<String> = listOf("concentration", "percentile", "grade", "message")

    fun project(node: JsonNode, fields: List<String>): ObjectNode {
        val out = MAPPER.createObjectNode()
        fields.forEach { field ->
            out.set<JsonNode>(field, node.get(field) ?: error("expected field '$field' on $node"))
        }
        return out
    }

    /**
     * @param course 코스 응답의 `data`
     * @param alternatives 대안 응답의 `data` (배열)
     * @param congestion 혼잡도 응답의 `data`
     */
    fun assemble(course: JsonNode, alternatives: JsonNode, congestion: JsonNode): ObjectNode {
        // 예보 커버리지가 없으면 `diagnosis` 자체가 없고 HTTP 는 200 이다. 이건
        // 실패가 아니라 제품 상태다(concepts/congestion-diagnosis.md). 예외로 다루면
        // 멀쩡한 코스가 백엔드 장애로 503 이 되어 돌아간다 — 실제로 그랬다.
        //
        // 없는 값은 **자리를 비운다.** 0 이나 빈 문자열을 채우면 모델이 그것을 값으로
        // 읽고 "백분위 0" 같은 문장을 쓴다. 대신 hasCongestionData 를 양쪽 분기에
        // 모두 실어, 모델이 부재를 해석하지 않고 플래그를 읽게 한다.
        val diagnosis = congestion.get("diagnosis")

        val items = MAPPER.createArrayNode()
        (course.get("items") ?: error("course response has no items"))
            .forEach { items.add(project(it, ITEM_FIELDS)) }

        val alternativeNodes = MAPPER.createArrayNode()
        alternatives.forEach { alternativeNodes.add(project(it, ALTERNATIVE_FIELDS)) }

        val congestionNode = if (diagnosis == null || diagnosis.isNull) {
            MAPPER.createObjectNode().apply {
                put("hasCongestionData", false)
                put(
                    "message",
                    congestion.path("message").asText("이 장소는 집중률 예측 데이터가 제공되지 않아요."),
                )
            }
        } else {
            project(diagnosis, DIAGNOSIS_FIELDS).apply { put("hasCongestionData", true) }
        }
        congestionNode.set<JsonNode>("betterDates", congestion.get("betterDates") ?: MAPPER.createArrayNode())

        val facts = MAPPER.createObjectNode()
        facts.set<JsonNode>("items", items)
        facts.set<JsonNode>("alternatives", alternativeNodes)
        facts.put("targetDate", (course.get("targetDate") ?: error("course response has no targetDate")).asText())
        facts.put("title", (course.get("title") ?: error("course response has no title")).asText())
        facts.put(
            "congestionReductionRate",
            (course.get("congestionReductionRate") ?: error("course response has no congestionReductionRate")).asInt(),
        )
        facts.put("summary", (course.get("summary") ?: error("course response has no summary")).asText())
        facts.set<JsonNode>("recommendedDate", course.get("recommendedDate") ?: MAPPER.nullNode())
        facts.set<JsonNode>("congestion", congestionNode)

        return facts
    }
}
