package com.hermes.harness

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 판정용 OpenAI 호환 클라이언트.
 *
 * 설명 생성 어댑터와 같은 `tool_choice` 방식으로 스키마를 강제한다 — 지시만으로
 * JSON 을 요구하면 모델이 산문으로 답할 자유가 남고, 그러면 `QualityJudge` 가
 * 그것을 "판정 불가" 로 처리해 판정이 조용히 멈춘다.
 *
 * `com.hermes.harness` 에 두는 이유: 판정은 하네스 관심사다. 서버는 판정하지 않는다.
 */
class OpenAiCompatibleJudgeProvider(
    override val name: String,
    private val endpoint: URI,
    private val apiKey: String,
    private val model: String,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build(),
) : JudgeProvider {

    private val log = LoggerFactory.getLogger(OpenAiCompatibleJudgeProvider::class.java)

    override fun judge(systemText: String, userText: String): JudgeResponse = try {
        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildBody(systemText, userText, model)))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            JudgeFailed("$name http ${response.statusCode()}")
        } else {
            val arguments = MAPPER.readTree(response.body())
                .at("/choices/0/message/tool_calls/0/function/arguments").asText()
            if (arguments.isNullOrBlank()) {
                JudgeFailed("$name returned no tool call — the judge schema was not forced")
            } else {
                JudgeAnswered(arguments)
            }
        }
    } catch (e: Exception) {
        log.warn("{} judge call failed", name, e)
        JudgeFailed("${e::class.simpleName}: ${e.message}")
    }

    companion object {
        private val MAPPER = ObjectMapper()

        const val TOOL_NAME = "report_quality_findings"

        val OPENAI_ENDPOINT: URI = URI.create("https://api.openai.com/v1/chat/completions")

        fun openAi(apiKey: String, model: String) =
            OpenAiCompatibleJudgeProvider("openai", OPENAI_ENDPOINT, apiKey, model)

        fun buildBody(systemText: String, userText: String, model: String): String {
            val finding = mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "required" to listOf("issue", "evidence", "why"),
                "properties" to mapOf(
                    // 열거값을 스키마에 박아 모르는 값이 오는 경우를 줄인다. 그래도
                    // 오면 QualityJudge 가 "판정 불가" 로 처리한다 — 조용히 버리지 않는다.
                    "issue" to mapOf("type" to "string", "enum" to QualityIssue.entries.map { it.name }),
                    "evidence" to mapOf("type" to "string"),
                    "why" to mapOf("type" to "string"),
                ),
            )

            return MAPPER.writeValueAsString(
                linkedMapOf(
                    "model" to model,
                    "messages" to listOf(
                        linkedMapOf("role" to "system", "content" to systemText),
                        linkedMapOf("role" to "user", "content" to userText),
                    ),
                    "tools" to listOf(
                        linkedMapOf(
                            "type" to "function",
                            "function" to linkedMapOf(
                                "name" to TOOL_NAME,
                                "parameters" to mapOf(
                                    "type" to "object",
                                    "additionalProperties" to false,
                                    "required" to listOf("findings"),
                                    "properties" to mapOf(
                                        "findings" to mapOf("type" to "array", "items" to finding),
                                    ),
                                ),
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
