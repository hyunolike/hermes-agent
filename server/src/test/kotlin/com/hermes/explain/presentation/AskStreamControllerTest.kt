package com.hermes.explain.presentation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.hermes.context.BundleLoader
import com.hermes.context.CitationValidator
import com.hermes.context.PromptAssembler
import com.hermes.explain.CourseQuestionService
import com.hermes.facts.FactsSource
import com.hermes.facts.HanjeokClient
import com.hermes.facts.HanjeokUnavailableException
import com.hermes.llm.ExplanationProvider
import com.hermes.llm.ProviderResult
import com.hermes.llm.ProviderUsage
import com.hermes.llm.StreamCompleted
import com.hermes.llm.StreamEnd
import com.hermes.llm.StreamFailed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.concurrent.Executors

/**
 * AskControllerTest 와 같이 진짜 객체를 조립한다. 가짜는 한적 클라이언트와 프로바이더뿐이다.
 */
class AskStreamControllerTest {

    private val mapper = ObjectMapper()
    private val bundle = BundleLoader.load()
    private val known = bundle.paths().first()
    private val factsExecutor = Executors.newFixedThreadPool(2)

    /** AskControllerTest 의 것과 같은 사실. 두 경로가 같은 코스를 본다. */
    private open inner class FakeClient : HanjeokClient {
        override fun course(courseUuid: String): JsonNode = mapper.readTree(
            """{"targetDate":"2026-09-12","title":"제목","congestionReductionRate":34,"summary":"요약",
                "recommendedDate":null,
                "items":[{"attractionId":1001,"name":"경복궁","visitOrder":1,"timeLabel":"오전 10:00",
                          "grade":"VERY_CROWDED","reason":"첫 방문지","travelMinutesFromPrev":null}]}""",
        )

        override fun congestion(attractionId: Long, date: String): JsonNode = mapper.readTree(
            """{"diagnosis":{"concentration":87.3,"percentile":92,"grade":"VERY_CROWDED","message":"붐빈다"},
                "betterDates":[]}""",
        )

        override fun alternatives(attractionId: Long, date: String, radiusKm: Int): JsonNode =
            mapper.readTree("[]")
    }

    /** 조각을 흘린다. explain 이 불리면 스트리밍 경로가 비스트리밍으로 새고 있다는 뜻이다. */
    private class StreamingProvider(
        private val chunks: List<String>,
        private val end: StreamEnd = StreamCompleted(ProviderUsage(0, 0, 0, 0)),
    ) : ExplanationProvider {
        override val name = "streaming"

        override fun explain(systemText: String, userText: String): ProviderResult =
            error("스트리밍 경로에서 explain 이 불리면 안 된다")

        override fun stream(systemText: String, userText: String, onChunk: (String) -> Unit): StreamEnd {
            chunks.forEach(onChunk)
            return end
        }
    }

    private fun mvc(provider: ExplanationProvider): MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                AskStreamController(
                    FactsSource(FakeClient(), 15, factsExecutor),
                    CourseQuestionService(PromptAssembler(bundle), CitationValidator(bundle), provider),
                    AskStreamExecutor(Executors.newSingleThreadExecutor()),
                    "gpt-4o",
                ),
            )
            .setControllerAdvice(ApiErrorHandler())
            .build()

    /** 스트림을 끝까지 받아 본문을 돌려준다. SSE 는 UTF-8 로 읽어야 한글이 안 깨진다. */
    private fun streamed(provider: ExplanationProvider): String {
        val mvc = mvc(provider)
        val started = mvc
            .perform(
                post("/agent/ask/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"courseUuid":"abc","question":"왜 이 순서예요?"}"""),
            )
            .andExpect(request().asyncStarted())
            .andReturn()
        return mvc.perform(asyncDispatch(started)).andReturn().response.getContentAsString(Charsets.UTF_8)
    }

    @Test
    fun `인용이 유효하면 citations 뒤에 delta 와 done 이 나간다`() {
        val body = streamed(StreamingProvider(listOf("""{"citations":["$known"],""", """"explanation":"가나"}""")))

        assertThat(body).contains("event:citations").contains("event:delta").contains("event:done")
        assertThat(body.indexOf("event:citations")).isLessThan(body.indexOf("event:delta"))
        assertThat(body).contains("가나")
    }

    @Test
    fun `인용이 무효하면 delta 없이 불투명한 unavailable 만 나간다`() {
        val body = streamed(
            StreamingProvider(listOf("""{"citations":["not/in/bundle.md"],"explanation":"새면 안 되는 문장"}""")),
        )

        assertThat(body).doesNotContain("event:delta")
        assertThat(body).contains("event:unavailable").contains("EXPLANATION_UNAVAILABLE")
        // 불투명성: 사유(인용 경로)도 본문도 브라우저로 새면 안 된다.
        assertThat(body).doesNotContain("not/in/bundle.md")
        assertThat(body).doesNotContain("citations not in bundle")
        assertThat(body).doesNotContain("새면 안 되는 문장")
    }

    @Test
    fun `본문 도중 실패하면 불투명한 aborted 가 나간다`() {
        val body = streamed(
            StreamingProvider(
                listOf("""{"citations":["$known"],"explanation":"미"""),
                end = StreamFailed("INTERNAL DETAIL THAT MUST NOT LEAK"),
            ),
        )

        assertThat(body).contains("event:aborted").contains("EXPLANATION_ABORTED")
        assertThat(body).doesNotContain("INTERNAL DETAIL THAT MUST NOT LEAK")
    }

    @Test
    fun `사실을 못 받으면 불투명한 unavailable 만 나가고 사유는 새지 않는다`() {
        val broken = object : FakeClient() {
            override fun course(courseUuid: String): JsonNode =
                throw HanjeokUnavailableException("SENTINEL COURSE 404 DETAIL")
        }
        val mvc = MockMvcBuilders
            .standaloneSetup(
                AskStreamController(
                    FactsSource(broken, 15, factsExecutor),
                    CourseQuestionService(
                        PromptAssembler(bundle),
                        CitationValidator(bundle),
                        StreamingProvider(emptyList()),
                    ),
                    AskStreamExecutor(Executors.newSingleThreadExecutor()),
                    "gpt-4o",
                ),
            )
            .setControllerAdvice(ApiErrorHandler())
            .build()

        val started = mvc
            .perform(
                post("/agent/ask/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"courseUuid":"abc","question":"왜 이 순서예요?"}"""),
            )
            .andExpect(request().asyncStarted())
            .andReturn()
        val body = mvc.perform(asyncDispatch(started)).andReturn().response.getContentAsString(Charsets.UTF_8)

        assertThat(body).contains("event:unavailable").contains("EXPLANATION_UNAVAILABLE")
        assertThat(body).doesNotContain("SENTINEL COURSE 404 DETAIL")
    }

    @Test
    fun `질문이 비어 있으면 스트림을 열지 않고 400`() {
        mvc(StreamingProvider(emptyList()))
            .perform(
                post("/agent/ask/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"courseUuid":"abc","question":"   "}"""),
            )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `courseUuid 가 비어 있으면 스트림을 열지 않고 400`() {
        // 200 SSE unavailable 로 내리면 클라이언트가 재시도한다 — 몇 번을 보내도 같은
        // 이유로 실패하는 요청이라 재시도 폭풍이 된다. AskController(비스트리밍)는
        // 이 경우를 이미 400으로 막는다.
        mvc(StreamingProvider(emptyList()))
            .perform(
                post("/agent/ask/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"courseUuid":"   ","question":"왜 이 순서예요?"}"""),
            )
            .andExpect(status().isBadRequest)
    }
}
