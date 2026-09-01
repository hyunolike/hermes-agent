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
 * 실패가 실제로 새는 걸 막는지 확인한다 — 두 겹으로.
 *
 * `ApiErrorHandler` 는 이제 `ExplanationUnavailableException` 뿐 아니라 그 밖의
 * 모든 예외도 잡는다(`@ExceptionHandler(Exception::class)`, 최종 수정 라운드 항목 1).
 * 그 핸들러가 잡으면 요청은 스프링의 기본 에러 컨트롤러(`/error`,
 * `DefaultErrorAttributes`)까지 가지 않고 `ExceptionHandlerExceptionResolver` 단계에서
 * 바로 `{"code":"EXPLANATION_UNAVAILABLE"}` 로 렌더링된다 — 그래서 아래 두 테스트가
 * 확인하는 건 이제 "새지 않는다"를 넘어 "정확히 그 계약 본문이다"다.
 *
 * `application.yml` 의 `spring.web.error.*` 는 그래도 지운다 — `ApiErrorHandler` 가
 * 못 잡는 경로(예: 핸들러 자체를 못 찾는 404, `Exception` 이 아닌 `Throwable`)가
 * 기본 에러 컨트롤러로 떨어질 때의 방어선이다. 접두사가 `server.error` 가 아니라
 * `spring.web.error` 인 이유는 `application.yml` 의 주석 참고(스프링 부트 4.1 에서
 * `ErrorProperties` 는 `ServerProperties` 가 아니라 `WebProperties` 에 중첩된다).
 *
 * 1) 깨진 JSON 요청 본문 → `HttpMessageNotReadableException`. 컨트롤러 메서드
 *    진입 전(인자 바인딩 단계)에 던져지지만 `ExceptionHandlerExceptionResolver` 는
 *    이 단계의 예외도 잡으므로 `ApiErrorHandler.onUnexpected` 로 간다(실측:
 *    라운드 1 은 이 경로가 `spring.web.error.*` 로만 막혀 4xx·코드 없음으로
 *    응답한다고 적었는데, `Exception::class` 핸들러를 넣고 나니 이제는 503 +
 *    계약 본문으로 응답한다 — 아래에서 재검증).
 * 2) 잡히지 않은 평범한 `RuntimeException`(`/test/boom`, 이 클래스 안에서만
 *    뜨는 테스트 전용 컨트롤러 — 운영 코드에는 이런 예외를 던지는 지점이 없다)도
 *    같은 핸들러로 간다.
 *
 * MockMvc(WebEnvironment.MOCK)로는 이 경로들을 실측할 수 없었다 — 컨테이너가
 * 없어 실제 HTTP 응답 렌더링 경로를 안 타고 본문이 그냥 비어버려, 무엇을
 * 검증하는지 알 수 없는 거짓 통과가 나왔다(직접 확인함). 그래서 RANDOM_PORT 로
 * 실제 내장 톰캣을 띄우고 진짜 HTTP 응답을 받는다 — 루프백(localhost) 호출이라
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
    fun `깨진 JSON 요청도 메시지·스택트레이스·바인딩오류 없이 계약 본문만 준다`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val request = HttpEntity("not-json-at-all", headers)

        val response = rest.exchange(
            "http://localhost:$port/agent/explain",
            HttpMethod.POST,
            request,
            String::class.java,
        )

        // ApiErrorHandler.onUnexpected 가 잡는다 — 더는 4xx 가 아니라 다른 모든
        // 실패와 같은 503 이다(최종 수정 라운드 항목 1).
        assertThat(response.statusCode.value()).isEqualTo(503)

        val body = ObjectMapper().readTree(response.body)
        assertThat(body.has("message")).isFalse()
        assertThat(body.has("trace")).isFalse()
        assertThat(body.has("exception")).isFalse()
        assertThat(body.has("errors")).isFalse()
        assertThat(body.get("code").asText()).isEqualTo("EXPLANATION_UNAVAILABLE")
    }

    /**
     * `/test/boom` 이 예기치 못한(anticipated 되지 않은) `RuntimeException`(메시지:
     * "INTERNAL DETAIL THAT MUST NOT LEAK")을 던진다 — 프로덕션에서는
     * `ExplainController` 의 `mapper.readTree(...)` 가 예상 못 한 입력에 걸릴 때
     * 같은 모양의 실패가 난다. `ApiErrorHandler.onUnexpected` 가 잡아 그 메시지를
     * 삼키고, 클라이언트에게는 `ExplanationUnavailableException` 과 완전히 같은
     * 모양의 503 계약 본문만 준다 — "새지 않는다"가 아니라 "본문이 정확히 이거다"를
     * 검증한다(항목 1 이 요구하는 뮤테이션 증거는 커밋 메시지/최종 보고서 참고 —
     * 핸들러를 빼면 이 테스트가 정확히 이 지점에서 실패로 바뀐다).
     */
    @Test
    fun `잡히지 않은 예외는 EXPLANATION_UNAVAILABLE 503 계약 본문 그대로다`() {
        val response = rest.getForEntity("http://localhost:$port/test/boom", String::class.java)

        assertThat(response.statusCode.value()).isEqualTo(503)

        val raw = response.body ?: ""
        assertThat(raw).doesNotContain("INTERNAL DETAIL THAT MUST NOT LEAK")

        val body = ObjectMapper().readTree(raw)
        assertThat(body.fieldNames().asSequence().toList()).containsExactly("code")
        assertThat(body.get("code").asText()).isEqualTo("EXPLANATION_UNAVAILABLE")
    }
}
