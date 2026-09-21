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
}
