package com.hermes.shared.config

import com.hermes.context.Bundle
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

/** 근거 없이 뜨면 안 된다 — 인용할 것이 없는 서버는 설명을 낼 수 없다. */
@Component
class BundleHealthIndicator(private val bundle: Bundle) : HealthIndicator {

    override fun health(): Health {
        if (bundle.documents.isEmpty()) {
            return Health.down().withDetail("reason", "context bundle is empty").build()
        }
        return Health.up()
            .withDetail("documents", bundle.documents.size)
            .withDetail("bytes", bundle.byteSize())
            .build()
    }
}
