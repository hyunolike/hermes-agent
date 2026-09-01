package com.hermes.explain.presentation

import com.hermes.context.Bundle
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.HandlerMapping

data class ContextEntry(val path: String, val bytes: Int)

/**
 * 인용이 가리키는 곳.
 *
 * GitHub 링크를 쓰지 않는다 — 그건 설명이 근거한 문서가 아니라 *오늘의* 문서를
 * 연다. 서버는 이미 번들을 메모리에 들고 있으므로, 그것을 읽기 전용으로 내면
 * 설명과 근거가 같은 판본임이 보장된다(스펙 §5).
 */
@RestController
class ContextController(private val bundle: Bundle) {

    @GetMapping("/agent/context")
    fun list(): List<ContextEntry> =
        bundle.documents.map { ContextEntry(path = it.path, bytes = it.content.toByteArray(Charsets.UTF_8).size) }

    // 명시적으로 charset=UTF-8 을 붙인다 — StringHttpMessageConverter 의 기본
    // 문자셋은 UTF-8 이 아니라서, 붙이지 않으면 em dash 같은 비 ASCII 바이트가
    // 클라이언트에서 깨진다. "LLM 에 보낸 바이트 그대로" 라는 계약을 지키려면
    // 인코딩까지 명시해야 한다.
    @GetMapping("/agent/context/**", produces = ["${MediaType.TEXT_PLAIN_VALUE};charset=UTF-8"])
    fun document(request: HttpServletRequest): ResponseEntity<String> {
        val full = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE) as String
        val path = full.removePrefix("/agent/context/")

        // 번들 목록에 있는 경로만 존재한다. 정규화나 접두사 일치를 하지 않으므로
        // `..` 를 포함한 경로는 목록에 없어 그대로 404 가 된다.
        val document = bundle.document(path) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(document.content)
    }
}
