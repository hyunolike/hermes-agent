package com.hermes.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.StructuredMessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import org.slf4j.LoggerFactory

class AnthropicExplanationProvider(private val client: AnthropicClient) : ExplanationProvider {

    private val log = LoggerFactory.getLogger(AnthropicExplanationProvider::class.java)

    override val name = "anthropic"

    override fun explain(systemText: String, userText: String): ProviderResult = try {
        val response = client.messages().create(buildParams(systemText, userText))

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
        // 예외의 정체를 지우지 않는다 — 스키마 유도 실패(IllegalArgumentException)나
        // NPE 같은 영구적 프로그래밍 오류가 소켓 타임아웃과 구분이 안 되면, 호출자가
        // Failed 를 재시도할 때 전액을 들여 같은 버그를 반복하게 된다.
        log.warn("anthropic explain failed", e)
        Failed("${e::class.simpleName}: ${e.message}")
    }

    companion object {
        const val MODEL = "claude-opus-5"

        // 8192 가 아니다 — thinking 과 응답 텍스트를 합쳐 덮는 한도이고
        // Opus 5 는 thinking 이 기본 ON 이다.
        const val MAX_TOKENS = 16_000L

        // Explanation 의 JSON 스키마를 SDK 가 직접 유도하게 한다 — 손으로 스키마를
        // 다시 쓰면 Explanation 데이터 클래스와 드리프트할 수 있다. 유도 자체를 하는
        // 함수(outputFormatFromClass)는 SDK 모듈 내부(internal)라 이 모듈에서 직접
        // 부를 수 없다(컴파일 에러로 확인함) — 그래서 공개 경로인
        // `.outputConfig(Class)` 를 최소 요청 한 번에 태워 유도된 포맷만 꺼내 쓴다.
        // Explanation 타입은 고정이므로 이 값은 요청마다 달라지지 않는다 — buildParams
        // 는 여전히 systemText/userText 두 인자만의 순수 함수다.
        private val derivedExplanationFormat: JsonOutputFormat by lazy {
            MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .addUserMessage("schema derivation only")
                .outputConfig(Explanation::class.java)
                .build()
                .rawParams
                .outputConfig()
                .orElseThrow()
                .format()
                .orElseThrow()
        }

        fun buildParams(systemText: String, userText: String): StructuredMessageCreateParams<Explanation> =
            MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                // thinking 을 명시하지 않는다 — Opus 5 는 생략이 곧 adaptive 다.
                // temperature/top_p/top_k 도 쓰지 않는다 — 400 을 반환한다.
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
                .addUserMessage(userText)
                // outputConfig(Class) 를 먼저 부른다 — StructuredMessageCreateParams<T> 의
                // outputType 을 채우는 유일한 경로다. 하지만 이 오버로드는 내부적으로
                // OutputConfig{format=...} (effort 없음) 을 새로 만들어
                // MessageCreateParams.Builder 에 곧바로 덮어써 버린다 — effort 는 들어갈
                // 자리가 없다. 그래서 effort 와 (미리 유도해 둔) 스키마를 모두 담은
                // OutputConfig 로 뒤이어 한 번 더 덮어쓴다. outputConfig(OutputConfig)
                // 오버로드에는 중복 호출 가드가 없어 조용히 마지막 값으로 대체된다 —
                // 이 순서가 effort 를 살리는 유일한 방법이다 (Task 6 리뷰에서 발견됨).
                .outputConfig(Explanation::class.java)
                .outputConfig(
                    OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(derivedExplanationFormat)
                        .build(),
                )
                .build()
    }
}
