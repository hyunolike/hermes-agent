package com.hermes.harness

import com.fasterxml.jackson.databind.ObjectMapper
import com.hermes.context.Bundle
import com.hermes.llm.Explanation

/**
 * 판정이 물을 수 있는 것. 일곱 규칙 검사가 결정론적으로 잡는 것은 **여기 없다** —
 * 같은 사실을 두 곳에서 물으면 서로 다른 답이 나올 수 있고, 그러면 어느 쪽을
 * 믿어야 할지가 새 문제가 된다.
 *
 * 처음에는 네 가지를 물었다. 측정이 둘을 걸러 냈다.
 * - 등급 표기는 틀린 어휘가 유한해 문자열로 결정되므로 규칙으로 내렸다
 *   (`Behaviour.GRADE_MISLABEL`). 판정에 맡겼을 때 판정자는 올바른 표기("보통")를
 *   두고 "'NORMAL' 로 써야 한다"고 방향을 뒤집어 3회 실행에서 7건을 오탐했다.
 * - "인용한 문서를 실제로 썼는가"는 gpt-4o-mini 와 gpt-4o 둘 다 오탐만 냈다
 *   (8건 전부, 일부는 영어로 답했다). 인용 문서 본문을 함께 넘겨도 마찬가지였다.
 *   대답할 수 없는 질문을 남겨 두면 판정자는 답을 지어낸다.
 */
enum class QualityIssue {
    /** 비문, 미번역 용어, 뜻이 통하지 않는 표현. */
    UNREADABLE,

    /** facts 에 근거가 없는 주장 — 특히 형용. */
    UNSUPPORTED_CLAIM,
}

/**
 * @param evidenceFound 인용문이 설명 본문에 실제로 있는가. 판정자가 `"..."` 같은
 *   자리표시자나 요약한 문장을 evidence 로 내는 일이 있다 — 그런 지적은 무엇을
 *   고칠지 가리키지 못하므로 실제 지적과 같은 자리에 세면 개수가 부풀려진다.
 *   조용히 버리지는 않는다. 판정자가 헛도는 것도 알아야 할 사실이다.
 */
data class QualityFinding(
    val issue: QualityIssue,
    val evidence: String,
    val why: String,
    val evidenceFound: Boolean,
)

sealed interface JudgeVerdict

/** 지적 있음(비어 있지 않음) 또는 지적 없음(비어 있음). */
data class Judged(val findings: List<QualityFinding>) : JudgeVerdict

/**
 * 판정 불가. **"지적 없음" 과 같은 자리에 두지 않는다** — 둘을 뭉개면 판정이
 * 사실상 멈춘 상태인데도 결과가 깨끗해 보인다.
 */
data class NotJudged(val reason: String) : JudgeVerdict

/**
 * 규칙으로 셀 수 없는 것만 LLM 에게 묻는다.
 *
 * 점수가 아니라 인용문이 붙은 지적을 받는다. `faithfulness 0.73` 으로는 무엇을
 * 고칠지 알 수 없지만, 걸린 문장과 이유가 있으면 프롬프트를 고칠 수 있다 — 실제로
 * 이 프로젝트의 프롬프트 결함 셋은 전부 본문을 읽고 고쳤다.
 *
 * 차단하지 않는다. 하네스 전용이고 서버 런타임 경로에 들어가지 않는다 —
 * 비결정적 검사가 응답을 막으면 같은 요청이 날마다 다르게 동작한다.
 */
class QualityJudge(private val provider: JudgeProvider) {

    fun judge(explanation: Explanation, factsJson: String, bundle: Bundle): JudgeVerdict {
        val response = provider.judge(INSTRUCTIONS, buildUserText(explanation, factsJson, bundle))

        val body = when (response) {
            is JudgeFailed -> return NotJudged(response.reason)
            is JudgeAnswered -> response.body
        }

        return parse(body, explanation.explanation)
    }

