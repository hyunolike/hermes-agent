package com.hermes.llm

import com.anthropic.models.messages.StructuredMessageCreateParams

data class SystemBlockView(val text: String, val cacheTtl: String?)

data class RawParamsView(
    val model: String,
    val maxTokens: Long,
    val system: List<SystemBlockView>,
    val userText: String,
)

fun StructuredMessageCreateParams<Explanation>.view(): RawParamsView {
    val body = this.rawParams

    val systemBlocks = body.system().orElseThrow().asTextBlockParams().map {
        SystemBlockView(
            text = it.text(),
            cacheTtl = it.cacheControl().orElse(null)?.ttl()?.orElse(null)?.asString(),
        )
    }

    return RawParamsView(
        model = body.model().asString(),
        maxTokens = body.maxTokens(),
        system = systemBlocks,
        userText = body.messages().last().content().asString(),
    )
}
