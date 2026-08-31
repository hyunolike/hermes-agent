package com.hermes

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScaffoldTest {
    @Test
    fun `소스셋이 연결되어 있다`() {
        assertThat(HermesApplication::class.java.packageName).isEqualTo("com.hermes")
    }
}
