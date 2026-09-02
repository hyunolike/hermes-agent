package com.hermes.shared.config

import com.hermes.llm.AnthropicExplanationProvider
import com.hermes.llm.OpenAiCompatibleExplanationProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 운영 서버가 어느 프로바이더로 도는가.
 *
 * 지금까지 측정은 전부 OpenAI 로 했는데 서버는 Anthropic 으로 고정돼 있었다 —
 * 배포하면 **한 번도 재본 적 없는 프로바이더**가 도는 셈이고, 하네스가 센 위반율
 * 0% 는 그 서버에 대해 아무 말도 하지 않는다. 하네스와 운영이 같은 프로바이더를
 * 고를 수 있어야 그 숫자가 서버의 숫자가 된다.
 */
class LlmSelectionTest {

    @Test
    fun `기본값은 anthropic 이다`() {
        val provider = LlmSelection.provider("anthropic", "claude-opus-5") { "key" }

        assertThat(provider).isInstanceOf(AnthropicExplanationProvider::class.java)
    }

    @Test
    fun `openai 를 고를 수 있다`() {
        val provider = LlmSelection.provider("openai", "gpt-4o") { "key" }

        assertThat(provider).isInstanceOf(OpenAiCompatibleExplanationProvider::class.java)
        assertThat(provider.name).isEqualTo("openai")
    }

    @Test
    fun `openrouter 를 고를 수 있다`() {
        assertThat(LlmSelection.provider("openrouter", "x/y") { "key" }.name).isEqualTo("openrouter")
    }

    @Test
    fun `모르는 이름은 기동을 멈춘다`() {
        // 조용히 기본값으로 떨어지면, 오타 하나가 "설정한 줄 알았던 프로바이더"와
        // "실제로 도는 프로바이더"를 갈라놓는다. 그 차이는 요금 고지서에서야 보인다.
        assertThatThrownBy { LlmSelection.provider("gpt5", "m") { "key" } }
            .hasMessageContaining("gpt5")
    }

    @Test
    fun `키가 비면 기동을 멈춘다`() {
        // 빈 키는 그대로 프로바이더까지 가서 401 로 돌아오고, 그 401 은 "키가
        // 틀렸다"와 "키를 안 넣었다"를 구분해 주지 않는다. 하네스에서 이미 한 번
        // 겪은 혼동이라 서버에서 되풀이하지 않는다.
        assertThatThrownBy { LlmSelection.provider("openai", "gpt-4o") { "" } }
            .hasMessageContaining("OPENAI_API_KEY")
    }
}
