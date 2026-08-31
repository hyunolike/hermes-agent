package com.hermes.explain

import com.hermes.context.CitationValidator
import com.hermes.context.Invalid
import com.hermes.context.PromptAssembler
import com.hermes.context.Valid
import com.hermes.llm.Answered
import com.hermes.llm.Explanation
import com.hermes.llm.ExplanationProvider
import com.hermes.llm.Failed
import com.hermes.llm.Refused

sealed interface ExplainOutcome

data class Explained(val explanation: Explanation) : ExplainOutcome

/** 스펙 §8 — 설명이 없는 것은 안전한 실패다. 한적의 규칙 기반 문구가 남는다. */
data class Unavailable(val reason: String) : ExplainOutcome

class ExplanationService(
    private val assembler: PromptAssembler,
    private val validator: CitationValidator,
    private val provider: ExplanationProvider,
) {

    fun explain(facts: BackendFacts): ExplainOutcome =
        when (val result = provider.explain(assembler.systemText, facts.json)) {
            is Refused -> Unavailable("refusal (${result.category ?: "unknown"})")
            is Failed -> Unavailable(result.reason)
            is Answered -> when (val citations = validator.validate(result.explanation.citations)) {
                is Valid -> Explained(result.explanation)
                is Invalid -> Unavailable(
                    if (citations.unknownPaths.isEmpty()) {
                        "no citations"
                    } else {
                        "citations not in bundle: ${citations.unknownPaths.joinToString()}"
                    },
                )
            }
        }
}
