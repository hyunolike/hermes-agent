package com.hermes.llm

import com.fasterxml.jackson.databind.ObjectMapper
import com.hermes.context.BundleLoader
import com.hermes.context.PromptAssembler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

class OpenAiCompatibleRequestShapeTest {

    private val mapper = ObjectMapper()
    private val systemText = PromptAssembler(BundleLoader.load()).systemText
    private val factsJson = """{"courseUuid":"3f6c2b18"}"""

    private fun body() = mapper.readTree(
        OpenAiCompatibleExplanationProvider.buildBody(systemText, factsJson, "nvidia/nemotron-nano-9b-v2:free"),
    )

    @Test
    fun `스키마를 tool_choice 로 강제한다`() {
        // 무료 Nemotron 은 response_format 을 지원하지 않는다. tool_choice 가
        // 함수를 고정하지 않으면 모델은 산문으로 답할 자유를 얻고, 계약은
        // 아무 에러 없이 사라진다.
        assertThat(body().at("/tool_choice/function/name").asText()).isEqualTo("submit_explanation")
    }

    @Test
    fun `tool_choice 가 가리키는 이름은 선언된 도구 이름과 같다`() {
        // 위 테스트는 tool_choice 이름을 리터럴과만 비교한다 — 선언된 도구 이름만
        // 바뀌어도(리네임 등) 그 테스트는 여전히 통과하면서 tool_choice 는 존재하지
        // 않는 함수를 가리키게 된다. 두 경로를 서로 비교해야 그 드리프트가 잡힌다.
        val response = body()

        assertThat(response.at("/tool_choice/function/name").asText())
            .isEqualTo(response.at("/tools/0/function/name").asText())
    }

    @Test
    fun `스키마가 두 필드를 모두 요구한다`() {
        val required = body().at("/tools/0/function/parameters/required")
            .map { it.asText() }.sorted()

        assertThat(required).containsExactly("citations", "explanation")
    }

    @Test
    fun `번들과 사실이 Anthropic 경로와 같은 내용이다`() {
        // 두 프로바이더에 다른 프롬프트를 주면 비교는 모델이 아니라 프롬프트를 잰다.
        assertThat(body().at("/messages/0/content").asText()).isEqualTo(systemText)
        assertThat(body().at("/messages/1/content").asText()).isEqualTo(factsJson)
    }

    @Test
    fun `같은 입력이면 본문이 바이트 단위로 같다`() {
        val a = OpenAiCompatibleExplanationProvider.buildBody(systemText, factsJson, "m")
        val b = OpenAiCompatibleExplanationProvider.buildBody(systemText, factsJson, "m")

        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `요청이 주어진 엔드포인트로 나가고 bearer 자격 증명을 싣는다`() {
        // 엔드포인트를 설정 가능하게 만들면서 그 값이 실제로 쓰이는지 확인할
        // 방법이 없으면 설정만 늘고 보장은 늘지 않는다.
        val endpoint = URI.create("https://example.invalid/v1/chat/completions")

        val request = OpenAiCompatibleExplanationProvider.buildRequest(
            endpoint, "sk-test-key", systemText, factsJson, "m",
        )

        assertThat(request.uri()).isEqualTo(endpoint)
        assertThat(request.headers().firstValue("Authorization")).hasValue("Bearer sk-test-key")
        assertThat(request.method()).isEqualTo("POST")
    }

    @Test
    fun `두 팩토리가 서로 다른 엔드포인트를 쓴다`() {
        // 같은 어댑터로 둘을 덮기 때문에, 엔드포인트가 뒤섞이면 "openai 로 쟀다"는
        // 실행이 사실은 openrouter 를 잰 것일 수 있고 아무것도 그것을 알려주지 않는다.
        assertThat(OpenAiCompatibleExplanationProvider.OPENROUTER_ENDPOINT.host)
            .isEqualTo("openrouter.ai")
        assertThat(OpenAiCompatibleExplanationProvider.OPENAI_ENDPOINT.host)
            .isEqualTo("api.openai.com")
        assertThat(OpenAiCompatibleExplanationProvider.OPENROUTER_ENDPOINT)
            .isNotEqualTo(OpenAiCompatibleExplanationProvider.OPENAI_ENDPOINT)
    }

    @Test
    fun `각 팩토리가 자기 엔드포인트와 이름을 실제로 배선한다`() {
        // 상수만 비교하는 테스트는 팩토리가 엉뚱한 상수를 넘겨도 통과한다.
        // 그러면 "openai 로 쟀다"는 실행이 사실은 openrouter 를 잰 것일 수 있고,
        // 그 수치로 프로바이더를 고르게 된다. 이름도 함께 본다 — 평가 리포트가
        // name 으로 프로바이더를 적으므로 둘이 같으면 결과를 구분할 수 없다.
        val router = OpenAiCompatibleExplanationProvider.openRouter("k", "m")
        val openai = OpenAiCompatibleExplanationProvider.openAi("k", "m")

        assertThat(router.name).isEqualTo("openrouter")
        assertThat(router.endpoint).isEqualTo(OpenAiCompatibleExplanationProvider.OPENROUTER_ENDPOINT)

        assertThat(openai.name).isEqualTo("openai")
        assertThat(openai.endpoint).isEqualTo(OpenAiCompatibleExplanationProvider.OPENAI_ENDPOINT)
    }
}
