package com.hermes.facts

import com.fasterxml.jackson.databind.JsonNode
import com.hermes.explain.BackendFacts
import com.hermes.explain.FactsProjection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

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

        // supplyAsync 는 executor 가 종료됐거나 포화 상태면 Future 밖에서,
        // 이 호출 스레드에서 곧바로 RejectedExecutionException 을 던진다 —
        // join() 의 catch 는 Future 안에서 일어난 실패만 보므로 그쪽으로는
        // 절대 걸리지 않는다. 나중 태스크가 이 executor 를
        // destroyMethod = "shutdown" 인 Spring 빈으로 등록하므로, 종료 중에
        // 도착한 요청이 정확히 이 경로를 탄다. 여기서 잡지 않으면 위층이
        // 알고 있는 유일한 실패 타입(HanjeokUnavailableException)이 아닌
        // 예외가 새어나가 안전한 503 대신 처리되지 않은 에러가 된다.
        val congestionFuture: CompletableFuture<JsonNode>
        val alternativesFuture: CompletableFuture<JsonNode>
        try {
            congestionFuture = CompletableFuture.supplyAsync({ client.congestion(attractionId, date) }, executor)
            alternativesFuture =
                CompletableFuture.supplyAsync({ client.alternatives(attractionId, date, radiusKm) }, executor)
        } catch (e: RejectedExecutionException) {
            throw HanjeokUnavailableException("hanjeok call was rejected by the executor (it may be shutting down)", e)
        }

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
