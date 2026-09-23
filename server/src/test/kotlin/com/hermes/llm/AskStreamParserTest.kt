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

    /** 백슬래시와 16진수를 떼어 둔다 — 붙여 쓰면 도구 층이 실제 글자로 풀어 버린다. */
    private fun esc(hex: String) = "\\" + "u" + hex

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
        // 이스케이프를 전부 섞은 본문 — 원문 그대로의 문자와 유니코드 이스케이프를 함께
        // 담는다. 가는 원문 그대로 두 번, esc 로 만든 유니코드 이스케이프로 한 번 더,
        // 이모지는 서로게이트 쌍 이스케이프로 담아 모델이 실제로 보내는 두 경로(원문 문자,
        // \u 이스케이프)를 한 번에 검증한다.
        val emoji = esc("D83D") + esc("DE00")
        val raw =
            """{"citations":["a\"b.md","c\\d.md"],"explanation":"줄\n탭\t따옴\"역\\슬\/가가웃${esc("AC00")}${emoji}끝"}"""
        val expectedBody = "줄\n탭\t따옴\"역\\슬/가가웃가" + String(Character.toChars(0x1F600)) + "끝"
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
        // 유니코드 이스케이프를 한 글자씩 먹이면 \, u, 16진수 넉 자가 전부 따로따로
        // 조각으로 들어온다 — esc 로 만들어 그 경로를 실제로 태운다.
        val emoji = esc("D83D") + esc("DE00")
        val raw = """{"citations":["a.md"],"explanation":"가${esc("AC00")}${emoji}"}"""
        val (events, _) = run(*raw.map { it.toString() }.toTypedArray())

        assertThat(body(events)).isEqualTo("가가" + String(Character.toChars(0x1F600)))
    }

    @Test
    fun `서로게이트 쌍의 앞쪽 반만 담긴 본문 조각은 내지 않는다`() {
        // 이모지 앞쪽 반까지만 먹인 시점에 나온 본문 조각들이 짝 없는 서로게이트로 끝나면
        // 안 된다. 모델이 실제로 보내는 형태 그대로 유니코드 이스케이프로 앞쪽 반만 먹인다.
        val parser = AskStreamParser()
        val first = parser.feed("""{"citations":["a.md"],"explanation":"x${esc("D83D")}""")
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
    fun `모르는 최상위 키가 오면 나머지를 삼키고 미완으로 끝난다`() {
        // 지금은 도달하지 않는다(스키마가 strict) — 미래에 필드를 하나 늘리고 이
        // 파서를 안 고치면 이렇게 된다는 것을 고정해 둔다. 조용히 무시하고 나머지를
        // 파싱하는 대신 fail closed 로 끝나야, 그 실패가 "truncated response" 로
        // 진단 가능하게 드러난다.
        val (events, parser) = run(
            """{"unknownField":"x","citations":["a.md"],"explanation":"y"}""",
        )

        assertThat(events.filterIsInstance<CitationsClosed>()).isEmpty()
        assertThat(body(events)).isEmpty()
        assertThat(parser.complete).isFalse()
    }

    @Test
    fun `공백과 줄바꿈이 끼어도 된다`() {
        val (events, parser) = run("{\n  \"citations\" : [ \"a.md\" ] ,\n  \"explanation\" : \"x\"\n}")

        assertThat(citations(events)).containsExactly("a.md")
        assertThat(body(events)).isEqualTo("x")
        assertThat(parser.complete).isTrue()
    }
}
