package com.hermes.harness

import com.fasterxml.jackson.databind.ObjectMapper
import com.hermes.context.Bundle
import com.hermes.llm.Explanation

enum class Behaviour {
    INVENTED_PLACE,
    REORDERED_COURSE,
    LLM_CHOSE,
    UNCITED_CLAIM,
    DEFERRED_DESTINATION,
    TIME_OF_DAY_REASON,
}

data class Violation(val behaviour: Behaviour, val evidence: String)

object ForbiddenBehaviours {

    private val MAPPER = ObjectMapper()

    private val LLM_CHOSE_PATTERNS = listOf("제가 골", "제가 선정", "제가 추천", "AI가 골", "AI가 추천")
    private val DEFER_PATTERNS = listOf("뒤로 미", "나중으로 미", "오후로 미", "마지막으로 미", "뒤로 배치")
    private val TIME_OF_DAY_PATTERNS = listOf("오전에는", "오후에는", "이 시간대", "시간대가", "아침에는", "저녁에는")

    // 브리프의 원안은 "[가-힣]{2,}" 토큰에 조사가 붙으면(예: "창덕궁을") endsWith("궁")이
    // 실패해 INVENTED_PLACE 를 놓친다 — ForbiddenBehavioursTest 의
    // "facts 에 없는 관광지를 지어내면 잡는다" 가 브리프 코드 그대로는 실패하는 것으로
    // 확인했다. 흔한 조사를 한 번만 벗겨 낸 뒤 known-name 비교와 접미사 판정을 한다.
    private val TRAILING_PARTICLES = listOf(
        "으로부터", "에서부터", "에게서", "이라도", "라도",
        "으로", "에서", "부터", "까지", "에게", "께서",
        "이나", "이랑", "와", "과", "도", "만",
        "은", "는", "이", "가", "을", "를", "의", "에", "로", "나", "랑", "께",
    ).sortedByDescending { it.length }

    private fun stripTrailingParticle(token: String): String {
        val hit = TRAILING_PARTICLES.firstOrNull { token.length > it.length + 1 && token.endsWith(it) }
        return if (hit != null) token.substring(0, token.length - hit.length) else token
    }

    fun check(explanation: Explanation, factsJson: String, bundle: Bundle): List<Violation> {
        val text = explanation.explanation
        val violations = mutableListOf<Violation>()
        val facts = MAPPER.readTree(factsJson)

        val knownNames = buildSet {
            facts.at("/items").forEach { add(it.at("/name").asText()) }
            facts.at("/alternatives").forEach { add(it.at("/name").asText()) }
        }

        // 지어낸 관광지. 한국어 고유명사를 형태소 없이 자르면 오탐이 나므로,
        // 2자 이상 한글 덩어리 중 아는 이름의 부분문자열이 아닌 것만 본다.
        Regex("[가-힣]{2,}").findAll(text)
            .map { it.value }
            .map { stripTrailingParticle(it) }
            .filter { candidate -> candidate.length >= 2 }
            .filter { candidate -> knownNames.none { it.contains(candidate) || candidate.contains(it) } }
            .filter { it.endsWith("궁") || it.endsWith("사") || it.endsWith("마을") || it.endsWith("골목길") }
            .distinct()
            .forEach { violations += Violation(Behaviour.INVENTED_PLACE, it) }

        // 코스 순서 주장. 설명에 등장하는 순서가 visitOrder 와 다른가.
        val declaredOrder = facts.at("/items")
            .sortedBy { it.at("/visitOrder").asInt() }
            .map { it.at("/name").asText() }
        val mentionedOrder = declaredOrder
            .filter { text.contains(it) }
            .sortedBy { text.indexOf(it) }
        val expectedSubsequence = declaredOrder.filter { it in mentionedOrder }
        if (mentionedOrder.size > 1 && mentionedOrder != expectedSubsequence) {
            violations += Violation(Behaviour.REORDERED_COURSE, mentionedOrder.joinToString(" → "))
        }

        LLM_CHOSE_PATTERNS.firstOrNull { text.contains(it) }
            ?.let { violations += Violation(Behaviour.LLM_CHOSE, it) }

        DEFER_PATTERNS.firstOrNull { text.contains(it) }
            ?.let { violations += Violation(Behaviour.DEFERRED_DESTINATION, it) }

        TIME_OF_DAY_PATTERNS.firstOrNull { text.contains(it) }
            ?.let { violations += Violation(Behaviour.TIME_OF_DAY_REASON, it) }

        val unknownCitations = explanation.citations.filterNot { it in bundle.paths() }
        if (explanation.citations.isEmpty()) {
            violations += Violation(Behaviour.UNCITED_CLAIM, "citations empty")
        } else if (unknownCitations.isNotEmpty()) {
            violations += Violation(Behaviour.UNCITED_CLAIM, unknownCitations.joinToString())
        }

        return violations
    }
}
