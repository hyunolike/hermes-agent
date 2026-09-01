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
        assertThat(health.details["bytes"]).isEqualTo(15681)
    }

    @Test
    fun `번들이 비었으면 DOWN 이다`() {
        // 근거 없이 뜨면 안 된다 — 인용할 것이 없는 서버는 설명을 낼 수 없다.
        val health = BundleHealthIndicator(Bundle(emptyList<BundleDocument>(), "")).health()

        assertThat(health.status).isEqualTo(Status.DOWN)
    }
}
