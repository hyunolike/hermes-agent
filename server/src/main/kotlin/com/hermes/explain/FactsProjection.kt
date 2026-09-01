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
        val diagnosis = congestion.get("diagnosis") ?: error("congestion response has no diagnosis")

        val items = MAPPER.createArrayNode()
        (course.get("items") ?: error("course response has no items"))
            .forEach { items.add(project(it, ITEM_FIELDS)) }

        val alternativeNodes = MAPPER.createArrayNode()
        alternatives.forEach { alternativeNodes.add(project(it, ALTERNATIVE_FIELDS)) }

        val congestionNode = project(diagnosis, DIAGNOSIS_FIELDS)
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