    private fun parse(body: String, explanationText: String): JudgeVerdict {
        val root = try {
            MAPPER.readTree(body)
        } catch (e: Exception) {
            return NotJudged("판정 응답이 JSON 이 아니다: ${e::class.simpleName}")
        }

        val findings = root.get("findings")
            ?: return NotJudged("판정 응답에 findings 가 없다")
        if (!findings.isArray) return NotJudged("findings 가 배열이 아니다")

        val parsed = mutableListOf<QualityFinding>()
        for (node in findings) {
            val raw = node.path("issue").asText("")
            // 모르는 값을 조용히 버리면 판정이 절반만 작동하는데 결과는 깨끗해 보인다.
            val issue = QualityIssue.entries.firstOrNull { it.name == raw }
                ?: return NotJudged("모르는 issue 값: '$raw'")
            val evidence = node.path("evidence").asText("")
            parsed += QualityFinding(
                issue = issue,
                evidence = evidence,
                why = node.path("why").asText(""),
                evidenceFound = evidence.isNotBlank() && explanationText.contains(evidence),
            )
        }
        return Judged(parsed)
    }

    private fun buildUserText(explanation: Explanation, factsJson: String, bundle: Bundle): String =
        buildString {
            appendLine("## 검사할 설명")
            appendLine(explanation.explanation)
            appendLine()
            appendLine("## 이 설명이 단 인용")
            explanation.citations.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## 설명이 근거로 받은 사실")
            appendLine(factsJson)
            appendLine()
            // 인용한 문서의 **본문**을 함께 넘긴다. 판정자가 본문의 주장을 사실과
            // 대조할 때 도메인 용어의 뜻을 알아야 하고, 그 뜻은 인용된 문서에 있다.
            // 인용한 것만 넣으므로 번들 전체(18KB)를 매 판정마다 보내지 않는다.
            appendLine("## 인용한 문서의 내용")
            explanation.citations.forEach { path ->
                val document = bundle.document(path)
                appendLine("### $path")
                appendLine(document?.content ?: "(번들에 없는 경로)")
                appendLine()
            }
        }

    private companion object {
        private val MAPPER = ObjectMapper()

        /**
         * 일곱 규칙 검사가 보는 것은 여기서 묻지 않는다. 지적할 게 없으면 빈 배열을
         * 내라고 명시한다 — 판정자가 무언가는 찾아야 한다고 느끼면 없는 문제를 만든다.
         */
        private val INSTRUCTIONS = """
            당신은 한국어 여행 코스 설명을 검사한다. 사실 왜곡은 이미 별도의 결정론적
            검사가 잡고 있으므로 여기서는 묻지 않는다. 아래 두 가지만 본다.

            - UNREADABLE — 문법적으로 틀린 문장("붐비는 날으로"), 한국어 문장에 섞인
              영어 단어, 뜻이 통하지 않는 표현
            - UNSUPPORTED_CLAIM — 주어진 사실에 근거가 없는 주장. 특히 "매력적인",
              "아름다운" 같은 형용은 사실 어디에도 대응하는 값이 없다

            지적 대상이 **아닌** 것:
            - 문체와 격식. 이 설명은 "~해요" 체로 친근하게 쓰기로 정해져 있다.
              공식적이지 않다는 이유로 지적하지 않는다.
            - 문장 길이, 더 나은 표현이 있다는 의견.
            - 등급 표기(여유·보통·혼잡·매우혼잡). 별도의 규칙 검사가 문자열로
              판정한다. "매우 붐빈다", "한산하다" 처럼 풀어 쓴 표현은 정상이다.
            - 인용을 실제로 썼는지 여부.

            판단 기준:
            - 문장이 자연스럽고 사실에 근거하면 지적하지 않는다.
            - 지적할 것이 없으면 빈 배열을 낸다. 억지로 찾지 않는다. 확신이 서지
              않으면 지적하지 않는다 — 잘못된 지적은 없는 지적보다 나쁘다.
            - 표현을 더 낫게 다듬을 수 있다는 이유로는 지적하지 않는다. 위 두 가지
              중 하나에 해당하는 결함일 때만 낸다.
            - evidence 는 본문에서 **글자 그대로** 옮긴다. 줄이거나 바꾸지 않고,
              말줄임표를 넣지 않는다. why 는 왜 문제인지를 한 문장으로 적는다.

            아래 형식의 JSON 만 답한다. 다른 말을 덧붙이지 않는다.

            {"findings":[{"issue":"UNREADABLE","evidence":"...","why":"..."}]}
        """.trimIndent()
    }
}
