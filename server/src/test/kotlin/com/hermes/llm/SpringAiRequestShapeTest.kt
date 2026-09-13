package com.hermes.llm

import com.hermes.context.BundleLoader
import com.hermes.context.PromptAssembler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.client.ChatClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient

/**
 * 실제로 나가는 요청 본문을 검사한다.
 *
 * 구 `AnthropicRequestShapeTest` 가 `buildParams` 라는 순수 함수의 반환값을 보던
 * 자리다. Spring AI 에는 그런 함수가 없어 요청이 모델 안에서 조립되므로, 나가는
 * 바이트를 직접 본다. 이쪽이 더 강하다 — 프레임워크가 무엇을 덧붙이거나 지워도
 * 여기서 잡힌다.
 */
class SpringAiRequestShapeTest {

    private val systemText = PromptAssembler(BundleLoader.load()).systemText
    private val factsJson = """{"courseUuid":"3f6c2b18-9a4d-4c77-8b21-5e0f7c9d1a44"}"""

    private fun provider(baseUrl: String): SpringAiExplanationProvider {
        val client = AnthropicOkHttpClient.builder()
            .apiKey("sk-ant-not-a-real-key")
            .baseUrl(baseUrl)
            .maxRetries(0)
            .build()
        val model = AnthropicChatModel.builder()
            .anthropicClient(client)
            .options(ChatClients.anthropicOptions("claude-opus-5"))
            .build()
        return SpringAiExplanationProvider("anthropic", ChatClient.create(model))
    }

    @Test
    fun `번들은 system 블록에 1시간 캐시 분기점과 함께 들어간다`() {
        CapturingEndpoint().use { endpoint ->
            provider(endpoint.baseUrl).explain(systemText, factsJson)
            val body = endpoint.capturedBody()

            assertThat(body["system"]).hasSize(1)
            assertThat(body["system"][0]["text"].asText()).isEqualTo(systemText)
            assertThat(body["system"][0]["cache_control"]["ttl"].asText()).isEqualTo("1h")
        }
    }

    @Test
    fun `매 요청 달라지는 사실은 캐시 분기점 뒤 user 턴에 있다`() {
        CapturingEndpoint().use { endpoint ->
            provider(endpoint.baseUrl).explain(systemText, factsJson)
            val body = endpoint.capturedBody()

            assertThat(body["messages"]).hasSize(1)
            assertThat(body["messages"][0]["role"].asText()).isEqualTo("user")
            assertThat(body["messages"][0]["content"].asText()).isEqualTo(factsJson)
        }
    }

    @Test
    fun `모델과 토큰 한도와 effort 가 스펙과 일치한다`() {
        CapturingEndpoint().use { endpoint ->
            provider(endpoint.baseUrl).explain(systemText, factsJson)
            val body = endpoint.capturedBody()

            assertThat(body["model"].asText()).isEqualTo("claude-opus-5")
            assertThat(body["max_tokens"].asInt()).isEqualTo(16000)
            assertThat(body["output_config"]["effort"].asText()).isEqualTo("low")
        }
    }

    @Test
    fun `엔드포인트가 죽어도 예외가 아니라 Failed 로 끝난다`() {
        CapturingEndpoint().use { endpoint ->
            val result = provider(endpoint.baseUrl).explain(systemText, factsJson)

            assertThat(result).isInstanceOf(Failed::class.java)
        }
    }
}
