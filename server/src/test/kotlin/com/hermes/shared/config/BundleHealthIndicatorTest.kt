package com.hermes.shared.config

import com.hermes.context.Bundle
import com.hermes.context.BundleDocument
import com.hermes.context.BundleLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status

class BundleHealthIndicatorTest {

    @Test
    fun `번들이 적재됐으면 UP 이고 문서 수와 바이트를 보고한다`() {
        val health = BundleHealthIndicator(BundleLoader.load()).health()

        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details["documents"]).isEqualTo(9)

        // 바이트 수를 리터럴로 박지 않는다 — 위키 문서가 한 글자만 바뀌어도 깨지는데,
        // 그 실패는 헬스 인디케이터의 결함이 아니라 번들이 갱신됐다는 사실일 뿐이다.
        // 대신 리소스를 독립적으로 다시 읽어 바이트를 세서 대조한다. Bundle.byteSize()
        // 와 같은 경로로 계산하면 문자 수와 바이트 수를 혼동하는 회귀를 못 잡는다.
        val resourceBytes = BundleHealthIndicatorTest::class.java
            .getResourceAsStream("/prompts/hanjeok-bundle.txt")!!
            .readAllBytes().size

        assertThat(health.details["bytes"]).isEqualTo(resourceBytes)
    }

    @Test
    fun `보고하는 바이트는 문자 수가 아니라 UTF-8 바이트 수다`() {
        // 번들은 한국어 문서가 대부분이라 문자 수와 바이트 수가 크게 다르다.
        // 둘을 혼동하면 용량 판단이 3배 가까이 어긋난다.
        val bundle = BundleLoader.load()
        val health = BundleHealthIndicator(bundle).health()

        assertThat(health.details["bytes"] as Int)
            .isGreaterThan(bundle.raw.length)
    }

    @Test
    fun `번들이 비었으면 DOWN 이다`() {
        // 근거 없이 뜨면 안 된다 — 인용할 것이 없는 서버는 설명을 낼 수 없다.
        val health = BundleHealthIndicator(Bundle(emptyList<BundleDocument>(), "")).health()

        assertThat(health.status).isEqualTo(Status.DOWN)
    }
}
