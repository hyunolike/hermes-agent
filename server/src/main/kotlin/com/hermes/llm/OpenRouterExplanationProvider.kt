package com.hermes.llm

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * OpenRouter 무료 티어 비교용 어댑터.
 *
 * 캐시 분기점이 없다 — 무료 티어에는 낮출 비용이 없으므로 번들이 매 요청 다시
 * 처리된다. 그것이 쓰는 것은 돈이 아니라 지연과 레이트리밋 한 칸이다.
 * 08-20 기록이 "무료 티어에서는 캐싱 논거가 약해진다"고 남긴 지점이 여기다.
 */
class OpenRouterExplanationProvider(
    private val apiKey: String,
    private val model: String,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build(),
) : ExplanationProvider {

    private val log = LoggerFactory.getLogger(OpenRouterExplanationProvider::class.java)

    override val name = "openrouter"

    override fun explain(systemText: String, factsJson: String): ProviderResult = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildBody(systemText, factsJson, model)))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            Failed("openrouter http ${response.statusCode()}")
        } else {
            val root = MAPPER.readTree(response.body())
            val arguments = root.at("/choices/0/message/tool_calls/0/function/arguments").asText()
            if (arguments.isNullOrBlank()) {
                Failed("openrouter returned no tool call — the schema was not forced")
            } else {
                val parsed = MAPPER.readTree(arguments)
                Answered(
                    explanation = Explanation(
                        explanation = parsed.at("/explanation").asText(),
                        citations = parsed.at("/citations").map { it.asText() },
                    ),
                    usage = ProviderUsage(
                        cacheReadTokens = 0,
                        cacheCreationTokens = 0,
                        inputTokens = root.at("/usage/prompt_tokens").asLong(0),
                        outputTokens = root.at("/usage/completion_tokens").asLong(0),
                    ),
                )
            }
        }
    } catch (e: Exception) {
        // Anthropic 어댑터와 같은 이유로 예외의 정체를 지우지 않는다 — 프로바이더
        // 비교 실행 중 openrouter 쪽이 조용히 죽으면 진단할 방법이 없어진다.
        log.warn("openrouter explain failed", e)
        Failed("${e::class.simpleName}: ${e.message}")
    }

    companion object {
        private val MAPPER = ObjectMapper().registerKotlinModule()

        // 선언된 도구 이름과 tool_choice 핀이 이 하나의 상수에서 파생된다 — 둘을
        // 독립된 리터럴로 따로 적으면 한쪽만 바뀌어도(리네임 등) 컴파일도, 기존
        // 네 테스트도 통과하면서 tool_choice 가 존재하지 않는 함수를 가리키게 된다.
        // 그 상태에서 모델은 산문으로 답할 자유를 얻고 출력 계약은 에러 없이 사라진다.
        const val TOOL_NAME = "submit_explanation"

        fun buildBody(systemText: String, factsJson: String, model: String): String {
            val schema = mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "required" to listOf("explanation", "citations"),
                "properties" to mapOf(
                    "explanation" to mapOf("type" to "string"),
                    "citations" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                ),
            )

            return MAPPER.writeValueAsString(
                linkedMapOf(
                    "model" to model,
                    "messages" to listOf(
                        linkedMapOf("role" to "system", "content" to systemText),
                        linkedMapOf("role" to "user", "content" to factsJson),
                    ),
                    "tools" to listOf(
                        linkedMapOf(
                            "type" to "function",
                            "function" to linkedMapOf(
                                "name" to TOOL_NAME,
                                "parameters" to schema,
                            ),
                        ),
                    ),
                    "tool_choice" to linkedMapOf(
                        "type" to "function",
                        "function" to linkedMapOf("name" to TOOL_NAME),
                    ),
                ),
            )
        }
    }
}
