package com.hermes.context

sealed interface CitationResult

data object Valid : CitationResult

data class Invalid(val unknownPaths: List<String>) : CitationResult

/**
 * 응답의 citations 배열이 번들에 실재하는 문서만 가리키는지 본다.
 *
 * 이 검사는 테스트가 아니라 런타임 방어선이다. 화면의 인용 칩은 번들 사본을 열기
 * 때문에, 번들에 없는 경로가 통과하면 사용자는 404 를 보게 된다.
 */
class CitationValidator(private val bundle: Bundle) {

    fun validate(citations: List<String>): CitationResult {
        if (citations.isEmpty()) return Invalid(emptyList())

        val known = bundle.paths()
        val unknown = citations.distinct().filterNot { it in known }

        return if (unknown.isEmpty()) Valid else Invalid(unknown)
    }
}
