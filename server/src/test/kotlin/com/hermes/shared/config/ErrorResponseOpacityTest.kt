package com.hermes.shared.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType

/**
 * `application.yml` 의 `server.error.*` 가 실제로 새는 걸 막는지 확인한다.
 *
 * `ApiErrorHandler` 가 잡는 `ExplanationUnavailableException` 경로는
 * `ExplainControllerTest` 가 이미 본다(코드만, `reason` 없음). 여기서 보는 건
 * 그 핸들러가 잡지 않는 경로 — 깨진 JSON 요청 본문이 스프링 부트 기본
 * 에러 컨트롤러(BasicErrorController)로 떨어지는 경우다.
 *
 * MockMvc(WebEnvironment.MOCK)로는 이 경로를 실측할 수 없었다 — 컨테이너가
 * 없어 `sendError` 이후 `/error`로의 서블릿 포워드가 일어나지 않고 본문이
 * 그냥 비어버려, 무엇을 검증하는지 알 수 없는 거짓 통과가 나왔다(직접 확인함:
 * `response.contentAsString`이 빈 문자열이었다). 그래서 RANDOM_PORT 로 실제
 * 내장 톰캣을 띄우고 진짜 HTTP 응답을 받는다 — 루프백(localhost) 호출이라
 * "테스트가 네트워크나 API 키를 필요로 하면 안 된다"는 제약과는 무관하다.
 *
 * 외부 호출 없음 — 컨트롤러 메서드에 도달하기 전에 JSON 파싱이 깨지므로
 * CourseExplainer 는 호출되지 않는다(한적도 Anthropic 도 부르지 않는다).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["hermes.hanjeok.base-url=http://localhost:1", "ANTHROPIC_API_KEY=not-used-in-this-test"],
)
class ErrorResponseOpacityTest {

    @LocalServerPort
    var port: Int = 0

    private val rest = TestRestTemplate()

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
}
