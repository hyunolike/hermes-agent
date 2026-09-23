package com.hermes.llm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

/**
 * `SpringAiExplanationProvider.toProviderResult` 를 손으로 조립한 `ChatResponse` 로
 * 직접 테스트한다 — 네트워크도 키도 필요 없다.
 *
 * 이 파일이 있는 이유: `SpringAiRequestShapeTest` 는 `CapturingEndpoint` 가 항상 500 을
 * 돌려주므로 모든 호출이 `Failed` 로 끝난다. `Answered` 로 가는 경로 — JSON 을
 * `Explanation` 으로 파싱하고 캐시 읽기/생성/입력/출력 네 개의 토큰 수를 뽑는 일 —
 * 는 그 파일에서 단 한 번도 실행되지 않는다. 이 네 숫자가 바로 이 프로젝트가 1시간
 * 프롬프트 캐시 적중을 재는 방법이라, 조용히 틀려도 어떤 테스트도 빨개지지 않는
 * 상태를 두면 안 된다.
 */
class SpringAiResponseMappingTest {

    @Test
    fun `Answered 매핑은 파싱된 Explanation 과 네 토큰 수를 필드별로 정확히 나른다`() {
        val text = """{"explanation":"loop replaces the straight climb because gradient exceeds the limit","citations":["seg-12","seg-47"]}"""
        val generation = Generation(
            AssistantMessage(text),
            ChatGenerationMetadata.builder().finishReason("end_turn").build(),
        )
        // 네 값이 서로 달라야 한다 — 필드가 바뀌어치기(swap)되거나 복붙되면 이 중
        // 어느 하나는 반드시 틀린 값으로 드러난다. 1/1/1/1 이나 0 들이면 그 실수를
        // 가려 버린다.
        val usage = DefaultUsage(111, 222, null, null, 333L, 444L)
        val response = ChatResponse(listOf(generation), ChatResponseMetadata.builder().usage(usage).build())

        val result = SpringAiExplanationProvider.toProviderResult(response)

        assertThat(result).isInstanceOf(Answered::class.java)
        val answered = result as Answered
        assertThat(answered.explanation.explanation)
            .isEqualTo("loop replaces the straight climb because gradient exceeds the limit")
        assertThat(answered.explanation.citations).containsExactly("seg-12", "seg-47")
        assertThat(answered.usage.inputTokens).isEqualTo(111L)
        assertThat(answered.usage.outputTokens).isEqualTo(222L)
        assertThat(answered.usage.cacheReadTokens).isEqualTo(333L)
        assertThat(answered.usage.cacheCreationTokens).isEqualTo(444L)
    }

    @Test
    fun `거절은 content 를 읽지 않고 Refused 로 갈린다`() {
        // content 가 유효한 JSON 이 아니다 — finishReason 판단보다 먼저 content 를
        // 읽는 코드였다면 파싱이 터져 Failed 가 됐을 것이다. Refused 가 나온다는
        // 것 자체가 "읽지 않았다"는 증거다.
        val generation = Generation(
            AssistantMessage("not-json-and-should-never-be-parsed"),
            ChatGenerationMetadata.builder().finishReason("refusal").build(),
        )
        val response = ChatResponse(listOf(generation))

        val result = SpringAiExplanationProvider.toProviderResult(response)

        assertThat(result).isEqualTo(Refused(category = null))
    }

    @Test
    fun `OpenAI 거절 모양(finishReason=stop, metadata의 refusal 필드)도 Refused 로 갈린다`() {
        // Fix round 1 조사 결과: 실제 OpenAI 거절은 finish_reason 이 "stop" 으로
        // 남고(SDK 의 FinishReason enum 에 "refusal" 값 자체가 없다), 거절 텍스트는
        // content 가 아니라 AssistantMessage.metadata["refusal"] 로 온다(Spring AI
        // 의 OpenAiChatModel.buildGeneration 이 message.refusal() 을 그 키로 옮겨
        // 놓는 것을 바이트코드로 확인 — SpringAiExplanationProvider.isRefusal 의
        // 주석 참고). finishReason 만 보던 구코드였다면 이 케이스는 Refused 가
        // 아니라 "response carried no structured content" 의 Failed 로 잘못
        // 떨어졌다 — 위 `거절은 content 를 읽지 않고 Refused 로 갈린다` 테스트는
        // Anthropic 모양(finishReason="refusal")만 고정하므로 이 OpenAI 모양은
        // 따로 고정해야 한다.
        val assistantMessage = AssistantMessage.builder()
            .content("")
            .properties(mapOf("refusal" to "I can't help with that."))
            .build()
        val generation = Generation(
            assistantMessage,
            ChatGenerationMetadata.builder().finishReason("stop").build(),
        )
        val response = ChatResponse(listOf(generation))

        val result = SpringAiExplanationProvider.toProviderResult(response)

        assertThat(result).isEqualTo(Refused(category = null))
    }

    @Test
    fun `모델 텍스트가 Explanation JSON 이 아니면 크래시 대신 Failed 로 끝나고 예외 정체가 남는다`() {
        val generation = Generation(
            AssistantMessage("this is not json at all"),
            ChatGenerationMetadata.builder().finishReason("end_turn").build(),
        )
        val response = ChatResponse(listOf(generation))

        val result = SpringAiExplanationProvider.toProviderResult(response)

        assertThat(result).isInstanceOf(Failed::class.java)
        val failed = result as Failed
        // 예외의 정체(클래스명)를 지우지 않는다 — 영구적 파싱 버그가 소켓 타임아웃과
        // 구분이 안 되면 재시도가 전액을 들여 같은 실수를 반복한다.
        assertThat(failed.reason).containsIgnoringCase("json")
    }
}
