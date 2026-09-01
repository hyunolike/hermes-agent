package com.hermes.facts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.web.client.RestClient

interface HanjeokClient {
    fun course(courseUuid: String): JsonNode
    fun congestion(attractionId: Long, date: String): JsonNode
    fun alternatives(attractionId: Long, date: String, radiusKm: Int): JsonNode
}

/**
 * 한적 공개 API 클라이언트.
 *
 * 아웃바운드 호출이므로 스펙 §2.2 의 HTTP 금지 대상이 아니다 — 그 금지는
 * 인바운드(요청을 받는 것)에 관한 것이고, 하네스는 이 클라이언트를 대역으로
 * 갈아끼워 서버 없이 application 층을 구동한다.
 *
 * 재시도하지 않는다. 스펙 §8 이 한적 실패를 재시도 없는 503 으로 규정한다 —
 * 설명이 없는 것은 안전한 실패이고, 한적의 규칙 기반 문구가 남는다.
 *
 * 이 클래스를 나가는 경로는 완전한 data 페이로드거나 던져진
 * HanjeokUnavailableException, 둘 중 하나뿐이다. "조용히 null 반환"은 없다 —
 * data 필드가 JSON null 로 와도(필드 자체가 없는 것과 마찬가지로) 실패로
 * 다룬다.
 *
 * 응답 본문은 String 으로 받아 이 클래스가 직접 파싱한다. RestClient 의 기본
 * JSON 컨버터가 Jackson 3(tools.jackson) 이라, Jackson 2 타입인 JsonNode(com.
 * fasterxml.jackson) 를 곧바로 body(JsonNode::class.java) 로 요청하면 "추상
 * 타입이라 만들 수 없다"는 InvalidDefinitionException 으로 깨진다.
 * FactsProjection/FactsNormalizer 등 나머지 코드가 전부 Jackson 2 JsonNode 를
 * 쓰므로, 버전을 맞추기 위해 여기서 Jackson 2 ObjectMapper 로 직접 readTree
 * 한다.
 */
class RestHanjeokClient(private val rest: RestClient) : HanjeokClient {

    private val mapper = ObjectMapper()

    override fun course(courseUuid: String): JsonNode =
        get("/api/v1/courses/{uuid}", mapOf("uuid" to courseUuid))

    override fun congestion(attractionId: Long, date: String): JsonNode =
        get("/api/v1/attractions/{id}/congestion?date={date}", mapOf("id" to attractionId, "date" to date))

    override fun alternatives(attractionId: Long, date: String, radiusKm: Int): JsonNode =
        get(
            "/api/v1/attractions/{id}/alternatives?date={date}&radius={radius}",
            mapOf("id" to attractionId, "date" to date, "radius" to radiusKm),
        )

    private fun get(template: String, vars: Map<String, Any>): JsonNode {
        val raw: String = try {
            rest.get().uri(template, vars).retrieve().body(String::class.java) ?: ""
        } catch (e: Exception) {
            throw HanjeokUnavailableException("hanjeok call failed for $template: ${e::class.simpleName}", e)
        }

        if (raw.isBlank()) {
            throw HanjeokUnavailableException("hanjeok returned an empty body for $template")
        }

        val body: JsonNode = try {
            mapper.readTree(raw)
        } catch (e: Exception) {
            throw HanjeokUnavailableException(
                "hanjeok response was not valid JSON for $template: ${e::class.simpleName}",
                e,
            )
        }

        if (!body.path("success").asBoolean(false)) {
            throw HanjeokUnavailableException(
                "hanjeok answered success=false for $template: ${body.path("error").asText("unknown")}",
            )
        }

        // body.get("data") 가 반환하는 값은 세 가지다: 필드가 아예 없으면 Kotlin
        // null, 필드가 있고 JSON null 이면 NullNode(비어있지 않은 객체), 필드가
        // 있고 값이 있으면 그 노드. 뒤의 두 경우를 구분하지 않으면 success=true
        // 인데 실제로는 데이터가 없는 응답이 "빈 것 같은 JsonNode"로 조용히
        // 통과해 위층이 존재하지 않는 사실을 설명하게 만든다.
        val data = body.get("data")
        if (data == null || data.isNull) {
            throw HanjeokUnavailableException("hanjeok response carried no data for $template")
        }
        return data
    }
}
