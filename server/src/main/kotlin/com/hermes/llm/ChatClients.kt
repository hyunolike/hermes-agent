package com.hermes.llm

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.ai.anthropic.AnthropicCacheOptions
import org.springframework.ai.anthropic.AnthropicCacheStrategy
import org.springframework.ai.anthropic.AnthropicCacheTtl
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions

/**
 * 프로바이더별 요청 옵션을 만든다.
 *
 * **순수 함수만 둔다.** 클라이언트도 키도 여기서 만지지 않는다 — 지금
 * `buildParams` 가 순수 함수인 것과 같은 이유다. 캐시 분기점과 스키마 강제가
 * 살아 있는지는 키 없이 검사할 수 있어야 한다(ChatClientsTest).
 */
object ChatClients {

    // `/v1`이 baseUrl 쪽에 붙는다 — SDK 접미사가 아니다. `openai-java-core`의
    // `OpenAiSetup`을 javap 로 까 보면 그 안의 기본값 자체가
    // `OPENAI_URL = "https://api.openai.com/v1"`이고, `OpenAiChatModel.Builder`는
    // 그 위에 `/chat/completions`만 얹는다(바이트코드로 확인: baseUrl 뒤에 다른 걸
    // 붙이지 않는다). 이전에는 이 값이 "구 프로바이더 엔드포인트에서
    // `/v1/chat/completions`를 뗀 것"이라고 잘못 가정해 `/v1`까지 통째로
    // 떼어냈었다 — SpringAiRequestShapeTest 의 캡처 테스트가 그 상태에서 실제
    // 경로가 `/v1/chat/completions`가 아니라 `/chat/completions`임을 잡아냈다.
    // 뗄 접미사는 `/chat/completions`뿐이다.
    const val OPENAI_BASE_URL = "https://api.openai.com/v1"
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

    // 8192 가 아니다 — max_tokens 는 thinking 과 응답 텍스트를 합쳐 덮고,
    // Opus 5 는 thinking 이 기본 ON 이다.
    const val MAX_TOKENS = 16_000

    /**
     * `Explanation` 의 JSON 스키마를 SDK 가 직접 유도하게 한다 — 손으로 다시 쓰면
     * 데이터 클래스와 드리프트한다. 유도 함수 자체는 SDK 내부(internal)라 부를 수
     * 없어, 공개 경로인 `.outputConfig(Class)` 를 최소 요청 한 번에 태워 유도된
     * 포맷만 꺼낸다. `Explanation` 타입은 고정이므로 이 값은 요청마다 달라지지 않는다.
     */
    private val derivedExplanationFormat: JsonOutputFormat by lazy {
        MessageCreateParams.builder()
            .model("claude-opus-5")
            .maxTokens(MAX_TOKENS.toLong())
            .addUserMessage("schema derivation only")
            .outputConfig(Explanation::class.java)
            .build()
            .rawParams
            .outputConfig()
            .orElseThrow()
            .format()
            .orElseThrow()
    }

    fun explanationSchema(): JsonOutputFormat = derivedExplanationFormat

    /**
     * 구 코드에 있던 우회 — `outputConfig(Class)` 와 `outputConfig(OutputConfig)` 를
     * 순서대로 두 번 불러 effort 를 살리는 — 가 필요 없다. Spring AI 는 완성된
     * `OutputConfig` 를 그대로 받는다.
     */
    fun anthropicOptions(model: String): AnthropicChatOptions =
        AnthropicChatOptions.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .cacheOptions(
                AnthropicCacheOptions.builder()
                    .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                    .messageTypeTtl(MessageType.SYSTEM, AnthropicCacheTtl.ONE_HOUR)
                    .build(),
            )
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(derivedExplanationFormat)
                    .build(),
            )
            .build()

    /**
     * 구 `OpenAiCompatibleExplanationProvider`는 `tool_choice`로 스키마를 강제했다.
     * Spring AI 경로로 옮기면서 그 대응물을 빠뜨렸었다 — `model`/`baseUrl`/
     * `maxTokens`만 싣고 출력 계약이 전혀 없었다. 그 결과 모델이 JSON이 아니라
     * 산문으로 답해("경복궁은...") `SpringAiExplanationProvider.toProviderResult`의
     * `MAPPER.readTree(text)`가 깨졌다(`eval openai 5` 5회 전부 explained=0로
     * 실측). 이번에 `response_format: json_schema`로 강제해 그 구멍을 막는다 —
     * 설계 스펙의 프로바이더 표가 원래 요구하던 것이다.
     */
    fun openAiCompatibleOptions(model: String, baseUrl: String): OpenAiChatOptions =
        OpenAiChatOptions.builder()
            .model(model)
            .baseUrl(baseUrl)
            .maxTokens(MAX_TOKENS)
            .responseFormat(
                OpenAiChatModel.ResponseFormat.builder()
                    .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                    .jsonSchema(explanationSchemaJson())
                    .strict(true)
                    .build(),
            )
            .build()

    /**
     * `explanationSchema()`(Anthropic SDK가 `Explanation`에서 유도한 것, 단일
     * 소스)를 순수 JSON 스키마 텍스트로 한 번 더 꺼낸다 — 손으로 다시 쓴
     * 두 번째 사본을 만들지 않는다. `OpenAiChatModel$ResponseFormat.jsonSchema`는
     * `String`을 받는데, `OpenAiChatModel`을 javap 로 까 보면 그 문자열을
     * `objectMapper.readValue(jsonSchema, ResponseFormatJsonSchema.JsonSchema.Schema::class.java)`
     * 로 다시 파싱한다 — 즉 순수 JSON 텍스트여야 한다(래퍼 없이). 스키마 자체는
     * `derivedExplanationFormat.schema()._additionalProperties()`에 이미
     * `Map<String, JsonValue>`로 들어 있다(ChatClientsTest 가 `required` 필드를
     * 같은 경로로 읽어 이미 검증한다) — 그 맵을 `JsonValue.from(...)`으로 다시
     * 감싼 뒤 `.convert(JsonNode::class.java)`로 풀면, Anthropic SDK 자신의
     * Jackson 매퍼가 그 값을 직렬화한다. 손으로 만든 매퍼가 아니다.
     */
    private fun explanationSchemaJson(): String =
        JsonValue.from(explanationSchema().schema()._additionalProperties())
            .convert(JsonNode::class.java)
            .toString()
}
