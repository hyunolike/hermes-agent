package com.hermes.llm

import com.fasterxml.jackson.databind.ObjectMapper
import com.hermes.context.BundleLoader
import com.hermes.context.PromptAssembler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenRouterRequestShapeTest {

    private val mapper = ObjectMapper()
    private val systemText = PromptAssembler(BundleLoader.load()).systemText
    private val factsJson = """{"courseUuid":"3f6c2b18"}"""

    private fun body() = mapper.readTree(
        OpenRouterExplanationProvider.buildBody(systemText, factsJson, "nvidia/nemotron-nano-9b-v2:free"),
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
        val a = OpenRouterExplanationProvider.buildBody(systemText, factsJson, "m")
        val b = OpenRouterExplanationProvider.buildBody(systemText, factsJson, "m")

        assertThat(a).isEqualTo(b)
    }
}
