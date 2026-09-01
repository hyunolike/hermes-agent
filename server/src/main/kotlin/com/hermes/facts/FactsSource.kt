package com.hermes.facts

import com.fasterxml.jackson.databind.JsonNode
import com.hermes.explain.BackendFacts
import com.hermes.explain.FactsProjection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService

/**
 * 한적에서 사실을 모은다 — 호출 3회, 왕복 2회.
 *
 * 코스를 먼저 받아야 대상 관광지와 날짜를 알 수 있고, 그 둘이 정해지면 혼잡도와
 * 대안은 서로를 기다릴 이유가 없으므로 병렬로 간다.
 *
 * 목적지는 언제나 `visitOrder` 가 가장 작은 항목이다. `CourseRoutePolicy.bestOrder`
 * 가 `listOf(originId) + best` 를 반환하므로 목적지는 늘 첫 방문지이고 뒤로 밀리지
 * 않는다(concepts/travel-context-layer.md).
 */
class FactsSource(
    private val client: HanjeokClient,
    private val radiusKm: Int = 15,
    private val executor: ExecutorService,
) {

    fun fetch(courseUuid: String): BackendFacts {
        val course = client.course(courseUuid)

        val items = course.get("items")
        if (items == null || !items.isArray || items.isEmpty) {
            throw HanjeokUnavailableException("course $courseUuid carried no items")
        }

        val destination = items.minByOrNull { it.path("visitOrder").asInt(Int.MAX_VALUE) }
            ?: throw HanjeokUnavailableException("course $courseUuid has no destination item")
        val attractionId = destination.path("attractionId").asLong(0L)
        if (attractionId == 0L) throw HanjeokUnavailableException("destination item has no attractionId")

        val date = course.path("targetDate").asText(null)
            ?: throw HanjeokUnavailableException("course $courseUuid has no targetDate")

        val congestionFuture = CompletableFuture.supplyAsync({ client.congestion(attractionId, date) }, executor)
        val alternativesFuture =
            CompletableFuture.supplyAsync({ client.alternatives(attractionId, date, radiusKm) }, executor)

        val congestion = join(congestionFuture, "congestion")
        val alternatives = join(alternativesFuture, "alternatives")

        val facts = try {
            FactsProjection.assemble(course = course, alternatives = alternatives, congestion = congestion)
        } catch (e: IllegalStateException) {
            // 투영은 필드가 빠지면 error() 를 던진다. 반쪽짜리 facts 로 설명을
            // 만들면 없는 근거를 지어내라고 시키는 것과 같으므로 여기서 멈춘다.
            throw HanjeokUnavailableException("hanjeok response did not carry the expected fields: ${e.message}", e)
        }

        return BackendFacts(courseUuid = courseUuid, json = facts.toString())
    }

    private fun join(future: CompletableFuture<JsonNode>, what: String): JsonNode = try {
        future.join()
    } catch (e: CompletionException) {
        val cause = e.cause
        if (cause is HanjeokUnavailableException) throw cause
        throw HanjeokUnavailableException("hanjeok $what call failed: ${cause?.let { it::class.simpleName }}", cause)
    }
}
