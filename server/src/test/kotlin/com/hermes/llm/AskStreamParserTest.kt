package com.hermes.llm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AskStreamParserTest {

    /** 조각들을 먹이고 나온 이벤트를 전부 모은다. */
    private fun run(vararg chunks: String): Pair<List<ParseEvent>, AskStreamParser> {
        val parser = AskStreamParser()
        return chunks.flatMap { parser.feed(it) } to parser
    }

    /** 본문 조각을 이어 붙인다 — 조각 경계는 구현의 사정이지 결과가 아니다. */
    private fun body(events: List<ParseEvent>) =
        events.filterIsInstance<BodyText>().joinToString("") { it.text }

    private fun citations(events: List<ParseEvent>) =
        events.filterIsInstance<CitationsClosed>().single().citations

    @Test
    fun `인용이 먼저 오면 인용을 알린 뒤 본문을 알린다`() {
        val (events, parser) = run("""{"citations":["a.md","b.md"],"explanation":"안녕"}""")

        assertThat(events.first()).isEqualTo(CitationsClosed(listOf("a.md", "b.md")))
        assertThat(body(events)).isEqualTo("안녕")
        assertThat(parser.complete).isTrue()
    }

    @Test
    fun `본문이 먼저 오면 본문을 알린 뒤 인용을 알린다 — 이벤트는 스트림 순서를 지킨다`() {
        val (events, parser) = run("""{"explanation":"안녕","citations":["a.md"]}""")

        assertThat(events.first()).isInstanceOf(BodyText::class.java)
        assertThat(events.last()).isEqualTo(CitationsClosed(listOf("a.md")))
        assertThat(body(events)).isEqualTo("안녕")
        assertThat(parser.complete).isTrue()
    }

    @Test
    fun `모든 분할 위치에서 결과가 같다`() {
        // 이스케이프를 전부 섞은 본문. 가 은 "가", 😀 은 이모지 하나다.
        val raw = """{"citations":["a\"b.md","c\\d.md"],"explanation":"줄\n탭\t따옴\"역\\슬\/가가웃😀끝"}"""
        val expectedBody = "줄\n탭\t따옴\"역\\슬/가가웃😀끝"
        val expectedCitations = listOf("a\"b.md", "c\\d.md")

        for (cut in 0..raw.length) {
            val (events, parser) = run(raw.substring(0, cut), raw.substring(cut))
            assertThat(body(events)).describedAs("cut=$cut").isEqualTo(expectedBody)
            assertThat(citations(events)).describedAs("cut=$cut").isEqualTo(expectedCitations)
            assertThat(parser.complete).describedAs("cut=$cut").isTrue()
        }
    }

    @Test
    fun `한 글자씩 먹여도 결과가 같다`() {
        val raw = """{"citations":["a.md"],"explanation":"가가😀"}"""
        val (events, _) = run(*raw.map { it.toString() }.toTypedArray())

        assertThat(body(events)).isEqualTo("가가😀")
    }

    @Test
    fun `서로게이트 쌍의 앞쪽 반만 담긴 본문 조각은 내지 않는다`() {
        // 이모지 앞쪽 반까지만 먹인 시점에 나온 본문 조각들이 짝 없는 서로게이트로 끝나면 안 된다.
        val parser = AskStreamParser()
        val first = parser.feed("""{"citations":["a.md"],"explanation":"x\uD83D""")
        val firstBody = first.filterIsInstance<BodyText>().joinToString("") { it.text }

        assertThat(firstBody).isEqualTo("x")
        assertThat(firstBody.last().isHighSurrogate()).isFalse()
    }

    @Test
    fun `본문이 닫히지 않은 채 끝나면 complete 가 거짓이다`() {
        val (_, parser) = run("""{"citations":["a.md"],"explanation":"잘리""")

        assertThat(parser.complete).isFalse()
    }

    @Test
    fun `인용이 닫히지 않은 채 끝나면 인용 이벤트가 없고 complete 가 거짓이다`() {
        val (events, parser) = run("""{"citations":["a.md""")

        assertThat(events.filterIsInstance<CitationsClosed>()).isEmpty()
        assertThat(parser.complete).isFalse()
    }

    @Test
    fun `빈 인용 배열도 닫힘으로 알린다 — 판정은 검증기가 한다`() {
        val (events, _) = run("""{"citations":[],"explanation":"x"}""")

        assertThat(citations(events)).isEmpty()
    }

    @Test
    fun `공백과 줄바꿈이 끼어도 된다`() {
        val (events, parser) = run("{\n  \"citations\" : [ \"a.md\" ] ,\n  \"explanation\" : \"x\"\n}")

        assertThat(citations(events)).containsExactly("a.md")
        assertThat(body(events)).isEqualTo("x")
        assertThat(parser.complete).isTrue()
    }
}
