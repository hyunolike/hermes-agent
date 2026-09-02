package com.hermes.context

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 같은 입력이 바이트 단위로 같은 `system` 문자열을 만드는가(스펙 §9).
 *
 * 이것은 스타일 문제가 아니라 **비용 문제**다. 프롬프트 캐시는 접두사 일치로
 * 동작하므로, `system` 이 요청마다 1바이트라도 흔들리면 캐시는 통째로 미스 나고
 * 18KB 를 매번 새로 계산한다. 그 비용은 조용히 오른다 — 응답은 멀쩡하고
 * 위반율도 그대로이며, 달라지는 것은 요금뿐이라 어떤 검사도 울리지 않는다.
 */
class PromptDeterminismTest {

    @Test
    fun `번들을 다시 읽어도 같은 바이트다`() {
        val first = PromptAssembler(BundleLoader.load()).systemText
        val second = PromptAssembler(BundleLoader.load()).systemText

        assertThat(first.toByteArray(Charsets.UTF_8))
            .isEqualTo(second.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `같은 조립기를 여러 번 읽어도 같다`() {
        val assembler = PromptAssembler(BundleLoader.load())

        assertThat(assembler.systemText).isEqualTo(assembler.systemText)
    }

    @Test
    fun `system 은 번들 원문 그대로다`() {
        // 조립기가 머리말이나 구분선을 덧붙이면, 화면이 보여 주는 근거와 모델이
        // 실제로 본 바이트가 갈라진다 — /agent/context 가 증명하려는 것이 무너진다.
        val bundle = BundleLoader.load()

        assertThat(PromptAssembler(bundle).systemText).isEqualTo(bundle.raw)
    }

    @Test
    fun `system 에 시각이나 실행마다 변하는 값이 없다`() {
        // 타임스탬프 한 줄이면 캐시는 영원히 미스다. 실제로 넣기 쉬운 자리라
        // (예: "생성 시각") 명시적으로 막는다.
        val systemText = PromptAssembler(BundleLoader.load()).systemText

        val year = java.time.Year.now().value.toString()
        assertThat(systemText).doesNotContain("generatedAt")
        assertThat(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""").containsMatchIn(systemText))
            .describedAs("ISO 타임스탬프가 system 에 있으면 캐시가 매 요청 미스 난다")
            .isFalse()
        assertThat(systemText.contains("$year-") && systemText.contains(java.time.LocalDate.now().toString()))
            .describedAs("오늘 날짜가 박히면 하루가 지날 때 캐시가 통째로 무효가 된다")
            .isFalse()
    }
}
