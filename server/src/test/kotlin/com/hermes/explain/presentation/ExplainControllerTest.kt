package com.hermes.explain.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import com.hermes.explain.CourseExplainer
import com.hermes.explain.CourseExplanation
import com.hermes.explain.ExplanationUnavailableException
import com.hermes.llm.Explanation
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ExplainControllerTest {

    private val explainer = mock(CourseExplainer::class.java)
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(ExplainController(explainer, "claude-opus-5", ObjectMapper()))
        .setControllerAdvice(ApiErrorHandler())
        .build()

    @Test
    fun `설명과 인용과 facts 를 함께 돌려준다`() {
        `when`(explainer.explain("abc")).thenReturn(
            CourseExplanation(
                explanation = Explanation("경복궁은 붐빕니다.", listOf("concepts/congestion-diagnosis.md")),
                factsJson = """{"items":[{"name":"경복궁"}]}""",
                cached = false,
            ),
        )

        mvc.perform(
            post("/agent/explain").contentType(MediaType.APPLICATION_JSON)
                .content("""{"courseUuid":"abc"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.explanation").value("경복궁은 붐빕니다."))
            .andExpect(jsonPath("$.citations[0]").value("concepts/congestion-diagnosis.md"))
            // 프론트가 코스를 그리려면 facts 가 필요하다 — 그래야 한적을 직접 안 부른다.
            .andExpect(jsonPath("$.facts.items[0].name").value("경복궁"))
            .andExpect(jsonPath("$.model").value("claude-opus-5"))
    }

    @Test
    fun `설명 불가는 503 과 코드만 낸다`() {
        // 사유는 로그에 남고 본문에는 나가지 않는다 — 모델이 거부한 텍스트나
        // 내부 실패 사유가 클라이언트에 노출되면 안 된다.
        `when`(explainer.explain("abc")).thenThrow(ExplanationUnavailableException("citations not in bundle: x.md"))

        mvc.perform(
            post("/agent/explain").contentType(MediaType.APPLICATION_JSON)
                .content("""{"courseUuid":"abc"}"""),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("EXPLANATION_UNAVAILABLE"))
            .andExpect(jsonPath("$.reason").doesNotExist())
    }
}
