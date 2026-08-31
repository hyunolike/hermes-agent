package com.hermes.llm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 포트가 세 결과를 모두 표현할 수 있는지 확인한다. 어댑터는 다음 태스크에서 붙인다. */
class FakeExplanationProviderTest {

    private class FakeProvider(private val result: ProviderResult) : ExplanationProvider {
        override val name = "fake"
        var lastSystemText: String? = null
        override fun explain(systemText: String, factsJson: String): ProviderResult {
            lastSystemText = systemText
            return result
        }
    }

    @Test
    fun `답변을 사용량과 함께 돌려준다`() {
        val provider = FakeProvider(
            Answered(
                Explanation("경복궁은 매우 붐빕니다.", listOf("concepts/congestion-diagnosis.md")),
                ProviderUsage(cacheReadTokens = 3800, cacheCreationTokens = 0, inputTokens = 120, outputTokens = 210),
            ),
        )

        val result = provider.explain("system", """{"courseUuid":"x"}""")

        assertThat(result).isInstanceOf(Answered::class.java)
        assertThat((result as Answered).usage.cacheReadTokens).isEqualTo(3800)
        assertThat(provider.lastSystemText).isEqualTo("system")
    }

    @Test
    fun `거절을 실패와 구분해 표현한다`() {
        // HTTP 200 에 stop_reason=refusal 이 오는 경로다. content 를 읽기 전에
        // 갈라야 하므로 결과 타입 자체가 달라야 한다.
        assertThat(FakeProvider(Refused("cyber")).explain("s", "f")).isEqualTo(Refused("cyber"))
    }

    @Test
    fun `실패는 이유를 들고 온다`() {
        assertThat(FakeProvider(Failed("timeout")).explain("s", "f")).isEqualTo(Failed("timeout"))
    }
}
