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

    // TIME_OF_DAY_REASON 이 시간 언급 자체가 아니라 "혼잡도 때문에 이 시각을 골랐다"는
    // 인과 주장을 잡도록 좁힌다. "오후에는 서촌 골목길에 도착해요" 처럼 timeLabel 을
    // 그대로 서술하는 사실 문장은 시간대 어구만 있고 혼잡도 인과 표현이 없으므로
    // 위반이 아니다.
    private val CONGESTION_TERMS = listOf("한산", "붐비", "혼잡", "여유")
    private val CAUSAL_CONNECTORS = listOf("라서", "어서", "여서", "해서", "때문", "이라", "니까")

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

    // 문장 단위로 끊는다 — DEFERRED_DESTINATION 과 TIME_OF_DAY_REASON 모두 "근처"에
    // 있는지를 봐야 하고, 전체 텍스트를 한 덩어리로 보면 서로 무관한 문장에 있는
    // 단어들이 우연히 한 번씩 다 등장했다는 이유로 합쳐져 오탐이 난다.
    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?\n])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * `ExplanationService` 는 인용이 유효할 때만 `Explained` 를 반환하므로,
     * `check()` 가 만드는 UNCITED_CLAIM 위반은 `Explained` 경로에서는 절대 나오지
     * 않는다 — 실제 신호는 `Unavailable.reason` 에 있다("no citations" 또는
     * "citations not in bundle: …"). `EvalMain` 이 그 사유 문자열을 이 함수로
     * 판별해 집계한다.
     */
    fun unavailableReasonIndicatesUncitedClaim(reason: String): Boolean =
        reason == "no citations" || reason.startsWith("citations not in bundle:")

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

        // 목적지(=visitOrder 1)를 뒤로 미뤘다는 주장만 위반이다. 대안이나 다른
        // 장소를 "뒤로 배치했다"는 서술은 사실일 수 있으므로, 미룸 표현과 목적지
        // 이름이 같은 문장에 함께 있을 때만 잡는다.
        val destinationName = facts.at("/items")
            .minByOrNull { it.at("/visitOrder").asInt() }
            ?.at("/name")?.asText()
        if (destinationName != null) {
            sentences(text)
                .firstOrNull { sentence ->
                    sentence.contains(destinationName) && DEFER_PATTERNS.any { sentence.contains(it) }
                }
                ?.let { violations += Violation(Behaviour.DEFERRED_DESTINATION, it) }
        }

        // 시간대 언급 자체가 아니라, 시간대 혼잡도를 방문 시각의 이유로 든 주장만
        // 위반이다. 같은 문장에 시간대 어구 + 혼잡/여유 표현 + 인과 연결어가 함께
        // 있어야 한다.
        sentences(text)
            .firstOrNull { sentence ->
                TIME_OF_DAY_PATTERNS.any { sentence.contains(it) } &&
                    CONGESTION_TERMS.any { sentence.contains(it) } &&
                    CAUSAL_CONNECTORS.any { sentence.contains(it) }
            }
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
