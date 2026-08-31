package com.hermes.llm

import com.anthropic.models.messages.StructuredMessageCreateParams
import com.fasterxml.jackson.core.type.TypeReference

data class SystemBlockView(val text: String, val cacheTtl: String?)

data class OutputConfigView(val effort: String?, val schemaRequiredFields: List<String>)

data class RawParamsView(
    val model: String,
    val maxTokens: Long,
    val system: List<SystemBlockView>,
    val userText: String,
    val outputConfig: OutputConfigView,
)

fun StructuredMessageCreateParams<Explanation>.view(): RawParamsView {
    val body = this.rawParams

    val systemBlocks = body.system().orElseThrow().asTextBlockParams().map {
        SystemBlockView(
            text = it.text(),
            cacheTtl = it.cacheControl().orElse(null)?.ttl()?.orElse(null)?.asString(),
        )
    }

    val outputConfig = body.outputConfig().orElseThrow()
    val schemaRequiredFields = outputConfig.format().orElseThrow()
        .schema()._additionalProperties()["required"]
        ?.convert(object : TypeReference<List<String>>() {})
        ?: emptyList()

    return RawParamsView(
        model = body.model().asString(),
        maxTokens = body.maxTokens(),
        system = systemBlocks,
        userText = body.messages().last().content().asString(),
        outputConfig = OutputConfigView(
            effort = outputConfig.effort().orElse(null)?.asString(),
            schemaRequiredFields = schemaRequiredFields,
        ),
    )
}
