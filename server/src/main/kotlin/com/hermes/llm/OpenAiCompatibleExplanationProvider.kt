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
 * OpenAI 호환 chat-completions 어댑터. OpenRouter 와 OpenAI 를 같은 코드로 부른다.
 *
 * 이 어댑터가 하나로 둘을 덮는 이유는 편의가 아니라 **비교의 유효성**이다. 평가의
 * 가치는 "같은 프롬프트, 같은 인용 검증 아래에서 모델만 바꾼다"는 데 있는데,
 * 프로바이더마다 다른 어댑터를 쓰면 측정되는 것이 모델인지 요청 조립 방식인지
 * 흐려진다. 그래서 엔드포인트와 자격 증명만 다르고 본문 조립은 한 곳이다.
 *
 * **스키마는 `tool_choice` 로 강제한다.** OpenAI 는 `response_format` 의
 * `json_schema` 를 네이티브로 지원하지만 여기서는 쓰지 않는다 — OpenRouter 의
 * 무료 모델이 그것을 지원하지 않으므로, 한쪽만 네이티브 경로를 타면 두 실행이
 * 서로 다른 출력 강제 방식을 재게 된다. 우회를 양쪽에 똑같이 적용하는 편이
 * 비교로서 정직하다.
 *
 * **캐시 분기점이 없다.** Anthropic 경로는 번들을 `cache_control` 뒤에 두어 시간당
 * 한 번만 과금되게 하지만, 여기서는 그런 명시적 제어가 없어 번들이 매 요청 다시
 * 처리된다. OpenAI 는 자동 프롬프트 캐싱이 있으나 분기점을 어디 둘지 통제할 수
 * 없고, OpenRouter 무료 티어에는 낮출 비용 자체가 없다. 08-20 기록이 "무료
 * 티어에서는 캐싱 논거가 약해진다"고 남긴 지점이며, 실제 적중률은 평가가 답한다.
 */
class OpenAiCompatibleExplanationProvider(
    override val name: String,
    /**
     * private 이 아닌 이유: 팩토리가 엉뚱한 엔드포인트를 넘겨도 잡을 방법이
     * 없으면 "openai 로 쟀다"는 실행이 사실은 openrouter 를 잰 것일 수 있고,
     * 그 결과로 프로바이더를 고르게 된다.
     */
    val endpoint: URI,
    private val apiKey: String,
    private val model: String,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build(),
) : ExplanationProvider {

    private val log = LoggerFactory.getLogger(OpenAiCompatibleExplanationProvider::class.java)

    override fun explain(systemText: String, userText: String): ProviderResult = try {
        val response = http.send(
            buildRequest(endpoint, apiKey, systemText, userText, model),
            HttpResponse.BodyHandlers.ofString(),
        )

        if (response.statusCode() !in 200..299) {
            Failed("$name http ${response.statusCode()}")
        } else {
            val root = MAPPER.readTree(response.body())
            val arguments = root.at("/choices/0/message/tool_calls/0/function/arguments").asText()
            if (arguments.isNullOrBlank()) {
                Failed("$name returned no tool call — the schema was not forced")
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
        // 비교 실행 중 한쪽이 조용히 죽으면 진단할 방법이 없어진다.
        log.warn("{} explain failed", name, e)
        Failed("${e::class.simpleName}: ${e.message}")
    }

    companion object {
        private val MAPPER = ObjectMapper().registerKotlinModule()

        val OPENROUTER_ENDPOINT: URI = URI.create("https://openrouter.ai/api/v1/chat/completions")
        val OPENAI_ENDPOINT: URI = URI.create("https://api.openai.com/v1/chat/completions")

        // 선언된 도구 이름과 tool_choice 핀이 이 하나의 상수에서 파생된다 — 둘을
        // 독립된 리터럴로 따로 적으면 한쪽만 바뀌어도(리네임 등) 컴파일도, 기존
        // 테스트도 통과하면서 tool_choice 가 존재하지 않는 함수를 가리키게 된다.
        // 그 상태에서 모델은 산문으로 답할 자유를 얻고 출력 계약은 에러 없이 사라진다.
        const val TOOL_NAME = "submit_explanation"

        fun openRouter(apiKey: String, model: String, http: HttpClient? = null) =
            build("openrouter", OPENROUTER_ENDPOINT, apiKey, model, http)

        fun openAi(apiKey: String, model: String, http: HttpClient? = null) =
            build("openai", OPENAI_ENDPOINT, apiKey, model, http)

        private fun build(name: String, endpoint: URI, apiKey: String, model: String, http: HttpClient?) =
            if (http == null) {
                OpenAiCompatibleExplanationProvider(name, endpoint, apiKey, model)
            } else {
                OpenAiCompatibleExplanationProvider(name, endpoint, apiKey, model, http)
            }

        /**
         * 요청 조립을 `explain` 밖으로 뺀 이유: 엔드포인트에 묻혀 있으면 어떤
         * 테스트도 "이 프로바이더가 실제로 어디로 쏘는가"를 검증할 수 없다.
         * 엔드포인트를 설정 가능하게 만들면서 그 값이 실제로 쓰이는지 확인할
         * 방법이 없으면, 설정만 늘고 보장은 늘지 않는다.
         */
        fun buildRequest(
            endpoint: URI,
            apiKey: String,
            systemText: String,
            userText: String,
            model: String,
        ): HttpRequest = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildBody(systemText, userText, model)))
            .build()

        fun buildBody(systemText: String, userText: String, model: String): String {
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
                        linkedMapOf("role" to "user", "content" to userText),
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
