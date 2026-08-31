package com.hermes.llm

data class Explanation(val explanation: String, val citations: List<String>)

data class ProviderUsage(
    val cacheReadTokens: Long,
    val cacheCreationTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
)

sealed interface ProviderResult

data class Answered(val explanation: Explanation, val usage: ProviderUsage) : ProviderResult

/** HTTP 200 에 stop_reason=refusal. content 는 비어 있으므로 읽기 전에 갈라야 한다. */
data class Refused(val category: String?) : ProviderResult

data class Failed(val reason: String) : ProviderResult

/**
 * 프로바이더 교체 지점.
 *
 * 이 포트가 있는 이유는 하나다 — Anthropic 직접 호출과 OpenRouter 무료 티어를
 * **같은 프롬프트와 같은 검증** 아래에서 비교하기 위해서다. 비교가 서로 다른
 * 조립 경로를 타면 측정하는 것은 모델이 아니라 프롬프트가 된다.
 */
interface ExplanationProvider {
    val name: String

    fun explain(systemText: String, factsJson: String): ProviderResult
}
