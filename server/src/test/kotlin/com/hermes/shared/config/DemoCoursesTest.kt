package com.hermes.shared.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DemoCoursesTest {

    @Test
    fun `설정 문자열에서 uuid 와 라벨을 읽는다`() {
        val demos = DemoCourses.parse("aaa|매우혼잡 목적지, bbb|대안 없음, ccc|다른 날 추천")

        assertThat(demos.courses).hasSize(3)
        assertThat(demos.courses[0].uuid).isEqualTo("aaa")
        assertThat(demos.courses[1].label).isEqualTo("대안 없음")
    }

    @Test
    fun `빈 설정이면 비어 있다`() {
        assertThat(DemoCourses.parse("").courses).isEmpty()
    }

    @Test
    fun `라벨이 없으면 uuid 를 라벨로 쓴다`() {
        assertThat(DemoCourses.parse("aaa").courses[0].label).isEqualTo("aaa")
    }
}
