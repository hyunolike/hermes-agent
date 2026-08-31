package com.hermes.harness

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.hermes.context.BundleLoader
import com.hermes.context.CitationValidator
import com.hermes.context.PromptAssembler
import com.hermes.explain.BackendFacts
import com.hermes.explain.ExplainOutcome
import com.hermes.explain.ExplanationService
import com.hermes.explain.Explained
import com.hermes.explain.Unavailable
import com.hermes.llm.AnthropicExplanationProvider
import com.hermes.llm.ExplanationProvider
import com.hermes.llm.OpenRouterExplanationProvider
import java.io.File

private val MAPPER = ObjectMapper()

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

private fun project(node: JsonNode, fields: List<String>): ObjectNode {
    val out = MAPPER.createObjectNode()
    fields.forEach { field ->
        out.set<JsonNode>(field, node.get(field) ?: error("expected field '$field' on $node"))
    }
    return out
}

/**
 * 픽스처의 `backendResponses` (엔드포인트 문자열을 키로 갖는 4개 응답)를 판정기와
 * 프로바이더가 함께 보는 평평한 facts 객체 하나로 정규화한다.
 *
 * `GET /api/v1/attractions/1001` 은 뺀다 — 유일하게 고유한 필드인 `area` 를
 * 설명이 쓰지 않으므로 스펙이 이 호출 자체를 쳐냈다(harness/scenarios/
 * travel-context-explanation.md, queries/why-this-place-today.md 참고).
 *
 * 응답마다 `{success, error, data}` 봉투를 쓰므로 `data` 안까지 들어가 읽는다.
 */
private fun normalizeFacts(fixture: JsonNode): ObjectNode {
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

/**
 * 금지 행동 6종을 센다. 실제 API 를 부르므로 돈이 든다.
 *
 * 서버를 띄우지 않는다 — presentation 을 건너뛰고 application 층을 직접 부르므로,
 * 여기서 통과한 프롬프트 조립과 인용 검증이 운영에서 도는 것과 같은 코드다.
 *
 * 프로바이더에게 보내는 factsJson 과 ForbiddenBehaviours.check 에 넘기는 factsJson 은
 * 반드시 같은 문자열이어야 한다 — 판정기가 `items`/`alternatives` 를 그 최상위에서
 * 읽으므로, 둘이 다른 모양을 보면 판정은 아무것도 검증하지 못한다(모든 지명이
 * INVENTED_PLACE 로 잡히고 순서 검사는 조용히 비활성화된다).
 *
 *   ./gradlew eval --args="anthropic 5"
 *   ./gradlew eval --args="openrouter 5"
 */
fun main(args: Array<String>) {
    val providerName = args.getOrNull(0) ?: "anthropic"
    val runs = args.getOrNull(1)?.toIntOrNull() ?: 5

    val bundle = BundleLoader.load()
    val provider: ExplanationProvider = when (providerName) {
        "anthropic" -> AnthropicExplanationProvider(AnthropicOkHttpClient.fromEnv())
        "openrouter" -> OpenRouterExplanationProvider(
            apiKey = System.getenv("OPENROUTER_API_KEY")
                ?: error("OPENROUTER_API_KEY is not set"),
            model = System.getenv("OPENROUTER_MODEL") ?: "nvidia/nemotron-nano-9b-v2:free",
        )
        else -> error("unknown provider: $providerName")
    }

    val service = ExplanationService(PromptAssembler(bundle), CitationValidator(bundle), provider)

    val fixture = MAPPER.readTree(File("harness/fixtures/course-explanation-request.json"))
    val courseUuid = fixture.get("courseUuid").asText()
    val factsJson = normalizeFacts(fixture).toString()
    val facts = BackendFacts(courseUuid, factsJson)

    val tally = Behaviour.entries.associateWith { 0 }.toMutableMap()
    var explained = 0
    var unavailable = 0

    repeat(runs) { i ->
        when (val outcome: ExplainOutcome = service.explain(facts)) {
            is Explained -> {
                explained++
                val violations = ForbiddenBehaviours.check(outcome.explanation, factsJson, bundle)
                violations.forEach { tally[it.behaviour] = tally.getValue(it.behaviour) + 1 }
                println("[$i] explained — violations: ${violations.ifEmpty { "none" }}")
            }
            is Unavailable -> {
                unavailable++
                println("[$i] unavailable — ${outcome.reason}")
            }
        }
    }

    println()
    println("provider    : ${provider.name}")
    println("runs        : $runs")
    println("explained   : $explained")
    println("unavailable : $unavailable")
    println("violations  :")
    tally.forEach { (behaviour, count) -> println("  ${behaviour.name.padEnd(22)} $count") }
}
