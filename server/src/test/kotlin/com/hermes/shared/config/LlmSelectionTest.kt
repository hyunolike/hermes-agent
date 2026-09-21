package com.hermes.shared.config

import com.hermes.llm.CapturingEndpoint
import com.hermes.llm.ChatClients
import com.hermes.llm.SpringAiExplanationProvider
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
    fun `anthropic 은 Spring AI 로 돈다`() {
        val provider = LlmSelection.provider("anthropic", "claude-opus-5", { "key" })

        assertThat(provider).isInstanceOf(SpringAiExplanationProvider::class.java)
        assertThat(provider.name).isEqualTo("anthropic")
    }

    @Test
    fun `anthropic 도 설정한 모델을 따른다`() {
        // 구 코드는 이 인자를 버리고 claude-opus-5 를 박아 두었다. docs/deploy.md 는
        // HERMES_LLM_MODEL 이 "프로바이더에 맞는 모델 이름"이라고 약속하므로,
        // 문서가 약속한 것을 코드가 지키지 않던 상태였다.
        val options = ChatClients.anthropicOptions("claude-sonnet-5")

        assertThat(options.model).isEqualTo("claude-sonnet-5")
    }

    @Test
    fun `LlmSelection 이 고른 anthropic 프로바이더가 설정한 모델을 실제 요청에 싣는다`() {
        // 위 테스트는 ChatClients.anthropicOptions 하나만 본다 — LlmSelection 이 그
        // 함수에 model 을 제대로 넘기는지는 검증하지 않는다. LlmSelection.provider(...)
        // 의 "anthropic" 분기가 model 대신 다른 리터럴을 박아 넣어도 그 테스트는
        // 여전히 초록으로 남는다. 여기서는 LlmSelection.provider 가 실제로 조립해
        // 내보내는 프로바이더를 통해 루프백 엔드포인트로 나가는 바이트를 직접 본다 —
        // 설정에서 요청까지 전 구간을 잇는 유일한 테스트다.
        CapturingEndpoint().use { endpoint ->
            val provider = LlmSelection.provider("anthropic", "claude-sonnet-5", { "key" }, endpoint.baseUrl)

            provider.explain("system", "user")

            assertThat(endpoint.capturedBody()["model"].asText()).isEqualTo("claude-sonnet-5")
        }
    }

    @Test
    fun `LlmSelection 이 고른 openai 프로바이더가 v1 chat completions 로 스키마 강제 요청을 보낸다`() {
        // anthropic 쪽 바로 위 테스트와 짝을 이룬다. 그 테스트가 없었을 때 리뷰어가
        // ChatClients.OPENAI_BASE_URL 을 anthropicClient 에 잘못 흘려도 그린으로
        // 남을 수 있었던 것처럼, openai/openrouter 분기도 SpringAiRequestShapeTest
        // 만으로는 안 잡힌다 — 그 테스트는 LlmSelection 을 거치지 않고 클라이언트를
        // 손으로 다시 조립하기 때문이다(openAiProvider 헬퍼). 실제로 리뷰어가
        // LlmSelection.kt 의 "openai" 분기에서
        // `baseUrl = ChatClients.OPENAI_BASE_URL` 을
        // `baseUrl = ChatClients.OPENAI_BASE_URL.removeSuffix("/v1")` 로 뮤테이션해도
        // `./gradlew build` 가 그대로 통과했다 — 상수와 SDK 쪽 절반은 각각
        // 고정돼 있었지만 그 둘을 잇는 배선 자체는 아무것도 고정하지 않았기
        // 때문이다. 여기서는 LlmSelection.provider(...) 를 직접 통해 나가는
        // 바이트를 본다 — 설정에서 요청까지 전 구간을 openai 쪽에서도 잇는다.
        CapturingEndpoint().use { endpoint ->
            // baseUrl 자체가 /v1 을 지녀야 한다 — SDK 는 그 뒤에 /chat/completions 만
            // 얹는다(ChatClients.OPENAI_BASE_URL 의 주석, SpringAiRequestShapeTest 가
            // 이미 확인).
            val baseUrl = "${endpoint.baseUrl}/v1"
            val provider = LlmSelection.provider("openai", "gpt-4o", { "key" }, baseUrl)

            provider.explain("system", "user")

            assertThat(endpoint.capturedPath()).isEqualTo("/v1/chat/completions")
            val body = endpoint.capturedBody()
            assertThat(body["model"].asText()).isEqualTo("gpt-4o")
            // response_format 자체가 여기 있어야 한다 — LlmSelection 이 실제로
            // ChatClients.openAiCompatibleOptions 를 거쳐 조립했다는 뜻이다.
            assertThat(body["response_format"]["type"].asText()).isEqualTo("json_schema")
        }
    }

    @Test
    fun `LlmSelection 이 고른 openrouter 프로바이더가 v1 chat completions 로 지정한 모델을 싣는다`() {
        // openai 쪽 바로 위 테스트의 짝이다. 지금까지 "openrouter" 분기는
        // `openrouter 를 고를 수 있다`/`openrouter 도 Spring AI 로 돈다` 처럼
        // provider.name 만 확인했지, LlmSelection.provider(...) 를 통해 실제로
        // 나가는 바이트는 아무것도 본 적이 없다 — 리뷰어가 이 분기를
        // OPENAI_BASE_URL 로 바꿔도 스위트가 그대로 초록이었다. 여기서는
        // openai 와 같은 방식으로 루프백 엔드포인트로 나가는 경로와 모델을
        // 직접 본다.
        CapturingEndpoint().use { endpoint ->
            val baseUrl = "${endpoint.baseUrl}/v1"
            val provider = LlmSelection.provider("openrouter", "x/y", { "key" }, baseUrl)

            provider.explain("system", "user")

            assertThat(endpoint.capturedPath()).isEqualTo("/v1/chat/completions")
            val body = endpoint.capturedBody()
            assertThat(body["model"].asText()).isEqualTo("x/y")
        }
    }

    @Test
    fun `기본 baseUrl 은 openai 와 openrouter 상수를 각각 가리킨다`() {
        // 위 e2e 캡처 테스트 두 개(anthropic/openai/openrouter 전부)는 baseUrlOverride 를
        // 항상 채워서 LlmSelection.provider(...) 를 부른다 — 루프백 엔드포인트로
        // 나가는 바이트를 봐야 하니 override 없이는 테스트를 쓸 수가 없다. 그런데
        // `baseUrlOverride ?: defaultBaseUrl(name)` 에서 override 가 항상 채워져
        // 있으면 `?:` 가 항상 override 로 단락(short-circuit)되어 `defaultBaseUrl(...)`
        // 자체는 그 테스트들 안에서 한 번도 평가되지 않는다 — 실제로 리뷰어가
        // openrouter 분기의 상수를 OPENAI_BASE_URL 로 바꿔도 기존 e2e 테스트가
        // 전부 그린으로 남았다. 여기서는 override 를 아예 주지 않고
        // `defaultBaseUrl(...)` 을 직접 불러, "override 가 없을 때 실제로 어느
        // 상수가 선택되는가"라는 물음 자체를 테스트 대상으로 만든다.
        assertThat(LlmSelection.defaultBaseUrl("openai")).isEqualTo(ChatClients.OPENAI_BASE_URL)
        assertThat(LlmSelection.defaultBaseUrl("openrouter")).isEqualTo(ChatClients.OPENROUTER_BASE_URL)
    }

    @Test
    fun `기본 baseUrl 은 openai openrouter 가 아닌 이름에는 조용히 떨어지지 않는다`() {
        // provider(...) 의 when(name) 은 "openai"/"openrouter" 일 때만
        // defaultBaseUrl(...) 을 부른다 — anthropic 은 fromEnv() 를 쓰고, 그 외
        // 이름은 provider(...) 자체가 이미 error() 로 죽는다. 그래서 이 함수가
        // 다른 이름으로 불릴 일은 지금 없다. 하지만 defaultBaseUrl 을 독립적으로
        // 테스트할 수 있게 뽑아낸 이상, "있어선 안 되는 호출"을 조용히 아무
        // URL 로 떨어뜨리는 대신 즉시 죽는 쪽을 택했다 — 나중에 세 번째 분기가
        // 실수로 이 함수를 재사용해도 잘못된 기본값을 조용히 받는 대신 바로
        // 드러난다.
        assertThatThrownBy { LlmSelection.defaultBaseUrl("anthropic") }
            .hasMessageContaining("anthropic")
    }

    @Test
    fun `openai 도 Spring AI 로 돈다`() {
        val provider = LlmSelection.provider("openai", "gpt-4o", { "key" })

        assertThat(provider).isInstanceOf(SpringAiExplanationProvider::class.java)
        assertThat(provider.name).isEqualTo("openai")
    }

    @Test
    fun `openrouter 도 Spring AI 로 돈다`() {
        val provider = LlmSelection.provider("openrouter", "x/y", { "key" })

        assertThat(provider).isInstanceOf(SpringAiExplanationProvider::class.java)
        assertThat(provider.name).isEqualTo("openrouter")
    }

    @Test
    fun `openrouter 를 고를 수 있다`() {
        assertThat(LlmSelection.provider("openrouter", "x/y", { "key" }).name).isEqualTo("openrouter")
    }

    @Test
    fun `모르는 이름은 기동을 멈춘다`() {
        // 조용히 기본값으로 떨어지면, 오타 하나가 "설정한 줄 알았던 프로바이더"와
        // "실제로 도는 프로바이더"를 갈라놓는다. 그 차이는 요금 고지서에서야 보인다.
        assertThatThrownBy { LlmSelection.provider("gpt5", "m", { "key" }) }
            .hasMessageContaining("gpt5")
    }

    @Test
    fun `키가 비면 기동을 멈춘다`() {
        // 빈 키는 그대로 프로바이더까지 가서 401 로 돌아오고, 그 401 은 "키가
        // 틀렸다"와 "키를 안 넣었다"를 구분해 주지 않는다. 하네스에서 이미 한 번
        // 겪은 혼동이라 서버에서 되풀이하지 않는다.
        assertThatThrownBy { LlmSelection.provider("openai", "gpt-4o", { "" }) }
            .hasMessageContaining("OPENAI_API_KEY")
    }

    @Test
    fun `세 이름 모두 같은 함수 하나로 만들어진다`() {
        val names = listOf("anthropic", "openai", "openrouter")

        val made = names.map { LlmSelection.provider(it, "some-model", { "key" }).name }

        assertThat(made).isEqualTo(names)
    }
}
