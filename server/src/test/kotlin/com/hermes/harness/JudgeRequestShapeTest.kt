package com.hermes.harness

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JudgeRequestShapeTest {

    private val mapper = ObjectMapper()

    private fun body() = mapper.readTree(
        OpenAiCompatibleJudgeProvider.buildBody("지시", "검사할 설명", "gpt-4o-mini"),
    )

    @Test
    fun `판정 스키마를 tool_choice 로 강제한다`() {
        // 지시만으로 JSON 을 요구하면 모델은 산문으로 답할 자유가 남고, 그때
        // QualityJudge 는 그것을 "판정 불가" 로 처리한다 — 즉 판정이 조용히 멈춘다.
        assertThat(body().at("/tool_choice/function/name").asText())
            .isEqualTo(OpenAiCompatibleJudgeProvider.TOOL_NAME)
    }

    @Test
    fun `tool_choice 가 가리키는 이름은 선언된 도구 이름과 같다`() {
        val response = body()

        assertThat(response.at("/tool_choice/function/name").asText())
            .isEqualTo(response.at("/tools/0/function/name").asText())
    }

    @Test
    fun `issue enum 목록이 파서가 아는 값과 일치한다`() {
        // 스키마가 파서보다 넓으면 모델이 낸 값이 "모르는 issue" 로 판정 불가가
        // 되고, 좁으면 파서에만 있는 지적 타입은 영영 나오지 않는다. 둘 다 판정이
        // 조용히 반쪽이 되는 경우라, 한 곳을 고치고 다른 곳을 잊는 것을 막는다.
        val declared = body().at("/tools/0/function/parameters/properties/findings/items/properties/issue/enum")
            .map { it.asText() }.sorted()

        assertThat(declared).isEqualTo(QualityIssue.entries.map { it.name }.sorted())
    }

    @Test
    fun `지적은 인용문과 이유를 함께 요구한다`() {
        // evidence 없이 issue 만 오면 무엇을 고칠지 알 수 없다 — 점수 대신 지적을
        // 받기로 한 이유가 사라진다.
        val required = body().at("/tools/0/function/parameters/properties/findings/items/required")
            .map { it.asText() }.sorted()

        assertThat(required).containsExactly("evidence", "issue", "why")
    }

    @Test
    fun `지시와 검사 대상을 서로 다른 역할로 보낸다`() {
        val response = body()

        assertThat(response.at("/messages/0/role").asText()).isEqualTo("system")
        assertThat(response.at("/messages/0/content").asText()).isEqualTo("지시")
        assertThat(response.at("/messages/1/role").asText()).isEqualTo("user")
        assertThat(response.at("/messages/1/content").asText()).isEqualTo("검사할 설명")
    }
}
