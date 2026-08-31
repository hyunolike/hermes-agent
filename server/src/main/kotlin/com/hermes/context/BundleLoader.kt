package com.hermes.context

object BundleLoader {

    private val MARKER = Regex("^----- FILE: (.+) -----$", RegexOption.MULTILINE)

    fun load(resourcePath: String = "/prompts/hanjeok-bundle.txt"): Bundle {
        val raw = BundleLoader::class.java.getResource(resourcePath)
            ?.readText(Charsets.UTF_8)
            ?: error("bundle resource not found: $resourcePath")

        val markers = MARKER.findAll(raw).toList()
        check(markers.isNotEmpty()) { "bundle has no FILE markers: $resourcePath" }

        val documents = markers.mapIndexed { i, match ->
            val contentStart = match.range.last + 2 // 마커 줄의 개행 다음
            val contentEnd = if (i + 1 < markers.size) markers[i + 1].range.first else raw.length
            BundleDocument(
                path = match.groupValues[1],
                content = raw.substring(contentStart, contentEnd).trimEnd('\n'),
            )
        }

        return Bundle(documents = documents, raw = raw)
    }
}
