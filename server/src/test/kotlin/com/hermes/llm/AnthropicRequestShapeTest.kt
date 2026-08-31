package com.hermes.llm

import com.hermes.context.BundleLoader
import com.hermes.context.PromptAssembler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AnthropicRequestShapeTest {

    private val systemText = PromptAssembler(BundleLoader.load()).systemText
    private val factsJson = """{"courseUuid":"3f6c2b18-9a4d-4c77-8b21-5e0f7c9d1a44"}"""

    @Test
    fun `번들은 system 블록에 1시간 캐시 분기점과 함께 들어간다`() {
        val params = AnthropicExplanationProvider.buildParams(systemText, factsJson)
        val body = params.view()

        assertThat(body.system.single().text).isEqualTo(systemText)
        assertThat(body.system.single().cacheTtl).isEqualTo("1h")
    }

    @Test
    fun `매 요청 달라지는 사실은 캐시 분기점 뒤 user 턴에 있다`() {
        val body = AnthropicExplanationProvider.buildParams(systemText, factsJson).view()

        assertThat(body.userText).isEqualTo(factsJson)
    }

    @Test
    fun `모델과 토큰 한도가 스펙과 일치한다`() {
        val body = AnthropicExplanationProvider.buildParams(systemText, factsJson).view()

        assertThat(body.model).isEqualTo("claude-opus-5")
        // 8192 가 아니다 — max_tokens 는 thinking 과 응답을 합쳐 덮고,
        // Opus 5 는 thinking 이 기본 ON 이라 8192 는 잘릴 위험이 있다.
        assertThat(body.maxTokens).isEqualTo(16000L)
    }

    @Test
    fun `같은 입력이면 system 문자열이 바이트 단위로 같다`() {
        val a = AnthropicExplanationProvider.buildParams(systemText, factsJson).view()
        val b = AnthropicExplanationProvider.buildParams(systemText, factsJson).view()

        assertThat(a.system.single().text).isEqualTo(b.system.single().text)
    }
}
