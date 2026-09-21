package com.hermes.llm

import com.anthropic.models.messages.OutputConfig
import com.fasterxml.jackson.core.type.TypeReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicCacheStrategy
import org.springframework.ai.anthropic.AnthropicCacheTtl
import org.springframework.ai.chat.messages.MessageType

/**
 * 옵션 조립을 순수 함수로 꺼내 둔 이유는 하나다 — 키 없이 검사할 수 있어야 한다.
 * 실제로 나가는 바이트는 SpringAiRequestShapeTest 가 본다. 이 테스트가 고정하는
 * 것은 **우리가 프레임워크에 넣는 것**이다.
 */
class ChatClientsTest {

    @Test
    fun `anthropic 은 system 블록만 1시간 TTL 로 캐시한다`() {
        val options = ChatClients.anthropicOptions("claude-opus-5")
        val cache = options.cacheOptions

        assertThat(cache.strategy).isEqualTo(AnthropicCacheStrategy.SYSTEM_ONLY)
        assertThat(cache.messageTypeTtl[MessageType.SYSTEM]).isEqualTo(AnthropicCacheTtl.ONE_HOUR)
    }

    @Test
    fun `anthropic 은 effort 를 낮게 두고 스키마를 강제한다`() {
        val options = ChatClients.anthropicOptions("claude-opus-5")
        // getOutputConfig() 는 org.jspecify.annotations.Nullable 로 선언돼 있다 —
        // anthropicOptions 가 항상 채우므로 여기서는 non-null 임을 단언한다.
        val outputConfig = options.outputConfig!!

        assertThat(outputConfig.effort().orElse(null)).isEqualTo(OutputConfig.Effort.LOW)
        assertThat(outputConfig.format()).isPresent()
    }

    @Test
    fun `모델과 토큰 한도가 인자와 상수를 따른다`() {
        val options = ChatClients.anthropicOptions("claude-opus-5")

        assertThat(options.model).isEqualTo("claude-opus-5")
        // 8192 가 아니다 — max_tokens 는 thinking 과 응답을 합쳐 덮고,
        // Opus 5 는 thinking 이 기본 ON 이라 8192 는 잘릴 위험이 있다.
        assertThat(options.maxTokens).isEqualTo(16000)
    }

    @Test
    fun `유도된 스키마는 두 필드를 모두 요구한다`() {
        // 문자열 부분일치(contains)로는 부족하다 — "explanation"/"citations" 는
        // required 가 비어 있어도 properties 블록에 그대로 나타난다. required
        // 배열 자체를 꺼내야 "선언됨"과 "필수"를 구분할 수 있다. 같은 스키마를
        // RawParams.kt 의 StructuredMessageCreateParams.view() 가 이미 같은
        // 경로로 읽으므로, 두 테스트가 스키마를 같은 방식으로 검사하도록 맞춘다.
        val requiredFields = ChatClients.explanationSchema().schema()
            ._additionalProperties()["required"]
            ?.convert(object : TypeReference<List<String>>() {})
            ?: emptyList()

        assertThat(requiredFields).containsExactlyInAnyOrder("explanation", "citations")
    }

    @Test
    fun `openai 호환은 주어진 baseUrl 과 모델을 쓴다`() {
        val options = ChatClients.openAiCompatibleOptions("x/y", ChatClients.OPENROUTER_BASE_URL)

        assertThat(options.model).isEqualTo("x/y")
        assertThat(options.baseUrl).isEqualTo(ChatClients.OPENROUTER_BASE_URL)
    }
}
