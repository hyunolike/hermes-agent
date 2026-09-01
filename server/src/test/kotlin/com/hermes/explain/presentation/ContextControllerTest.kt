package com.hermes.explain.presentation

import com.hermes.context.BundleLoader
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ContextControllerTest {

    private val bundle = BundleLoader.load()
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(ContextController(bundle)).build()

    @Test
    fun `번들에 담긴 문서 목록을 낸다`() {
        mvc.perform(get("/agent/context"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(9))
            .andExpect(jsonPath("$[0].path").value("concepts/travel-context-layer.md"))
    }

    @Test
    fun `문서 본문은 LLM 에 보낸 바이트 그대로다`() {
        val expected = bundle.document("concepts/congestion-diagnosis.md")!!.content

        mvc.perform(get("/agent/context/concepts/congestion-diagnosis.md"))
            .andExpect(status().isOk)
            .andExpect(content().string(expected))
    }

    @Test
    fun `번들에 없는 경로는 404 다`() {
        // 인용 검증을 통과한 경로만 존재한다. 목록 밖은 절대 안 나간다.
        mvc.perform(get("/agent/context/concepts/weather-aware-travel-recommendation.md"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `상위 디렉터리 탈출을 허용하지 않는다`() {
        mvc.perform(get("/agent/context/../../build.gradle.kts")).andExpect(status().isNotFound)
    }
}
