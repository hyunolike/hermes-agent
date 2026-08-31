package com.hermes.context

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BundleLoaderTest {

    @Test
    fun `번들의 9개 문서를 선언된 순서대로 읽는다`() {
        val bundle = BundleLoader.load()

        assertThat(bundle.documents).hasSize(9)
        assertThat(bundle.documents.first().path).isEqualTo("concepts/travel-context-layer.md")
        // 서비스 프롬프트는 사용자 턴에 가장 가깝게 마지막에 온다
        assertThat(bundle.documents.last().path).isEqualTo("packages/hanjeok/prompt.md")
    }

    @Test
    fun `문서 내용이 마커를 포함하지 않는다`() {
        val bundle = BundleLoader.load()

        assertThat(bundle.documents).allSatisfy { doc ->
            assertThat(doc.content).doesNotContain("----- FILE:")
            assertThat(doc.content).isNotEmpty()
        }
    }

    @Test
    fun `두 번 적재해도 raw 문자열이 바이트 단위로 같다`() {
        // 캐시는 접두사 일치다. 적재가 비결정적이면 매 요청이 캐시 미스가 되고,
        // 그건 돈이 들되 아무 테스트도 실패시키지 않는다.
        assertThat(BundleLoader.load().raw).isEqualTo(BundleLoader.load().raw)
    }

    @Test
    fun `번들이 없으면 조용히 비지 않고 예외를 던진다`() {
        assertThatThrownBy { BundleLoader.load("/prompts/does-not-exist.txt") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("does-not-exist.txt")
    }

    @Test
    fun `문서 본문에 마커 형태의 줄이 섞여 있으면 조용히 나누지 않고 예외를 던진다`() {
        // packages/hanjeok/prompt.md의 인용은 나중 과제(CitationValidator)에서
        // bundle.paths()로 검증된다. 본문에 섞인 마커 형태의 줄이 조용히 문서를
        // 하나 더 만들어내면, 존재하지 않는 문서를 가리키는 조작된 경로가
        // "유효한" 인용 대상처럼 통과해 버린다.
        assertThatThrownBy { BundleLoader.load("/prompts/injected-marker-bundle.txt") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("docs/real.md")
    }
}
