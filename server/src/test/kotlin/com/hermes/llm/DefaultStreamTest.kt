package com.hermes.llm

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 스트리밍하지 않는 프로바이더(테스트 페이크 포함)도 stream() 계약을 지켜야 한다.
 * 기본 구현은 explain() 결과를 모델이 보냈을 법한 JSON 한 조각으로 낸다.
 */
class DefaultStreamTest {

    private class OnlyExplain(private val result: ProviderResult) : ExplanationProvider {
        override val name = "only-explain"
        var seen: Pair<String, String>? = null
        override fun explain(systemText: String, userText: String): ProviderResult {
            seen = systemText to userText
            return result
        }
    }

    private val usage = ProviderUsage(1, 2, 3, 4)

    @Test
    fun `답변은 인용이 먼저인 JSON 한 조각으로 나온다`() {
        val provider = OnlyExplain(Answered(Explanation("본문", listOf("a.md", "b.md")), usage))
        val chunks = mutableListOf<String>()

        val end = provider.stream("sys", "user") { chunks += it }

        assertThat(chunks).hasSize(1)
        val json = ObjectMapper().readTree(chunks.single())
        assertThat(json.fieldNames().asSequence().toList()).containsExactly("citations", "explanation")
        assertThat(json["citations"].map { it.asText() }).containsExactly("a.md", "b.md")
        assertThat(json["explanation"].asText()).isEqualTo("본문")
        assertThat(end).isEqualTo(StreamCompleted(usage))
    }

    @Test
    fun `같은 system 과 user 텍스트가 explain 으로 그대로 간다`() {
        val provider = OnlyExplain(Answered(Explanation("x", listOf("a.md")), usage))

        provider.stream("시스템", "사용자") {}

        assertThat(provider.seen).isEqualTo("시스템" to "사용자")
    }

    @Test
    fun `거절은 조각 없이 StreamRefused 로 끝난다`() {
        val chunks = mutableListOf<String>()

        val end = OnlyExplain(Refused("violence")).stream("s", "u") { chunks += it }

        assertThat(chunks).isEmpty()
        assertThat(end).isEqualTo(StreamRefused("violence"))
    }

    @Test
    fun `실패는 조각 없이 StreamFailed 로 끝나고 사유를 보존한다`() {
        val chunks = mutableListOf<String>()

        val end = OnlyExplain(Failed("IOException: reset")).stream("s", "u") { chunks += it }

        assertThat(chunks).isEmpty()
        assertThat(end).isEqualTo(StreamFailed("IOException: reset"))
    }
}
