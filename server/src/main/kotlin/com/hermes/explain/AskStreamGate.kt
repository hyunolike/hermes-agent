package com.hermes.explain

import com.hermes.context.CitationValidator
import com.hermes.context.Invalid
import com.hermes.context.Valid
import com.hermes.llm.BodyText
import com.hermes.llm.CitationsClosed
import com.hermes.llm.ParseEvent

sealed interface AskStreamEvent

data class CitationsEvent(val citations: List<String>) : AskStreamEvent

data class DeltaEvent(val text: String) : AskStreamEvent

data object DoneEvent : AskStreamEvent

/** 본문을 하나도 보내기 전의 실패. 이것이 오면 그 전에 DeltaEvent 는 0개다. */
data class UnavailableEvent(val reason: String) : AskStreamEvent

/** 본문을 보내기 시작한 뒤의 실패. 앞의 본문은 검증됐지만 문장이 미완이다. */
data class AbortedEvent(val reason: String) : AskStreamEvent

/**
 * 해독된 조각을 받아, 인용 검증을 통과한 본문만 내보낸다.
 *
 * **검증을 통과하지 않은 글자는 한 글자도 나가지 않는다.** 인용이 먼저 오면 그 자리에서
 * 검증하고 통과해야 본문을 흘린다. 본문이 먼저 오면 쥐고 있다가 인용이 오면 검증하고,
 * 통과하면 한 번에 내보내고 무효면 버린다. 필드 순서는 속도만 좌우하고 안전은 좌우하지
 * 못한다.
 *
 * 한 번 끝나면(무효, 실패, 완료) 이후 이벤트는 전부 무시한다.
 */
class AskStreamGate(
    private val validator: CitationValidator,
    private val emit: (AskStreamEvent) -> Unit,
) {
    private var validated = false
    private var closed = false
    private var deltas = 0
    private val held = StringBuilder()

    fun accept(event: ParseEvent) {
        if (closed) return
        when (event) {
            is CitationsClosed -> when (val result = validator.validate(event.citations)) {
                is Valid -> {
                    validated = true
                    emit(CitationsEvent(event.citations))
                    if (held.isNotEmpty()) {
                        send(held.toString())
                        held.clear()
                    }
                }
                is Invalid -> fail(invalidCitationReason(result))
            }
            is BodyText -> if (validated) send(event.text) else held.append(event.text)
        }
    }

    /** 프로바이더 스트림이 정상적으로 끝났을 때. 응답이 잘렸으면 실패로 닫는다. */
    fun finish(parseComplete: Boolean) {
        if (closed) return
        if (!parseComplete || !validated) {
            fail("truncated response")
            return
        }
        // 인용은 유효했는데 본문이 한 글자도 없었던 경우다. 그대로 DoneEvent 를 내면
        // 빈 설명이 "확정된 답"으로 화면에 박히고, 다음 질문의 history 에 answer: "" 로
        // 실려 간다. 블로킹 경로(SpringAiExplanationProvider)는 빈 본문을 Failed 로
        // 보므로, 여기서 통과시키면 두 경로가 같은 입력에 다르게 답한다.
        if (deltas == 0) {
            fail("empty answer")
            return
        }
        closed = true
        emit(DoneEvent)
    }

    fun fail(reason: String) {
        if (closed) return
        closed = true
        held.clear() // 방어적 정리 — closed 가드가 이미 held 를 다시 읽지 못하게 막지만, 나중에 그 가드가 사라져도 여기서 한 번 더 막는다.
        emit(if (deltas == 0) UnavailableEvent(reason) else AbortedEvent(reason))
    }

    private fun send(text: String) {
        deltas++
        emit(DeltaEvent(text))
    }
}
