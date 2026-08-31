package com.hermes.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.StructuredMessageCreateParams
import com.anthropic.models.messages.TextBlockParam

class AnthropicExplanationProvider(private val client: AnthropicClient) : ExplanationProvider {

    override val name = "anthropic"

    override fun explain(systemText: String, factsJson: String): ProviderResult = try {
        val response = client.messages().create(buildParams(systemText, factsJson))

        // refusal 을 content 읽기 전에 가른다. 거절은 HTTP 200 에 빈 content 로
        // 오므로, content[0] 을 무조건 읽는 코드는 여기서 깨진다.
        if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
            Refused(response.stopDetails().map { it.category().orElse(null)?.asString() }.orElse(null))
        } else {
            val explanation = response.content()
                .firstNotNullOfOrNull { it.text().orElse(null)?.text() }
                ?: return Failed("response carried no structured content")

            Answered(
                explanation = explanation,
                usage = ProviderUsage(
                    cacheReadTokens = response.usage().cacheReadInputTokens().orElse(0L),
                    cacheCreationTokens = response.usage().cacheCreationInputTokens().orElse(0L),
                    inputTokens = response.usage().inputTokens(),
                    outputTokens = response.usage().outputTokens(),
                ),
            )
        }
    } catch (e: Exception) {
        Failed(e.message ?: e::class.simpleName ?: "unknown")
    }

    companion object {
        const val MODEL = "claude-opus-5"

        // 8192 가 아니다 — thinking 과 응답 텍스트를 합쳐 덮는 한도이고
        // Opus 5 는 thinking 이 기본 ON 이다.
        const val MAX_TOKENS = 16_000L

        fun buildParams(systemText: String, factsJson: String): StructuredMessageCreateParams<Explanation> =
            MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                // thinking 을 명시하지 않는다 — Opus 5 는 생략이 곧 adaptive 다.
                // temperature/top_p/top_k 도 쓰지 않는다 — 400 을 반환한다.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .systemOfTextBlockParams(
                    listOf(
                        TextBlockParam.builder()
                            .text(systemText)
                            .cacheControl(
                                CacheControlEphemeral.builder()
                                    .ttl(CacheControlEphemeral.Ttl.TTL_1H)
                                    .build(),
                            )
                            .build(),
                    ),
                )
                .addUserMessage(factsJson)
                .outputConfig(Explanation::class.java)
                .build()
    }
}
