package com.hermes.shared.config

import com.hermes.HermesApplication
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

/**
 * `application.yml` 의 `hermes.hanjeok.base-url` 에 더 이상 기본값이 없다는 걸 직접
 * 확인한다(최종 수정 라운드 항목 3). 예전 기본값(`https://api.hanjeok.example`)은
 * `HANJEOK_BASE_URL` 을 빼먹은 운영자에게 깨끗한 기동과 통과하는 헬스체크를 준 뒤
 * 첫 요청에서야 실제 백엔드 장애와 구분 안 되는 실패를 안겼다 — 그 조용한 오설정
 * 경로를 이 테스트가 막는다.
 *
 * `HermesApplication` 을 `SpringApplicationBuilder` 로 직접 띄운다(웹 서버는
 * `WebApplicationType.NONE` 으로 꺼서 포트 충돌 없이 빠르게 확인한다) — 실제 운영
 * 기동 경로 그대로다. `ApplicationContextRunner` 로 이 프로퍼티 하나만 떼어 재현하는
 * 방식은 시도했으나 오도하는 거짓 통과를 냈다: 그 러너의 기본 컨텍스트는
 * `PropertySourcesPlaceholderConfigurer` 없이 `Environment.resolvePlaceholders`(관대한,
 * 안 던지는 버전)로 `@Value` 를 해석해서 `HANJEOK_BASE_URL` 이 없어도 예외 없이
 * `"${HANJEOK_BASE_URL}"` 이라는 문자열 값으로 조용히 성공해 버렸다 — 이 프로젝트가
 * 막으려는 바로 그 조용한 실패 모드를 테스트 러너가 그대로 재현한 것이다. 실제
 * `@EnableAutoConfiguration` 이 등록하는 엄격한(필수) 플레이스홀더 리졸버를 타야
 * `PlaceholderResolutionException` 이 실제로 던져진다 — 그래서 전체 애플리케이션을
 * 띄운다.
 *
 * 외부 호출 없음. 실패 시나리오는 `hanjeokRestClient` 빈 생성에서 멈추므로 그 뒤에
 * 오는 빈은 만들어지지 않고, 성공 시나리오도 `WebApplicationType.NONE` 이라 소켓을
 * 열지 않는다. `ANTHROPIC_API_KEY` 는 다른 테스트와 같은 이유로 실제 키가 아닌
 * 문자열을 프로퍼티로만 넘긴다(`AnthropicOkHttpClient.fromEnv()` 는 호출하지 않는다).
 */
class HanjeokBaseUrlConfigTest {

    @Test
    fun `HANJEOK_BASE_URL 없이는 기동이 실패하고 빠진 프로퍼티 이름을 남긴다`() {
        assertThatThrownBy {
            SpringApplicationBuilder(HermesApplication::class.java)
                .web(WebApplicationType.NONE)
                .properties("ANTHROPIC_API_KEY=not-used-in-this-test")
                .run()
        }.hasStackTraceContaining("HANJEOK_BASE_URL")
    }

    /**
     * `SpringApplicationBuilder.properties(...)` 는 `SpringApplication.setDefaultProperties`
     * 로 들어가는데 그 우선순위는 `application.yml` 보다 낮다 — 그래서
     * `hermes.hanjeok.base-url` 을 거기로 직접 넘겨도 `application.yml` 의
     * `${HANJEOK_BASE_URL}` 이 그대로 이긴다(실측: 처음에는 그렇게 썼다가 이 테스트가
     * 여전히 `PlaceholderResolutionException` 으로 실패하는 걸 보고서야 알았다).
     * 실제 운영자가 하는 것과 같은 경로로 넘겨야 한다 — 시스템 프로퍼티(또는 환경
     * 변수) `HANJEOK_BASE_URL` 자체를 채운다.
     */
    @Test
    fun `HANJEOK_BASE_URL 을 주면 정상 기동한다`() {
        System.setProperty("HANJEOK_BASE_URL", "http://localhost:1")
        try {
            val context = SpringApplicationBuilder(HermesApplication::class.java)
                .web(WebApplicationType.NONE)
                .properties("ANTHROPIC_API_KEY=not-used-in-this-test")
                .run()
            try {
                assertThat(context.isActive).isTrue()
            } finally {
                context.close()
            }
        } finally {
            System.clearProperty("HANJEOK_BASE_URL")
        }
    }
}
