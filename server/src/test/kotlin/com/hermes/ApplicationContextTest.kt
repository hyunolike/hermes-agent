package com.hermes

import com.hermes.explain.CourseExplainer
import com.hermes.explain.presentation.ContextController
import com.hermes.explain.presentation.ExplainController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 배선이 실제로 물리는지 본다. 단위 테스트가 전부 통과해도 빈 하나가 빠지면
 * 서버는 뜨지 않는다 — 그 실패를 배포가 아니라 여기서 만난다.
 *
 * 어떤 외부 호출도 하지 않는다. 프로바이더는 키 없이 생성되고(호출하지 않으므로
 * 문제되지 않는다 — `AnthropicOkHttpClient.fromEnv()`는 키가 없어도 던지지
 * 않는다, 실측으로 확인함), 한적 클라이언트는 base URL 만 잡는다.
 */
@SpringBootTest(properties = ["hermes.hanjeok.base-url=http://localhost:1", "ANTHROPIC_API_KEY=not-used-in-this-test"])
class ApplicationContextTest {

    @Autowired lateinit var explainer: CourseExplainer
    @Autowired lateinit var explainController: ExplainController
    @Autowired lateinit var contextController: ContextController

    @Test
    fun `컨텍스트가 뜨고 핵심 빈이 물린다`() {
        assertThat(explainer).isNotNull()
        assertThat(explainController).isNotNull()
        assertThat(contextController).isNotNull()
    }
}
