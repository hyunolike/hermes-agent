package com.hermes.llm

import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import org.springframework.ai.anthropic.AnthropicCacheOptions
import org.springframework.ai.anthropic.AnthropicCacheStrategy
import org.springframework.ai.anthropic.AnthropicCacheTtl
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.messages.MessageType
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

    fun openAiCompatibleOptions(model: String, baseUrl: String): OpenAiChatOptions =
        OpenAiChatOptions.builder()
            .model(model)
            .baseUrl(baseUrl)
            .maxTokens(MAX_TOKENS)
            .build()
}
