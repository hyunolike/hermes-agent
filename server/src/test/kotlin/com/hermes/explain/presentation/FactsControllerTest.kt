package com.hermes.explain.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import com.hermes.explain.BackendFacts
import com.hermes.explain.ExplanationUnavailableException
import com.hermes.facts.FactsSource
import com.hermes.facts.HanjeokUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 사실만 주는 엔드포인트.
 *
 * 스펙 §6.1 은 "503이면 설명 블록만 사라지고 코스는 그대로 읽힌다"고 적었는데,
 * `POST /agent/explain` 하나가 설명과 facts 를 함께 내고 모든 실패가 503 이므로
 * LLM 만 실패해도 프론트에는 코스를 그릴 facts 가 없었다 — 화면 전체가 사라진다.
 * 이 엔드포인트가 그 약속을 실제로 지킬 수 있게 만든다.
 */
class FactsControllerTest {

    private val source = mock(FactsSource::class.java)
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(FactsController(source, ObjectMapper()))
        .setControllerAdvice(ApiErrorHandler())
        .build()

    @Test
    fun `사실을 JSON 객체로 돌려준다`() {
        // 문자열로 실어 보내면 프론트가 한 번 더 파싱해야 하고, 그 파싱은
        // 서버가 확정한 필드 순서를 프론트에서 다시 흔든다.
        `when`(source.fetch("abc")).thenReturn(BackendFacts("abc", """{"items":[{"name":"경복궁"}]}"""))

        mvc.perform(get("/agent/facts/abc"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.courseUuid").value("abc"))
            .andExpect(jsonPath("$.facts.items[0].name").value("경복궁"))
    }

    @Test
    fun `설명 경로에 의존하지 않는다`() {
        // 이 엔드포인트의 존재 이유가 "LLM 없이"다. 나중에 누가 CourseExplainer 를
        // 여기 끌어오면 유료 호출과 캐시가 따라 들어오고, 그러면 LLM 이 죽었을 때
        // 코스가 그려진다는 이 엔드포인트의 유일한 약속이 조용히 깨진다.
        val dependencies = FactsController::class.java.declaredConstructors
            .flatMap { it.parameterTypes.asIterable() }
            .map { it.name }

        assertThat(dependencies).noneMatch { it.contains("CourseExplainer") || it.contains("ExplanationService") }
    }

    @Test
    fun `한적이 죽으면 503 과 코드만 낸다`() {
        `when`(source.fetch("abc")).thenThrow(HanjeokUnavailableException("course 503"))

        mvc.perform(get("/agent/facts/abc"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("EXPLANATION_UNAVAILABLE"))
            // 내부 사유는 로그에만 남는다.
            .andExpect(jsonPath("$.message").doesNotExist())
    }

    @Test
    fun `한적 실패를 캐치올까지 흘려보내지 않는다`() {
        // 위 테스트만으로는 부족하다 — 예외를 그대로 던져도 ApiErrorHandler 의
        // 캐치올이 같은 503 을 내므로 응답만 봐서는 구분되지 않는다. 그런데 그
        // 캐치올은 "예기치 못한 실패"용이라 ERROR 로 로그를 남긴다. 한적이 잠깐
        // 죽을 때마다 서버 버그로 기록되면, 진짜 버그가 그 소음에 묻힌다.
        `when`(source.fetch("abc")).thenThrow(HanjeokUnavailableException("course 503"))

        assertThatThrownBy { FactsController(source, ObjectMapper()).facts("abc") }
            .isInstanceOf(ExplanationUnavailableException::class.java)
    }
}
