package com.hermes.harness

/**
 * 품질 판정용 프로바이더 포트.
 *
 * `ExplanationProvider` 와 **분리한다.** 출력 스키마가 다르기 때문이다 — 설명은
 * `{explanation, citations}` 를 내고 판정은 지적 목록을 낸다. 기존 포트에 얹으면
 * 그 포트가 두 가지 일을 하게 되고, "같은 프롬프트·같은 검증 아래에서 모델만
 * 바꾼다"는 프로바이더 비교의 의미가 흐려진다.
 *
 * 응답을 파싱하지 않고 원문 그대로 돌려주는 이유: 파싱은 `QualityJudge` 가 맡아야
 * 가짜 프로바이더에 준비된 JSON 을 물려 파싱 규칙을 단위 테스트할 수 있다.
 */
interface JudgeProvider {
    val name: String

    fun judge(systemText: String, userText: String): JudgeResponse
}

sealed interface JudgeResponse

data class JudgeAnswered(val body: String) : JudgeResponse

data class JudgeFailed(val reason: String) : JudgeResponse
