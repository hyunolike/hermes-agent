package com.hermes.llm

import com.hermes.context.BundleLoader
import com.hermes.context.PromptAssembler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.client.ChatClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClientAsync
import org.springframework.ai.openai.OpenAiChatModel

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

    @Test
    fun `openai 호환 요청은 baseUrl 뒤에 chat completions 만 붙인다`() {
        CapturingEndpoint().use { endpoint ->
            // baseUrl 자체가 /v1 을 지녀야 한다 — SDK 는 그 뒤에 /chat/completions 만
            // 얹는다(ChatClients.OPENAI_BASE_URL 의 주석 참고, openai-java-core 의
            // OpenAiSetup.OPENAI_URL 이 이미 "https://api.openai.com/v1" 임을 javap 로
            // 확인). 이 테스트가 그 SDK 쪽 절반을 고정한다 — 루프백 baseUrl 뒤에 /v1 을
            // 직접 붙여서, 실제 나가는 경로가 baseUrl + "/chat/completions" 인지 본다.
            val baseUrl = "${endpoint.baseUrl}/v1"
            val model = OpenAiChatModel.builder()
                .openAiClient(
                    OpenAIOkHttpClient.builder()
                        .apiKey("sk-not-a-real-key")
                        .baseUrl(baseUrl)
                        .build(),
                )
                // openAiClientAsync 없이는 .build() 가 IllegalStateException("At least
                // one credential source must be specified")으로 죽는다 — 빌더 모양만
                // 보면 안 보이는 함정이다. .openAiClient(...)(sync)만 주면 sync 필드는
                // 우리가 준 걸 그대로 쓰지만(javap 로 확인: Objects.requireNonNullElseGet
                // 이 null 이 아니면 supplier 를 안 부른다), async 필드는 비어 있으면
                // OpenAiSetup.setupAsyncClient(...)로 기본 클라이언트를 새로 조립하려
                // 하고 그 조립이 OpenAiChatOptions.getApiKey()를 읽는다.
                // ChatClients.openAiCompatibleOptions 는 순수 함수라 apiKey 를 모른다 —
                // 그래서 .call() 만 쓰는 동기 경로여도 빌드 시점에 async 클라이언트가
                // 필요하다(LlmSelection.springAiOpenAiCompatible 에서 실측 확인).
                .openAiClientAsync(
                    OpenAIOkHttpClientAsync.builder()
                        .apiKey("sk-not-a-real-key")
                        .baseUrl(baseUrl)
                        .build(),
                )
                .options(ChatClients.openAiCompatibleOptions("gpt-4o", baseUrl))
                .build()

            SpringAiExplanationProvider("openai", ChatClient.create(model))
                .explain(systemText, factsJson)

            // baseUrl 이 이미 /v1 로 끝나므로, SDK 가 그 뒤에 무엇을 붙이는지만 본다.
            assertThat(endpoint.capturedPath()).isEqualTo("/v1/chat/completions")
            val body = endpoint.capturedBody()
            assertThat(body["model"].asText()).isEqualTo("gpt-4o")
            // maxTokens 는 이 테스트 말고는 어디서도 안 걸린다.
            assertThat(body["max_tokens"].asInt()).isEqualTo(16000)
        }
    }

    @Test
    fun `OPENAI_BASE_URL 과 OPENROUTER_BASE_URL 은 이미 v1 을 지닌다`() {
        // 위 테스트는 SDK 쪽 절반("baseUrl 뒤에 chat completions 만 붙는다")만
        // 고정한다. 이 상수 자체가 /v1 을 안 지니면(예: 예전처럼
        // "https://api.openai.com") 그 테스트는 여전히 초록일 수 있다 — 루프백
        // baseUrl 에 /v1 을 직접 붙였기 때문이다. 그래서 상수 쪽 절반은 따로
        // 고정해야 한다. 둘을 합쳐야 "실제 요청이 .../v1/chat/completions 에
        // 떨어진다"가 증명된다.
        assertThat(ChatClients.OPENAI_BASE_URL).isEqualTo("https://api.openai.com/v1")
        assertThat(ChatClients.OPENROUTER_BASE_URL).isEqualTo("https://openrouter.ai/api/v1")
    }
}
