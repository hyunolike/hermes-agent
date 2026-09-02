package com.hermes.shared.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

/**
 * **Anthropic 을 고른 경우에만 실질적인 검사다.** openai·openrouter 는
 * `LlmSelection` 이 키 없이는 기동 자체를 멈추므로 여기까지 오지 못한다. 반면
 * `AnthropicOkHttpClient.fromEnv()` 는 `ANTHROPIC_API_KEY`(또는
 * `ANTHROPIC_AUTH_TOKEN`)가 없어도 던지지 않는다 — 그냥 인증되지 않은 클라이언트가
 * 만들어진다. 그걸 그대로 두면 서버는 뜨고 `UP` 을 보고하고 레디니스 프로브를
 * 통과한 뒤, 모든 요청에서 503 을 낸다 — 살아있지만 쓸모없는 서버다.
 *
 * 여기서는 자격 증명의 "존재"만 본다 — API 를 실제로 호출하지 않는다. 호출까지
 * 하면 그 비용과 지연이 헬스체크에 들러붙고, 무엇보다 한적(hanjeok) 같은 외부
 * 의존성의 장애를 이 서버 자신의 장애로 전이시켜 연쇄 재시작을 유발한다 — 그래서
 * 한적 도달성은 의도적으로 헬스체크에 넣지 않는다(그건 `demoReachability` 태스크의
 * 몫이다).
 *
 * `@Value` 로 주입받는 이유는 두 가지다. 첫째, `System.getenv` 를 직접 읽으면
 * 테스트가 실제 프로세스 환경변수를 조작해야 하는데 이 프로젝트의 어떤 테스트도
 * 그렇게 하지 않는다(그리고 하면 안 된다 — 프로세스 전역 상태다). 둘째, Spring 의
 * `SystemEnvironmentPropertySource` 가 OS 환경변수 `ANTHROPIC_API_KEY` 를 같은
 * 이름의 프로퍼티로도 노출하므로, `@SpringBootTest(properties = ["ANTHROPIC_API_KEY=..."])`
 * 로 실제 키 없이 두 상태를 모두 검증할 수 있다(`ApplicationContextTest`,
 * `ErrorResponseOpacityTest` 가 이미 이 프로퍼티를 이렇게 넘긴다).
 */
@Component
class LlmCredentialHealthIndicator(
    @param:Value("\${hermes.llm.provider}") private val provider: String,
    @param:Value("\${ANTHROPIC_API_KEY:}") private val apiKey: String,
    @param:Value("\${ANTHROPIC_AUTH_TOKEN:}") private val authToken: String,
) : HealthIndicator {

    override fun health(): Health {
        // 다른 프로바이더에 Anthropic 키를 요구하면, 멀쩡히 답하는 서버가 DOWN 으로
        // 보고되고 Cloud Run 이 트래픽을 끊는다. 살아있는 서버를 죽었다고 하는 쪽이
        // 반대 실수보다 눈에 덜 띈다 — 헬스체크는 자기가 고른 것만 봐야 한다.
        if (provider != "anthropic") return Health.up().withDetail("provider", provider).build()

        if (apiKey.isBlank() && authToken.isBlank()) {
            return Health.down()
                .withDetail("reason", "no Anthropic credential configured (ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN)")
                .build()
        }
        return Health.up().withDetail("provider", provider).build()
    }
}
