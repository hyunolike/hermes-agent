package com.hermes.explain

import com.hermes.llm.Explanation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExplanationCacheTest {

    private fun explanation(text: String) = Explanation(text, listOf("concepts/congestion-diagnosis.md"))

    @Test
    fun `넣은 것을 돌려준다`() {
        val cache = ExplanationCache()
        cache.put("a", explanation("설명 A"))

        assertThat(cache.get("a")?.explanation).isEqualTo("설명 A")
        assertThat(cache.get("b")).isNull()
    }

    @Test
    fun `상한을 넘으면 가장 오래 안 쓴 항목부터 버린다`() {
        val cache = ExplanationCache(maxEntries = 2)
        cache.put("a", explanation("A"))
        cache.put("b", explanation("B"))
        cache.get("a")             // a 를 최근 사용으로 만든다
        cache.put("c", explanation("C"))

        assertThat(cache.size()).isEqualTo(2)
        assertThat(cache.get("b")).describedAs("가장 오래 안 쓴 b 가 밀려나야 한다").isNull()
        assertThat(cache.get("a")).isNotNull()
        assertThat(cache.get("c")).isNotNull()
    }
}
