package com.hermes.shared.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status

/**
 * 실제 키나 네트워크 없이 두 상태를 검증한다 — 생성자에 뭘 읽는지 직접 넣어준다
 * (`LlmCredentialHealthIndicator` 가 `@Value` 로 주입받는 값 그 자체).
 */
class LlmCredentialHealthIndicatorTest {

    @Test
    fun `API 키도 인증 토큰도 없으면 DOWN 이다`() {
        val health = LlmCredentialHealthIndicator(provider = "anthropic", apiKey = "", authToken = "").health()

        assertThat(health.status).isEqualTo(Status.DOWN)
    }

    @Test
    fun `공백뿐인 값도 없는 것과 같다`() {
        val health = LlmCredentialHealthIndicator(provider = "anthropic", apiKey = "   ", authToken = "").health()

        assertThat(health.status).isEqualTo(Status.DOWN)
    }

    @Test
    fun `API 키가 있으면 UP 이다`() {
        val health = LlmCredentialHealthIndicator(provider = "anthropic", apiKey = "sk-not-a-real-key", authToken = "").health()

        assertThat(health.status).isEqualTo(Status.UP)
    }

    @Test
    fun `다른 프로바이더를 고르면 Anthropic 키를 묻지 않는다`() {
        // OpenAI 로 도는 서버에 Anthropic 키가 없다고 DOWN 을 내면, 멀쩡히 답하는
        // 서버에서 Cloud Run 이 트래픽을 끊는다. openai·openrouter 는 키가 없으면
        // LlmSelection 이 기동 단계에서 이미 멈춘다.
        val health = LlmCredentialHealthIndicator(provider = "openai", apiKey = "", authToken = "").health()

        assertThat(health.status).isEqualTo(Status.UP)
    }

    @Test
    fun `API 키 없이 인증 토큰만 있어도 UP 이다`() {
        val health = LlmCredentialHealthIndicator(provider = "anthropic", apiKey = "", authToken = "token-not-real").health()

        assertThat(health.status).isEqualTo(Status.UP)
    }
}
