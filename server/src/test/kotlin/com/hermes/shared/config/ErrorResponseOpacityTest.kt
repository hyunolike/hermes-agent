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
 * 실패가 실제로 새는 걸 막는지 확인한다 — `ApiErrorHandler` 의 세 갈래 중
 * 아래 두 갈래(1 은 `ExplanationUnavailableException` 전용이라
 * `ExplainControllerTest` 가 이미 본다), 그리고 그 핸들러조차 못 잡는 경로의
 * 백스톱까지.
 *
 * `ApiErrorHandler` 는 세 겹이다(최종 수정 라운드 항목 1, 이어서 그 catch-all 이
 * 클라이언트 오류까지 503 으로 뭉개던 걸 바로잡은 두 번째 라운드).
 * 1) `ExplanationUnavailableException` → 503 `EXPLANATION_UNAVAILABLE`.
 * 2) `ResponseEntityExceptionHandler` 가 다루는 스프링 MVC 예외 계열(깨진 JSON,
 *    지원 안 하는 메서드/미디어 타입, 파라미터 누락 등) → 오버라이드한
 *    `handleExceptionInternal` 이 스프링이 계산한 4xx 상태 코드를 그대로 쓰고
 *    본문만 `{"code":"INVALID_REQUEST"}` 로 불투명하게 바꾼다. 아래 첫 번째
 *    테스트가 이 갈래를 본다(깨진 JSON → `HttpMessageNotReadableException`).
 *    503 을 주지 않는 이유: 같은 깨진 JSON 은 몇 번을 재시도해도 같은 이유로
 *    영원히 실패하므로, "나중에 다시 시도하라"는 뜻의 상태 코드를 주면 클라이언트
 *    쪽 실수를 서버 장애로 위장시켜 불필요한 재시도 폭풍을 부른다(실측: 처음
 *    버전은 이 경로도 뭉뚱그려 503 으로 냈다가 그게 틀렸다는 지적을 받고 나눴다).
 * 3) 그 밖의 모든 예외(진짜 예기치 못한 버그) → `onUnexpected` 가 503
 *    `EXPLANATION_UNAVAILABLE`. 아래 두 번째 테스트가 이 갈래를 본다 —
 *    `/test/boom`(이 클래스 안에서만 뜨는 테스트 전용 컨트롤러, 운영 코드에는
 *    이런 예외를 던지는 지점이 없다)이 던지는 평범한 `RuntimeException`으로
 *    재현한다. 프로덕션에서는 `ExplainController` 의 `mapper.readTree(...)` 가
 *    예상 못 한 입력에 걸릴 때 같은 모양의 실패가 난다.
 *
 * 이 셋 다 스프링의 기본 에러 컨트롤러(`/error`, `DefaultErrorAttributes`)까지
 * 가지 않고 `ExceptionHandlerExceptionResolver` 단계에서 바로 렌더링된다.
 * `application.yml` 의 `spring.web.error.*` 는 그래도 지운다 — `ApiErrorHandler`
 * 조차 못 잡는 경로(예: 핸들러 자체를 못 찾는 404, `Exception` 이 아닌
 * `Throwable`)가 기본 에러 컨트롤러로 떨어질 때의 방어선이다. 접두사가
 * `server.error` 가 아니라 `spring.web.error` 인 이유는 `application.yml` 의
 * 주석 참고(스프링 부트 4.1 에서 `ErrorProperties` 는 `ServerProperties` 가
 * 아니라 `WebProperties` 에 중첩된다).
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

    /**
     * `ApiErrorHandler.handleExceptionInternal` 갈래 — 스프링 MVC 예외 계열은
     * 4xx 를 유지하고, 몸통만 `{"code":"INVALID_REQUEST"}` 로 불투명해진다.
     * 503 이 아니다: 깨진 JSON 은 재시도해도 영원히 같은 이유로 실패하므로
     * "나중에 다시 시도하라"는 뜻의 상태 코드를 주면 안 된다.
     */
    @Test
    fun `깨진 JSON 요청은 4xx 로 남고 몸통만 불투명해진다`() {
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
        assertThat(body.fieldNames().asSequence().toList()).containsExactly("code")
        assertThat(body.get("code").asText()).isEqualTo("INVALID_REQUEST")
    }

    /**
     * `ApiErrorHandler.onUnexpected` 갈래 — 진짜 예기치 못한 예외는 여전히 503
     * `EXPLANATION_UNAVAILABLE` 이다. `/test/boom` 이 잡히지 않은
     * `RuntimeException`(메시지: "INTERNAL DETAIL THAT MUST NOT LEAK")을 던지면
     * 그 메시지는 삼켜지고, 클라이언트에게는 `ExplanationUnavailableException` 과
     * 완전히 같은 모양의 503 계약 본문만 간다.
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
