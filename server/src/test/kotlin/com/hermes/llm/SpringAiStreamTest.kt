package com.hermes.llm

import com.hermes.shared.config.LlmSelection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 스파이크(raw HTTP)가 보인 것을 Spring AI 경로에서 다시 증명한다: 스트리밍 요청도
 * response_format 을 싣고, 조각이 순서대로 onChunk 에 도착한다.
 */
class SpringAiStreamTest {

    /** openai-java 가 파싱할 수 있는 청크. id/object/created/model 은 SDK 가 요구한다. */
    private fun chunk(content: String, finish: String? = null): String {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
        val finishJson = if (finish == null) "null" else "\"$finish\""
        return """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o","choices":[{"index":0,"delta":{"content":"$escaped"},"finish_reason":$finishJson}]}""" + "\n\n"
    }

    private val sse = chunk("""{"citations":["concepts/a.md"]""") +
        chunk(""","explanation":"가나""") +
        chunk("""다"}""", finish = "stop") +
        "data: [DONE]\n\n"

    private fun provider(endpoint: CapturingEndpoint): ExplanationProvider =
        LlmSelection.provider("openai", "gpt-4o", { "sk-not-a-real-key" }, endpoint.baseUrl)

    @Test
    fun `스트리밍 요청도 stream 과 response_format 을 싣는다`() {
        CapturingEndpoint(CannedResponse(200, "text/event-stream", sse)).use { endpoint ->
            provider(endpoint).stream("sys", "user") {}
            val body = endpoint.capturedBody()

            assertThat(body["stream"].asBoolean()).isTrue()
            assertThat(body["response_format"]["type"].asText()).isEqualTo("json_schema")
            assertThat(body["response_format"]["json_schema"]["strict"].asBoolean()).isTrue()
        }
    }

    @Test
    fun `조각이 순서대로 도착하고 이어 붙이면 모델이 보낸 JSON 이다`() {
        CapturingEndpoint(CannedResponse(200, "text/event-stream", sse)).use { endpoint ->
            val chunks = mutableListOf<String>()

            val end = provider(endpoint).stream("sys", "user") { chunks += it }

            assertThat(chunks.joinToString("")).isEqualTo("""{"citations":["concepts/a.md"],"explanation":"가나다"}""")
            assertThat(chunks.size).describedAs("한 조각으로 뭉치지 않고 흘러야 한다").isGreaterThan(1)
            assertThat(end).isInstanceOf(StreamCompleted::class.java)
        }
    }

    @Test
    fun `엔드포인트가 죽으면 예외가 아니라 StreamFailed 로 끝난다`() {
        CapturingEndpoint().use { endpoint ->
            val end = provider(endpoint).stream("sys", "user") {}

            assertThat(end).isInstanceOf(StreamFailed::class.java)
        }
    }

    /**
     * Fix round 1, Important 리뷰 대응 — 실제 OpenAI 거절의 진짜 모양으로 만든 SSE.
     *
     * `finish_reason: "refusal"` 은 openai-java 의 `ChatCompletionChunk.Choice.
     * FinishReason` 에 아예 없는 값이다(STOP/LENGTH/TOOL_CALLS/CONTENT_FILTER/
     * FUNCTION_CALL 뿐 — javap 로 확인). 실제 거절은 `finish_reason: "stop"` 에 델타의
     * 별도 `refusal` 필드로 온다. 이 델타가 `id`/`object`/`created`/`model` 없이도
     * 파싱되는지는 이미 위 `chunk()` 로 검증됐으므로 여기서는 생략하지 않는다 — 실제
     * 거절 스트림의 형태를 그대로 흉내 낸다: 첫 청크가 `delta.refusal` 을 나르고,
     * 마지막 청크가 content 없이 `finish_reason: "stop"` 으로 닫는다.
     */
    private val refusalSse =
        """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o","choices":[{"index":0,"delta":{"refusal":"I can't help with that."},"finish_reason":null}]}""" +
            "\n\n" +
            """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" +
            "\n\n" +
            "data: [DONE]\n\n"

    @Test
    fun `진짜 OpenAI 거절 모양(델타의 refusal 필드, finish_reason=stop)도 StreamRefused 로 닫힌다`() {
        CapturingEndpoint(CannedResponse(200, "text/event-stream", refusalSse)).use { endpoint ->
            val chunks = mutableListOf<String>()

            val end = provider(endpoint).stream("sys", "user") { chunks += it }

            assertThat(chunks).describedAs("거절은 content 가 비어 onChunk 가 불리면 안 된다").isEmpty()
            assertThat(end).isInstanceOf(StreamRefused::class.java)
        }
    }
}
