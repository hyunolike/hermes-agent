package com.hermes.llm

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
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

    // 블록 바디다 — 응답이 null 일 때 조기 return 이 필요한데, 식 바디(`= try { ... }`)
    // 에서는 Kotlin 2.2 가 그 return 을 금지한다(컴파일로 확인: "Returns are
    // prohibited in functions with expression body").
    override fun explain(systemText: String, userText: String): ProviderResult {
        return try {
            val response = chatClient
                .prompt(Prompt(listOf(SystemMessage(systemText), UserMessage(userText))))
                .call()
                .chatResponse()
                ?: return Failed("response was null")

            toProviderResult(response)
        } catch (e: Exception) {
            // 예외의 정체를 지우지 않는다 — 영구적 프로그래밍 오류가 소켓 타임아웃과
            // 구분이 안 되면, 호출자가 Failed 를 재시도할 때 전액을 들여 같은 버그를
            // 반복한다. (네트워크 호출 자체가 던지는 예외만 여기서 잡는다 — 응답을
            // ProviderResult 로 바꾸는 로직 자체의 예외는 toProviderResult 가 자체적으로
            // 감싼다.)
            log.warn("$name explain failed", e)
            Failed("${e::class.simpleName}: ${e.message}")
        }
    }

    /**
     * 실제 스트리밍. Reactor 는 여기 가둔다 — 포트는 콜백과 블로킹 반환만 안다.
     *
     * 거절 판정은 `isRefusal` 을 쓴다(아래 설명 참고). **마지막 조각만 보면 안 된다** —
     * 루프백으로 실측했다: Spring AI 의 `OpenAiChatModel.internalStream` 은 원본 SSE
     * 청크를 누적 병합하지 않고 청크마다 독립적으로 `ChatResponse` 를 만들어 내보낸다
     * (`OpenAiChatModel$ChunkMerger.mergeChoices` 가 새 델타의 content/refusal 을
     * 이전 것 위에 이어붙이지 않는 것까지 바이트코드로 확인). 그래서 OpenAI 호환
     * 거절은 `refusal` 필드를 실은 조각과 `finish_reason: "stop"` 을 실은 마지막(대개
     * 빈) 조각이 서로 다를 수 있다 — 마지막 조각만 보면 그 사이 조각의 `refusal` 을
     * 놓친다. 그래서 조각마다 `isRefusal` 을 확인해 하나라도 걸리면 거절로 닫는다.
     * 거절이면 content 가 비어 있어(OpenAI 는 `refusal` 필드로, 본문 content 는
     * 비운다) onChunk 가 불리지 않으므로, 게이트는 delta 없이 unavailable 로 닫는다.
     */
    override fun stream(systemText: String, userText: String, onChunk: (String) -> Unit): StreamEnd = try {
        var last: ChatResponse? = null
        var refused = false
        chatClient
            .prompt(Prompt(listOf(SystemMessage(systemText), UserMessage(userText))))
            .stream()
            .chatResponse()
            .doOnNext { response ->
                last = response
                val generation = response.result
                if (generation != null && isRefusal(generation)) refused = true
                val text = generation?.output?.text
                if (!text.isNullOrEmpty()) onChunk(text)
            }
            .blockLast()

        if (refused) {
            StreamRefused(category = null)
        } else {
            // 스트리밍에서는 프로바이더가 usage 를 안 줄 수 있다. 스트리밍 경로는 usage 를
            // 집계에 쓰지 않으므로 0 으로 둔다 — 하네스는 비스트리밍 explain() 으로 잰다.
            StreamCompleted(last?.let { usageOf(it) } ?: ProviderUsage(0, 0, 0, 0))
        }
    } catch (e: Exception) {
        log.warn("$name stream failed", e)
        StreamFailed("${e::class.simpleName}: ${e.message}")
    }

    // public 이다(companion 자체를 private 로 두지 않는다) — Task 6 리뷰가 요구한
    // "매핑은 손으로 조립한 ChatResponse 로 직접 테스트할 수 있어야 한다" 를 만족하려면
    // SpringAiResponseMappingTest 가 SpringAiExplanationProvider.toProviderResult 를
    // 실제 네트워크 없이 부를 수 있어야 한다.
    companion object {
        private const val REFUSAL = "refusal"
        private val MAPPER = ObjectMapper()

        /**
         * `ChatResponse` 하나를 받아 `ProviderResult` 하나를 내는 순수 함수. 입력 밖의
         * 어떤 것도 만지지 않는다(네트워크 호출도, `this.name`/`this.chatClient` 도) —
         * 그래서 실제 호출 없이, 손으로 조립한 `ChatResponse` 로 바로 테스트할 수 있다.
         *
         * `explain` 이 쥐고 있는 것은 네트워크 호출과 그 바깥 try/catch 뿐이다. 이
         * 함수 자신의 실패(JSON 파싱 등)는 여기서 끝까지 감싸 `Failed` 로 돌려준다 —
         * "malformed content 는 크래시가 아니라 Failed" 라는 요구가 직접 테스트
         * 가능해야 하기 때문이다(explain 의 바깥 catch 를 거치지 않고도).
         */
        fun toProviderResult(response: ChatResponse): ProviderResult {
            val generation = response.result ?: return Failed("response carried no generation")

            // 거절을 content 읽기 전에 가른다. 거절은 HTTP 200 에 빈 content 로 오므로
            // 본문을 무조건 읽는 코드는 여기서 깨진다. 판정은 `isRefusal` 로 뺐다 —
            // 그 함수의 설명 참고.
            if (isRefusal(generation)) {
                return Refused(category = null)
            }

            val text = generation.output?.text
            if (text.isNullOrBlank()) return Failed("response carried no structured content")

            // 구조화 출력(effort+schema) 이 강제하는 계약이라 text 는 Explanation 의
            // JSON 이다. OpenAiCompatibleExplanationProvider 와 같은 방식으로 판다 —
            // 어느 프로바이더가 뒤에 있든 같은 파싱 경로를 타야 비교가 정직하다.
            //
            // 파싱 실패는 여기서 잡아 Failed 로 접는다 — 예외의 정체(클래스명+메시지)는
            // 지우지 않는다.
            val parsed = try {
                MAPPER.readTree(text)
            } catch (e: Exception) {
                return Failed("${e::class.simpleName}: ${e.message}")
            }

            val explanationText = parsed.at("/explanation").asText()
            if (explanationText.isNullOrBlank()) return Failed("structured content had no explanation field")

            return Answered(
                explanation = Explanation(
                    explanation = explanationText,
                    citations = parsed.at("/citations").map { it.asText() },
                ),
                usage = usageOf(response),
            )
        }

        /**
         * 거절 판정. `explain()`(`toProviderResult`) 과 `stream()` 이 같은 로직을 쓴다.
         *
         * finishReason 만으로는 부족하다 — 두 프로바이더가 거절을 싣는 자리가 다르다.
         * javap 로 직접 확인했다(Fix round 1, Important 리뷰 대응):
         *
         * - Anthropic: `com.anthropic.models.messages.StopReason` 에 `REFUSAL` 상수가
         *   실존한다. `AnthropicChatModel` 이 `StopReason.toString()` 을 finishReason
         *   문자열로 그대로 싣는 것은 이미 확인돼 있었다(위 커밋 로그 참고) — 그래서
         *   finishReason == "refusal" 비교는 Anthropic 에서는 원래도 맞았다.
         * - OpenAI 호환: `com.openai.models.chat.completions.ChatCompletionChunk.Choice.
         *   FinishReason` 에는 STOP/LENGTH/TOOL_CALLS/CONTENT_FILTER/FUNCTION_CALL 뿐이고
         *   "refusal" 값 자체가 없다 — 실제 거절은 finish_reason="stop" 에 message 의
         *   별도 `refusal` 필드로 온다(스트리밍은 `delta.refusal`). `OpenAiChatModel.
         *   buildGeneration` 이 이 필드를 `AssistantMessage.metadata["refusal"]` 로
         *   옮겨 놓는 것을, 스트리밍 쪽은 `ChunkMerger.chunkToChatCompletion` 이
         *   `delta.refusal()` 을 병합해 같은 `buildGeneration` 을 타는 것까지 바이트코드로
         *   확인했다 — call() 과 stream() 이 같은 변환 경로를 공유한다.
         *
         * 그래서 finishReason == "refusal" 이 원래 맞았던 것은 Anthropic 뿐이고,
         * OpenAI 호환 경로에서는 이 검사가 한 번도 발동한 적이 없었다(REFUSAL 이 이
         * enum 에 없으므로) — 빈 content 가 그대로 "response carried no structured
         * content" 의 Failed 로 떨어졌다. 두 신호를 모두 보게 고쳤다.
         */
        private fun isRefusal(generation: Generation): Boolean {
            if (generation.metadata?.finishReason.equals(REFUSAL, ignoreCase = true)) return true
            val refusal = generation.output?.metadata?.get("refusal") as? String
            return !refusal.isNullOrBlank()
        }

        /**
         * `ChatResponse` 에서 usage 를 뽑는다. `explain()`(`toProviderResult`) 과
         * `stream()` 이 같은 로직을 쓴다 — 두 벌 두지 않는다.
         */
        private fun usageOf(response: ChatResponse): ProviderUsage {
            val usage = response.metadata.usage
            return ProviderUsage(
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
            )
        }
    }
}
