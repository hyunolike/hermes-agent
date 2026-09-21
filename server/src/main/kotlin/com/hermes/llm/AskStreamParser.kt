package com.hermes.llm

sealed interface ParseEvent

/** 인용 배열이 닫혔다. 판정은 여기서 하지 않는다 — 규칙이 두 군데 생기면 갈라진다. */
data class CitationsClosed(val citations: List<String>) : ParseEvent

/** 본문 문자열에서 이스케이프를 푼 조각. 빈 조각은 내지 않는다. */
data class BodyText(val text: String) : ParseEvent

/**
 * 모델이 조각으로 보내는 `{"citations":[...],"explanation":"..."}` 를 해독한다.
 *
 * 스키마가 strict 라 키는 정확히 둘이고 순서만 모른다. 그래서 범용 JSON 파서가 아니라
 * 이 모양 하나만 아는 상태 기계로 둔다 — 범용 비동기 파서(Jackson)는 문자열이 **완성된
 * 뒤에야** 토큰을 내므로 본문을 흘리는 데 쓸 수 없다.
 *
 * 상태를 전부 필드에 두고 한 글자씩 처리하므로 조각 경계가 어디든 결과가 같다. 이벤트는
 * 스트림에 나타난 순서대로 낸다 — 본문 조각을 모아 두었다가 인용 이벤트 뒤에 내면 순서가
 * 뒤집힌다.
 */
class AskStreamParser {

    private enum class Where { OBJECT, AFTER_KEY, VALUE, ARRAY }

    private enum class Target { KEY, CITATION, BODY }

    private var where = Where.OBJECT
    private var key = ""
    private var inString = false
    private var target = Target.KEY
    private var escaping = false
    private var hex: StringBuilder? = null
    private var pendingHigh: Char? = null
    private val text = StringBuilder()
    private val citations = mutableListOf<String>()
    private var citationsClosed = false
    private var bodyClosed = false

    /** 두 필드가 모두 닫혔는가. 스트림이 끝났는데 거짓이면 응답이 잘린 것이다. */
    val complete: Boolean get() = citationsClosed && bodyClosed

    fun feed(chunk: String): List<ParseEvent> {
        val events = mutableListOf<ParseEvent>()
        val body = StringBuilder()
        for (c in chunk) {
            if (inString) stringChar(c, body) else structural(c, body, events)
        }
        flushBody(body, events)
        return events
    }

    private fun structural(c: Char, body: StringBuilder, events: MutableList<ParseEvent>) {
        when (where) {
            Where.OBJECT -> if (c == '"') startString(Target.KEY)
            Where.AFTER_KEY -> if (c == ':') where = Where.VALUE
            Where.VALUE -> when {
                key == "citations" && c == '[' -> where = Where.ARRAY
                key == "explanation" && c == '"' -> startString(Target.BODY)
            }
            Where.ARRAY -> when (c) {
                '"' -> startString(Target.CITATION)
                ']' -> {
                    // 앞서 모인 본문 조각을 먼저 낸다 — 이벤트가 스트림 순서를 지키도록.
                    flushBody(body, events)
                    citationsClosed = true
                    events += CitationsClosed(citations.toList())
                    where = Where.OBJECT
                }
            }
        }
    }

    private fun startString(t: Target) {
        inString = true
        target = t
        text.clear()
    }

    private fun stringChar(c: Char, body: StringBuilder) {
        val digits = hex
        when {
            digits != null -> {
                digits.append(c)
                if (digits.length == 4) {
                    hex = null
                    put(digits.toString().toInt(16).toChar(), body)
                }
            }
            escaping -> {
                escaping = false
                when (c) {
                    'u' -> hex = StringBuilder(4)
                    'n' -> put('\n', body)
                    't' -> put('\t', body)
                    'r' -> put('\r', body)
                    'b' -> put('\b', body)
                    'f' -> put('\u000C', body)
                    else -> put(c, body) // \"  \\  \/
                }
            }
            c == '\\' -> escaping = true
            c == '"' -> endString(body)
            else -> put(c, body)
        }
    }

    private fun put(ch: Char, body: StringBuilder) {
        if (target != Target.BODY) {
            text.append(ch)
            return
        }
        // 서로게이트 쌍의 앞쪽 반만 조각 끝에 내보내면 받는 쪽이 깨진 글자를 본다.
        // 뒤쪽 반이 올 때까지 쥐고 있는다.
        val high = pendingHigh
        when {
            high != null -> {
                pendingHigh = null
                body.append(high).append(ch)
            }
            ch.isHighSurrogate() -> pendingHigh = ch
            else -> body.append(ch)
        }
    }

    private fun endString(body: StringBuilder) {
        inString = false
        when (target) {
            Target.KEY -> {
                key = text.toString()
                where = Where.AFTER_KEY
            }
            Target.CITATION -> citations += text.toString()
            Target.BODY -> {
                pendingHigh?.let {
                    body.append(it)
                    pendingHigh = null
                }
                bodyClosed = true
                where = Where.OBJECT
            }
        }
    }

    private fun flushBody(body: StringBuilder, events: MutableList<ParseEvent>) {
        if (body.isEmpty()) return
        events += BodyText(body.toString())
        body.clear()
    }
}
