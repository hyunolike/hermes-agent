package com.hermes.explain.presentation

import com.hermes.explain.ExplanationUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * `ResponseEntityExceptionHandler` 를 상속하는 이유는 스프링 MVC 자신이 던지는
 * 예외 계열(깨진 JSON, 지원하지 않는 메서드/미디어 타입, 파라미터 누락 등 —
 * 정확히 그 부모 클래스의 `@ExceptionHandler(value = [HttpRequestMethodNotSupportedException::class,
 * HttpMediaTypeNotSupportedException::class, MissingServletRequestParameterException::class,
 * HttpMessageNotReadableException::class, ...])` 가 다루는 20 여 개 목록)를 이 프로젝트가
 * 일일이 나열하지 않고도 정확한 상태 코드(4xx)로 응답하기 위해서다 — 그 목록의
 * 모든 예외는 `org.springframework.web.ErrorResponse` 를 구현해 자기 상태 코드를
 * 스스로 들고 있고, 부모 클래스가 그 코드로 `handleExceptionInternal` 을 호출한다.
 */
@RestControllerAdvice
class ApiErrorHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(ApiErrorHandler::class.java)

    @ExceptionHandler(ExplanationUnavailableException::class)
    fun onUnavailable(e: ExplanationUnavailableException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(mapOf("code" to "EXPLANATION_UNAVAILABLE"))

    /**
     * 위 클래스 문서의 스프링 MVC 예외 계열이 전부 이 메서드로 착지한다. 원래
     * 몸통(`ProblemDetail` — 메시지, 상세 등을 담는다)은 버리고 이 프로젝트 계약대로
     * 불투명한 `{"code":"INVALID_REQUEST"}` 만 담는다 — 상태 코드는 스프링이 이미
     * 계산한 값(부모가 넘겨주는 `statusCode`, 예: 깨진 JSON 은 400, 지원 안 하는
     * 메서드는 405)을 그대로 쓴다.
     *
     * 처음엔 이 계열도 `@ExceptionHandler(Exception::class)` 하나로 뭉뚱그려 503
     * `EXPLANATION_UNAVAILABLE` 로 냈었다 — 틀렸다. 503 은 "지금은 안 되니 나중에
     * 다시 시도하라"는 뜻인데 깨진 JSON 은 몇 번을 재시도해도 같은 이유로 영원히
     * 실패한다. 클라이언트 쪽 실수를 서버 장애로 위장시키면 불필요한 재시도 폭풍을
     * 부르고, 진짜 백엔드 장애와 구분도 안 된다 — 그래서 이 계열은 자기 4xx 를
     * 유지하고, 진짜 예기치 못한 실패만 아래 `onUnexpected` 에서 503 을 낸다.
     */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        log.warn("client request rejected: {} ({})", ex::class.simpleName, statusCode)
        val opaqueBody: Any = mapOf("code" to "INVALID_REQUEST")
        return ResponseEntity.status(statusCode)
            .headers(headers)
            .body(opaqueBody)
    }

    /**
     * 클라이언트가 만든 잘못된 요청. `@ResponseStatus(BAD_REQUEST)` 만으로는 부족하다 —
     * 아래 캐치올이 먼저 잡아 503 으로 바꿔 버린다(실측). 503 은 "지금은 안 되니 다시
     * 시도하라"는 뜻인데 빈 질문은 몇 번을 보내도 같은 이유로 실패한다.
     */
    @ExceptionHandler(InvalidAskRequestException::class)
    fun onInvalidAsk(e: InvalidAskRequestException): ResponseEntity<Map<String, String>> {
        log.warn("client request rejected: {}", e.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("code" to "INVALID_REQUEST"))
    }

    /**
     * 그 밖의 모든 예외 — 예를 들어 `ExplainController` 의 `mapper.readTree(...)`
     * 가 예상 못 한 입력에 걸려 던지는 버그. 위 두 핸들러(스프링 MVC 예외 계열,
     * `ExplanationUnavailableException`)에 안 걸리는, 진짜 예기치 못한 실패만
     * 여기로 온다 — 더 구체적인 핸들러가 항상 먼저 선택되기 때문이다. 이 핸들러가
     * 없으면 스프링의 기본 에러 컨트롤러로 떨어져 `{"timestamp","status","error","path"}`
     * 를 반환한다 — `spring.web.error.*` 가 세부 내용은 막아도, "설명을 만들지
     * 못하면 언제나 `{"code":"EXPLANATION_UNAVAILABLE"}` 뿐"이라는 계약을 어긴다.
     *
     * 원인은 지운다 — 클라이언트에게는 `ExplanationUnavailableException` 과 구분
     * 못 하게 같은 503 계약 본문만 준다. 대신 서버 로그에는 남긴다 — 안 그러면
     * 이런 버그가 조용히 삼켜져 진단할 수 없다.
     */
    @ExceptionHandler(Exception::class)
    fun onUnexpected(e: Exception): ResponseEntity<Map<String, String>> {
        log.error("unhandled exception reached the API boundary", e)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(mapOf("code" to "EXPLANATION_UNAVAILABLE"))
    }
}
