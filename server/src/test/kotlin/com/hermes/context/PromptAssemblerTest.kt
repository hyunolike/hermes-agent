package com.hermes.context

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PromptAssemblerTest {

    private val bundle = BundleLoader.load()

    @Test
    fun `system 문자열은 번들 원문을 그대로 담는다`() {
        assertThat(PromptAssembler(bundle).systemText).isEqualTo(bundle.raw)
    }

    @Test
    fun `같은 번들로 두 번 조립하면 바이트 단위로 같다`() {
        assertThat(PromptAssembler(bundle).systemText).isEqualTo(PromptAssembler(bundle).systemText)
    }

    @Test
    fun `조립이 번들에 없던 오늘 날짜를 끼워넣지 않는다`() {
        // 캐시 무효화 요인 중 가장 흔한 것이 조립 시점에 끼어드는 현재 시각이다.
        // 번들 안의 문서가 자기 frontmatter 에 날짜를 갖는 것은 정상이므로,
        // "오늘 날짜가 조립 과정에서 새로 생겼는가"만 본다.
        val today = java.time.LocalDate.now().toString()
        val assembled = PromptAssembler(bundle).systemText

        assertThat(assembled.windowed(today.length).count { it == today })
            .isEqualTo(bundle.raw.windowed(today.length).count { it == today })
    }

    @Test
    fun `캐시 가능 최소 접두사를 넘는 크기다`() {
        // Claude Opus 5 의 최소 캐시 가능 접두사는 512 토큰이다. 보수적으로
        // 1 토큰 = 4 바이트로 잡아도 번들은 이를 크게 넘어야 한다.
        assertThat(bundle.byteSize()).isGreaterThan(512 * 4)
    }
}
