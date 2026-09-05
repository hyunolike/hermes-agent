package com.hermes.harness

import com.hermes.context.BundleLoader
import com.hermes.llm.Explanation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QualityJudgeTest {

    private val bundle = BundleLoader.load()

    private val factsJson = """
        {"items":[{"attractionId":1001,"name":"경복궁","visitOrder":1,"grade":"VERY_CROWDED"},
                  {"attractionId":1003,"name":"북촌 한옥마을","visitOrder":2,"grade":"NORMAL"}],
         "alternatives":[{"attractionId":1003,"name":"북촌 한옥마을","grade":"NORMAL","score":0.704}],
         "congestion":{"percentile":92,"grade":"VERY_CROWDED"}}
    """.trimIndent()

    private class FakeProvider(private val response: JudgeResponse) : JudgeProvider {
        override val name = "fake"
        var lastSystemText: String? = null
        var lastUserText: String? = null
        override fun judge(systemText: String, userText: String): JudgeResponse {
            lastSystemText = systemText
            lastUserText = userText
            return response
        }
    }

    private fun explanation(text: String, vararg citations: String) =
        Explanation(text, citations.toList().ifEmpty { listOf("concepts/congestion-diagnosis.md") })

    @Test
    fun `지적이 없으면 빈 목록으로 판정된다`() {
        val judge = QualityJudge(FakeProvider(JudgeAnswered("""{"findings":[]}""")))

        val verdict = judge.judge(explanation("경복궁은 이 날 매우 붐빕니다."), factsJson, bundle)

        assertThat(verdict).isInstanceOf(Judged::class.java)
        assertThat((verdict as Judged).findings).isEmpty()
    }

    @Test
    fun `지적은 인용문과 이유를 함께 싣는다`() {
        // 점수가 아니라 지적이어야 하는 이유가 여기 있다 — "0.73" 으로는 무엇을
        // 고칠지 알 수 없지만, 걸린 문장과 이유가 있으면 프롬프트를 고칠 수 있다.
        val judge = QualityJudge(
            FakeProvider(
                JudgeAnswered(
                    """{"findings":[
                       {"issue":"UNREADABLE","evidence":"매우 붐비는 날으로",
                        "why":"'날으로' 는 한국어 어형이 아니다"}]}""",
                ),
            ),
        )

        val verdict = judge.judge(explanation("경복궁은 매우 붐비는 날으로 보입니다."), factsJson, bundle)

        val findings = (verdict as Judged).findings
        assertThat(findings).hasSize(1)
        assertThat(findings[0].issue).isEqualTo(QualityIssue.UNREADABLE)
        assertThat(findings[0].evidence).isEqualTo("매우 붐비는 날으로")
        assertThat(findings[0].why).contains("한국어")
    }

    @Test
    fun `본문에 없는 인용문은 지적으로 세지 않도록 표시한다`() {
        // gpt-4o 가 evidence 로 "..." 를 낸 적이 있다. 그런 지적은 무엇을 고칠지
        // 가리키지 못하는데, 실제 지적과 같이 세면 개수만 부풀어 오른다.
        val judge = QualityJudge(
            FakeProvider(JudgeAnswered("""{"findings":[{"issue":"UNREADABLE","evidence":"...","why":"..."}]}""")),
        )

        val findings = (judge.judge(explanation("경복궁은 붐빕니다."), factsJson, bundle) as Judged).findings

        // 버리지는 않는다 — 판정자가 헛도는 것도 알아야 할 사실이다.
        assertThat(findings).hasSize(1)
        assertThat(findings[0].evidenceFound).isFalse()
    }

    @Test
    fun `본문에 그대로 있는 인용문은 확인된 것으로 표시한다`() {
        val judge = QualityJudge(
            FakeProvider(
                JudgeAnswered("""{"findings":[{"issue":"UNREADABLE","evidence":"붐비는 날으로","why":"비문"}]}"""),
            ),
        )

        val findings = (judge.judge(explanation("경복궁은 매우 붐비는 날으로 보입니다."), factsJson, bundle) as Judged).findings

        assertThat(findings[0].evidenceFound).isTrue()
    }

    @Test
    fun `판정 실패는 지적 없음이 아니라 판정 불가다`() {
        // 이 프로젝트에서 반복해 값을 한 규칙이다 — "위반 없음" 과 "잴 수 없음" 을
        // 같은 자리에 두면, 측정이 멈춘 것을 아무도 모른다.
        val judge = QualityJudge(FakeProvider(JudgeFailed("http 429")))

        val verdict = judge.judge(explanation("아무 말."), factsJson, bundle)

        assertThat(verdict).isInstanceOf(NotJudged::class.java)
        assertThat((verdict as NotJudged).reason).contains("429")
    }

    @Test
    fun `응답이 스키마에 맞지 않아도 판정 불가다`() {
        // 산문으로 답하거나 findings 가 없는 응답을 "지적 없음" 으로 읽으면,
        // 판정이 사실상 작동하지 않는데도 깨끗해 보인다.
        val judge = QualityJudge(FakeProvider(JudgeAnswered("이 설명은 괜찮아 보입니다.")))

        val verdict = judge.judge(explanation("아무 말."), factsJson, bundle)

        assertThat(verdict).isInstanceOf(NotJudged::class.java)
    }

    @Test
    fun `JSON 이지만 findings 가 없는 응답도 판정 불가다`() {
        // 위 테스트는 산문 응답만 본다 — 파싱이 먼저 터지므로 findings 누락 분기에는
        // 닿지 않는다. 스키마를 반만 지킨 응답(JSON 이되 findings 가 없는)을
        // "지적 없음" 으로 읽으면, 판정이 멈춘 것이 깨끗한 결과로 보인다.
        val judge = QualityJudge(FakeProvider(JudgeAnswered("""{"verdict":"ok"}""")))

        assertThat(judge.judge(explanation("아무 말."), factsJson, bundle))
            .isInstanceOf(NotJudged::class.java)
    }

    @Test
    fun `findings 가 배열이 아니면 판정 불가다`() {
        val judge = QualityJudge(FakeProvider(JudgeAnswered("""{"findings":"없음"}""")))

        assertThat(judge.judge(explanation("아무 말."), factsJson, bundle))
            .isInstanceOf(NotJudged::class.java)
    }

    @Test
    fun `모르는 issue 값은 버리지 않고 판정 불가로 만든다`() {
        // 조용히 걸러내면 판정이 절반만 작동하는데 결과는 깨끗해 보인다.
        val judge = QualityJudge(
            FakeProvider(JudgeAnswered("""{"findings":[{"issue":"VIBES","evidence":"x","why":"y"}]}""")),
        )

        assertThat(judge.judge(explanation("아무 말."), factsJson, bundle))
            .isInstanceOf(NotJudged::class.java)
    }

    @Test
    fun `판정에 넘기는 자료가 설명과 인용과 사실을 모두 담는다`() {
        // 근거 없는 주장을 물으려면 판정자가 본문과 facts 를 함께 봐야 한다.
        // 하나라도 빠지면 그 질문은 대답할 수 없는 질문이 되고, 판정자는 대답할 수
        // 없는 질문에 대해서도 답을 지어낸다.
        val provider = FakeProvider(JudgeAnswered("""{"findings":[]}"""))
        QualityJudge(provider).judge(
            explanation("경복궁은 붐빕니다.", "concepts/congestion-diagnosis.md"),
            factsJson,
            bundle,
        )

        val sent = provider.lastUserText!!
        assertThat(sent).contains("경복궁은 붐빕니다.")
        assertThat(sent).contains("concepts/congestion-diagnosis.md")
        assertThat(sent).contains("\"percentile\"")
    }

    @Test
    fun `인용한 문서의 본문을 함께 넘긴다`() {
        // 본문의 주장을 사실과 대조하려면 도메인 용어의 뜻을 알아야 하고, 그 뜻은
        // 인용된 문서에 있다("혼잡 진단", "대안 점수" 가 무엇을 세는 말인지).
        val provider = FakeProvider(JudgeAnswered("""{"findings":[]}"""))
        QualityJudge(provider).judge(
            explanation("경복궁은 붐빕니다.", "concepts/congestion-diagnosis.md"),
            factsJson,
            bundle,
        )

        val cited = bundle.document("concepts/congestion-diagnosis.md")!!.content
        assertThat(provider.lastUserText!!).contains(cited)
    }

    @Test
    fun `인용하지 않은 문서까지 넘기지는 않는다`() {
        // 번들 전체를 넣으면 판정 호출마다 18KB 를 더 보내게 된다. 본문이 실제로
        // 기댄 개념은 인용된 문서 안에 있다.
        val provider = FakeProvider(JudgeAnswered("""{"findings":[]}"""))
        QualityJudge(provider).judge(
            explanation("경복궁은 붐빕니다.", "concepts/congestion-diagnosis.md"),
            factsJson,
            bundle,
        )

        val notCited = bundle.paths().first { it != "concepts/congestion-diagnosis.md" }
        assertThat(provider.lastUserText!!).doesNotContain(bundle.document(notCited)!!.content)
    }

    @Test
    fun `판정 지시는 규칙 검사가 이미 보는 것을 다시 묻지 않는다`() {
        // 여덟 규칙이 결정론적으로 잡는 것을 판정에게 또 물으면, 같은 사실이
        // 두 곳에서 서로 다른 답으로 나올 수 있다. 등급 표기가 실제로 그랬다 —
        // 판정자는 올바른 표기를 두고 뒤집어 지적했고, 규칙은 뒤집지 않는다.
        val provider = FakeProvider(JudgeAnswered("""{"findings":[]}"""))
        QualityJudge(provider).judge(explanation("아무 말."), factsJson, bundle)

        val instructions = provider.lastSystemText!!
        assertThat(instructions).doesNotContain("REORDERED_COURSE")
        assertThat(instructions).doesNotContain("DEFERRED_DESTINATION")
        assertThat(instructions).doesNotContain("GRADE_MISLABEL")
    }
}
