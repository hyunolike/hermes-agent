package com.hermes.explain.presentation

import com.hermes.explain.ExplanationUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiErrorHandler {

    private val log = LoggerFactory.getLogger(ApiErrorHandler::class.java)

    @ExceptionHandler(ExplanationUnavailableException::class)
    fun onUnavailable(e: ExplanationUnavailableException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(mapOf("code" to "EXPLANATION_UNAVAILABLE"))

    /**
     * 그 밖의 모든 예외 — 예를 들어 `ExplainController` 의 `mapper.readTree(...)`
     * 가 예상 못 한 입력에 걸려 던지는 버그. 이 핸들러가 없으면 스프링의 기본
     * 에러 컨트롤러로 떨어져 `{"timestamp","status","error","path"}` 를 반환한다
     * — `spring.web.error.*` 가 세부 내용은 막아도, "실패는 언제나
     * `{"code":"EXPLANATION_UNAVAILABLE"}` 뿐"이라는 계약을 어긴다.
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
