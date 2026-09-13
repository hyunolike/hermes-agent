package com.hermes.shared.config

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.hermes.llm.ChatClients
import com.hermes.llm.ExplanationProvider
import com.hermes.llm.OpenAiCompatibleExplanationProvider
import com.hermes.llm.SpringAiExplanationProvider
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.client.ChatClient

/**
 * 운영 서버의 프로바이더를 설정으로 고른다.
 *
 * 이전에는 `AnthropicExplanationProvider` 로 고정돼 있었다. 그런데 이 프로젝트가
 * 실제로 측정한 것은 전부 OpenAI 다 — 배포하면 한 번도 재본 적 없는 프로바이더가
 * 돌고, 하네스가 낸 "위반율 0%"는 그 서버에 대해 아무것도 말해 주지 않는다.
 * `EvalMain` 과 **같은 세 이름**을 쓰는 이유가 이것이다: 잰 것을 그대로 띄운다.
 *
 * 자격 증명은 `System.getenv` 대신 함수로 받는다 — 테스트가 프로세스 전역
 * 환경변수를 건드리지 않고도 두 상태를 모두 검증할 수 있어야 한다.
 *
 * 세 프로바이더 모두 `hermes.llm.model` 을 따른다. 이전에는 anthropic 만 이 인자를
 * 버리고 모델을 코드에 박아 두어, `docs/deploy.md` 가 약속한 `HERMES_LLM_MODEL` 이
 * 그 경로에서만 조용히 무시됐다.
 */
object LlmSelection {

    fun provider(name: String, model: String, env: (String) -> String?): ExplanationProvider =
        when (name) {
            // Anthropic SDK 는 키가 없어도 던지지 않고 인증되지 않은 클라이언트를
            // 만든다. 그 검사는 LlmCredentialHealthIndicator 가 맡는다.
            "anthropic" -> SpringAiExplanationProvider(
                name = "anthropic",
                chatClient = ChatClient.create(
                    AnthropicChatModel.builder()
                        .anthropicClient(AnthropicOkHttpClient.fromEnv())
                        .options(ChatClients.anthropicOptions(model))
                        .build(),
                ),
            )
            "openai" -> OpenAiCompatibleExplanationProvider.openAi(require(env, "OPENAI_API_KEY"), model)
            "openrouter" ->
                OpenAiCompatibleExplanationProvider.openRouter(require(env, "OPENROUTER_API_KEY"), model)
            else -> error(
                "unknown hermes.llm.provider: '$name' (expected anthropic, openai, or openrouter)",
            )
        }

    /**
     * 빈 값을 없는 값과 같이 다룬다. 빈 키는 프로바이더까지 가서 401 로 돌아오는데,
     * 그 401 은 "키가 틀렸다"와 "키를 안 넣었다"를 구분해 주지 않는다.
     */
    private fun require(env: (String) -> String?, name: String): String =
        env(name)?.takeIf { it.isNotBlank() }
            ?: error("$name is not set (or is empty) — the '$name' credential is required for this provider")
}
