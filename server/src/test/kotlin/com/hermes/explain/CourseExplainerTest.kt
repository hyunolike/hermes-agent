package com.hermes.explain

import com.hermes.context.BundleLoader
import com.hermes.context.CitationValidator
import com.hermes.context.PromptAssembler
import com.hermes.facts.FactsSource
import com.hermes.facts.HanjeokClient
import com.hermes.facts.HanjeokUnavailableException
import com.hermes.llm.Answered
import com.hermes.llm.Explanation
import com.hermes.llm.ExplanationProvider
import com.hermes.llm.Failed
import com.hermes.llm.ProviderResult
import com.hermes.llm.ProviderUsage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class CourseExplainerTest {

    private val mapper = ObjectMapper()
    private val bundle = BundleLoader.load()
    private val executor = Executors.newFixedThreadPool(2)

    private val courseJson = """
        {"targetDate":"2026-08-15","title":"제목","congestionReductionRate":34,"summary":"요약",
         "recommendedDate":null,
         "items":[{"attractionId":1001,"name":"경복궁","visitOrder":1,"timeLabel":"오전 10:00",
                   "grade":"VERY_CROWDED","reason":"첫 방문지","travelMinutesFromPrev":null}]}
    """.trimIndent()

    private open inner class FakeClient : HanjeokClient {
        override fun course(courseUuid: String): JsonNode = mapper.readTree(courseJson)
        override fun congestion(attractionId: Long, date: String): JsonNode = mapper.readTree(
            """{"diagnosis":{"concentration":87.3,"percentile":92,"grade":"VERY_CROWDED","message":"붐빈다"},"betterDates":[]}""",
        )
        override fun alternatives(attractionId: Long, date: String, radiusKm: Int): JsonNode = mapper.readTree("[]")
    }

    private class CountingProvider(private val result: ProviderResult) : ExplanationProvider {
        override val name = "counting"
        var calls = 0
        override fun explain(systemText: String, factsJson: String): ProviderResult {
            calls++; return result
        }
    }

    private fun explainer(
        provider: ExplanationProvider,
        client: HanjeokClient = FakeClient(),
        cache: ExplanationCache = ExplanationCache(),
    ) = CourseExplainer(
        factsSource = FactsSource(client, 15, executor),
        service = ExplanationService(PromptAssembler(bundle), CitationValidator(bundle), provider),
        cache = cache,
    )

    private fun answered() = Answered(
        Explanation("경복궁은 붐빕니다.", listOf("concepts/congestion-diagnosis.md")),
        ProviderUsage(0, 0, 0, 0),
    )

    @Test
    fun `설명과 함께 facts 를 돌려준다`() {
        // 프론트가 코스를 그리려면 facts 가 필요하고, 그래야 한적을 직접 안 부른다.
        val result = explainer(CountingProvider(answered())).explain("abc")

        assertThat(result.explanation.explanation).isEqualTo("경복궁은 붐빕니다.")
        assertThat(mapper.readTree(result.factsJson).at("/items/0/name").asText()).isEqualTo("경복궁")
        assertThat(result.cached).isFalse()
    }

    @Test
    fun `같은 uuid 를 다시 물으면 모델을 다시 부르지 않는다`() {
        val provider = CountingProvider(answered())
        val explainer = explainer(provider)

        explainer.explain("abc")
        val second = explainer.explain("abc")

        assertThat(provider.calls).describedAs("코스는 불변이므로 한 번이면 된다").isEqualTo(1)
        assertThat(second.cached).isTrue()
    }

    @Test
    fun `한적이 실패하면 설명 불가로 바뀐다`() {
        val client = object : FakeClient() {
            override fun course(courseUuid: String): JsonNode = throw HanjeokUnavailableException("down")
        }

        assertThatThrownBy { explainer(CountingProvider(answered()), client).explain("abc") }
            .isInstanceOf(ExplanationUnavailableException::class.java)
    }

    @Test
    fun `설명 생성이 실패하면 캐시에 넣지 않는다`() {
        // 실패를 캐시하면 일시적 장애가 그 코스에 영구히 눌어붙는다.
        val provider = CountingProvider(Failed("timeout"))
        val cache = ExplanationCache()
        val explainer = explainer(provider, cache = cache)

        assertThatThrownBy { explainer.explain("abc") }.isInstanceOf(ExplanationUnavailableException::class.java)

        assertThat(cache.size()).isZero()
        assertThat(cache.get("abc")).isNull()
    }
}
