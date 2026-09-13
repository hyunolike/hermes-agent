package com.hermes.llm

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt

/**
 * 포트 뒤의 단일 구현. 프로바이더별 차이는 주입된 `ChatClient` 가 들고 있고,
 * 여기서는 어느 프로바이더인지 알 필요가 없다.
 *
 * 포트를 남긴 이유는 바뀌지 않았다 — "같은 프롬프트와 같은 검증으로 비교한다"는
 * 보장이 프레임워크가 아니라 이 저장소 코드에 남아 있어야 한다.
 */
class SpringAiExplanationProvider(
    override val name: String,
    private val chatClient: ChatClient,
) : ExplanationProvider {

    private val log = LoggerFactory.getLogger(SpringAiExplanationProvider::class.java)

    // 블록 바디다 — 거절/결측 판단마다 조기 return 이 필요한데, 식 바디(`= try { ... }`)에서는
    // Kotlin 2.2 가 그 return 을 금지한다(컴파일로 확인: "Returns are prohibited in functions
    // with expression body").
    override fun explain(systemText: String, userText: String): ProviderResult {
        return try {
            val response = chatClient
                .prompt(Prompt(listOf(SystemMessage(systemText), UserMessage(userText))))
                .call()
                .chatResponse()
                ?: return Failed("response was null")

            val generation = response.result ?: return Failed("response carried no generation")

            // 거절을 content 읽기 전에 가른다. 거절은 HTTP 200 에 빈 content 로 오므로
            // 본문을 무조건 읽는 코드는 여기서 깨진다.
            //
            // 구 코드가 싣던 stopDetails.category 는 여기까지 오지 않는다 — Spring AI 는
            // StopReason.toString() 을 finishReason 문자열로만 노출한다(AnthropicChatModel
            // 을 javap 로 확인함: lambda$buildGenerations$17 이 stopReason.toString() 을
            // 그대로 finishReason 에 싣는다). 사유 해상도는 떨어지지만 Refused 와 Failed 를
            // 가르는 판단에는 이것으로 충분하다.
            if (generation.metadata?.finishReason.equals(REFUSAL, ignoreCase = true)) {
                return Refused(category = null)
            }

            val text = generation.output?.text
            if (text.isNullOrBlank()) return Failed("response carried no structured content")

            // 구조화 출력(effort+schema) 이 강제하는 계약이라 text 는 Explanation 의
            // JSON 이다. OpenAiCompatibleExplanationProvider 와 같은 방식으로 판다 —
            // 어느 프로바이더가 뒤에 있든 같은 파싱 경로를 타야 비교가 정직하다.
            val parsed = MAPPER.readTree(text)
            val explanationText = parsed.at("/explanation").asText()
            if (explanationText.isNullOrBlank()) return Failed("structured content had no explanation field")

            val usage = response.metadata.usage
            Answered(
                explanation = Explanation(
                    explanation = explanationText,
                    citations = parsed.at("/citations").map { it.asText() },
                ),
                usage = ProviderUsage(
                    // usage.nativeUsage 는 프로바이더마다 콘크리트 타입이 다르다
                    // (Anthropic 은 com.anthropic.models.messages.Usage — javap 로 확인).
                    // 그걸 직접 캐스팅하는 대신, spring-ai-model 의 최상위 Usage 인터페이스가
                    // 이미 노출하는 getCacheReadInputTokens()/getCacheWriteInputTokens() 를
                    // 쓴다 — AnthropicChatModel.getDefaultUsage(...) 가 nativeUsage 에서
                    // 캐시 토큰을 뽑아 DefaultUsage 의 이 필드에 채워 넣는 것까지 바이트코드로
                    // 확인했다. OpenAI 계열은 캐시 생성 토큰 개념이 없어 이 필드가 null 로
                    // 남고(OpenAiChatModel.getDefaultUsage 확인), 여기서 0L 로 떨어진다 —
                    // 캐스팅이 없으니 ClassCastException 도 날 수 없다.
                    cacheReadTokens = usage.cacheReadInputTokens ?: 0L,
                    cacheCreationTokens = usage.cacheWriteInputTokens ?: 0L,
                    inputTokens = (usage.promptTokens ?: 0).toLong(),
                    outputTokens = (usage.completionTokens ?: 0).toLong(),
                ),
            )
        } catch (e: Exception) {
            // 예외의 정체를 지우지 않는다 — 영구적 프로그래밍 오류가 소켓 타임아웃과
            // 구분이 안 되면, 호출자가 Failed 를 재시도할 때 전액을 들여 같은 버그를
            // 반복한다.
            log.warn("$name explain failed", e)
            Failed("${e::class.simpleName}: ${e.message}")
        }
    }

    private companion object {
        const val REFUSAL = "refusal"
        val MAPPER = ObjectMapper()
    }
}
