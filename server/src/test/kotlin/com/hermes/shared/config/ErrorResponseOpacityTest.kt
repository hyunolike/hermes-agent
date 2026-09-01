package com.hermes.shared.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `application.yml` 의 `spring.web.error.*` 가 실제로 새는 걸 막는지 확인한다.
 *
 * 접두사가 `server.error` 가 아니라 `spring.web.error` 인 이유는 `application.yml`
 * 의 주석 참고(스프링 부트 4.1 에서 `ErrorProperties` 는 `ServerProperties` 가 아니라
 * `WebProperties` 에 중첩된다). 첫 버전은 `server.error.*` 로 썼고, 그 시절엔 이
 * 테스트가 뮤테이션(`include-message: always`)에도 안 흔들렸다 — 그런데 그건
 * 어떤 경로가 안전해서가 아니라 그 설정 블록 전체가 어떤 필드에도 안 묶여
 * 조용히 무시되고 있었기 때문이었다(실측 없이 Boot 3 시절 접두사를 그대로 옮겨
 * 적은 결과). 접두사를 고치고 나서 뮤테이션을 다시 돌리니 아래 두 테스트가 모두
 * 예상대로 반응한다.
 *
 * `ApiErrorHandler` 가 잡는 `ExplanationUnavailableException` 경로는
 * `ExplainControllerTest` 가 이미 본다(코드만, `reason` 없음). 여기서 보는 건
 * 그 핸들러가 잡지 않는 두 경로다.
 *
 * 1) 깨진 JSON 요청 본문 → `HttpMessageNotReadableException`. `DefaultErrorAttributes`
 *    는 `HandlerExceptionResolver` 로도 등록되어 있고 `@Order(HIGHEST_PRECEDENCE)`
 *    라 어떤 예외든 가장 먼저 가로채 요청 속성에 저장한 뒤 다음 리졸버(sendError
 *    를 실제로 호출하는 `DefaultHandlerExceptionResolver`)에 넘긴다 — 그래서 이
 *    경로도 뒤이은 `/error` 포워드에서 `spring.web.error.*` 를 그대로 탄다. (검토
 *    라운드 1 초안은 이 경로가 그 설정과 무관하게 안전하다고 적었는데, 그건 접두사가
 *    잘못돼 있던 상태에서 관찰한 거짓 신호였다 — 뒤에 남긴 뮤테이션 증거가 실제
 *    인과관계다.)
 * 2) 잡히지 않은 평범한 `RuntimeException` → 위와 같은 경로를 탄다. 프로덕션
 *    코드에는 이런 예외를 던지는 지점이 없어서, 검증하려고 테스트 전용
 *    컨트롤러(`/test/boom`)를 이 클래스 안에서만 등록한다 — 운영 코드는
 *    건드리지 않는다.
 *
 * MockMvc(WebEnvironment.MOCK)로는 이 경로들을 실측할 수 없었다 — 컨테이너가
 * 없어 `sendError` 이후 `/error`로의 서블릿 포워드가 일어나지 않고 본문이
 * 그냥 비어버려, 무엇을 검증하는지 알 수 없는 거짓 통과가 나왔다(직접 확인함:
 * `response.contentAsString`이 빈 문자열이었다). 그래서 RANDOM_PORT 로 실제
 * 내장 톰캣을 띄우고 진짜 HTTP 응답을 받는다 — 루프백(localhost) 호출이라
 * "테스트가 네트워크나 API 키를 필요로 하면 안 된다"는 제약과는 무관하다.
 *
 * 외부 호출 없음. 첫 번째 테스트는 컨트롤러 메서드에 도달하기 전에 JSON 파싱이
 * 깨지므로 CourseExplainer 를 호출하지 않는다. 두 번째 테스트는 이 클래스 안에서만
 * 뜨는 `/test/boom` 을 부르므로 한적도 Anthropic 도 부르지 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["hermes.hanjeok.base-url=http://localhost:1", "ANTHROPIC_API_KEY=not-used-in-this-test"],
)
class ErrorResponseOpacityTest {

    @LocalServerPort
    var port: Int = 0

    private val rest = TestRestTemplate()

    /** `/test/boom` — 이 테스트 클래스에서만 뜬다. 운영 컨텍스트에는 없다. */
    @TestConfiguration
    class BoomConfig {
        @Bean
        fun boomController(): BoomController = BoomController()
    }

    @RestController
    class BoomController {
        @GetMapping("/test/boom")
        fun boom(): String = throw RuntimeException("INTERNAL DETAIL THAT MUST NOT LEAK")
    }

    @Test
    fun `프레임워크 기본 에러 응답도 메시지·스택트레이스·바인딩오류를 내지 않는다`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val request = HttpEntity("not-json-at-all", headers)

        val response = rest.exchange(
            "http://localhost:$port/agent/explain",
            HttpMethod.POST,
            request,
            String::class.java,
        )

        assertThat(response.statusCode.is4xxClientError).isTrue()

        val body = ObjectMapper().readTree(response.body)
        assertThat(body.has("message")).isFalse()
        assertThat(body.has("trace")).isFalse()
        assertThat(body.has("exception")).isFalse()
        assertThat(body.has("errors")).isFalse()
        // "code" 도 없다 — 그건 ApiErrorHandler 전용이고, 이 요청은 그 핸들러가
        // 잡는 ExplanationUnavailableException 이 아니라 파싱 실패다. 기본
        // 에러 응답에 그 필드가 섞여 들어오면 계약이 흔들렸다는 뜻이다.
        assertThat(body.has("code")).isFalse()
    }

    /**
     * `/test/boom` 이 잡히지 않은 `RuntimeException`(메시지: "INTERNAL DETAIL THAT
     * MUST NOT LEAK")을 던지면 `DefaultErrorAttributes` 를 타고 `/error` 로
     * 포워드된다 — `spring.web.error.include-message: never` 가 없으면 그 문자열이
     * 그대로 응답 본문에 실린다(뮤테이션으로 실측: `always` 로 바꾸면 이 테스트가
     * 정확히 그 문자열을 담은 본문으로 실패한다).
     */
    @Test
    fun `잡히지 않은 RuntimeException 은 메시지를 남기지 않는다`() {
        val response = rest.getForEntity("http://localhost:$port/test/boom", String::class.java)

        assertThat(response.statusCode.is5xxServerError).isTrue()

        val raw = response.body ?: ""
        assertThat(raw).doesNotContain("INTERNAL DETAIL THAT MUST NOT LEAK")

        val body = ObjectMapper().readTree(raw)
        assertThat(body.has("message")).isFalse()
        assertThat(body.has("trace")).isFalse()
        assertThat(body.has("exception")).isFalse()
    }
}
