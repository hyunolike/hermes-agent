package com.hermes.explain.presentation

import com.hermes.explain.ExplanationUnavailableException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiErrorHandler {

    @ExceptionHandler(ExplanationUnavailableException::class)
    fun onUnavailable(e: ExplanationUnavailableException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(mapOf("code" to "EXPLANATION_UNAVAILABLE"))
}
