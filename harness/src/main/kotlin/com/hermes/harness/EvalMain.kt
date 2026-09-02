package com.hermes.harness

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.hermes.context.BundleLoader
import com.hermes.context.CitationValidator
import com.hermes.context.PromptAssembler
import com.hermes.explain.BackendFacts
import com.hermes.explain.ExplainOutcome
import com.hermes.explain.ExplanationService
import com.hermes.explain.Explained
import com.hermes.explain.Unavailable
import com.hermes.llm.AnthropicExplanationProvider
import com.hermes.llm.ExplanationProvider
import com.hermes.llm.OpenAiCompatibleExplanationProvider
import java.io.File
import kotlin.system.exitProcess

/**
 * 금지 행동 7종을 센다. 실제 API 를 부르므로 돈이 든다.
 *
 * 서버를 띄우지 않는다 — presentation 을 건너뛰고 application 층을 직접 부르므로,
 * 여기서 통과한 프롬프트 조립과 인용 검증이 운영에서 도는 것과 같은 코드다.
 *
 * 정규화(`FactsNormalizer`)와 판정(`ForbiddenBehaviours`)은 `server` 의 main
 * 소스셋에 있다 — 이 파일은 그 둘과 프로바이더를 엮기만 한다. 프로바이더에게
 * 보내는 factsJson 과 `ForbiddenBehaviours.check` 에 넘기는 factsJson 은 반드시
 * 같은 문자열이어야 한다 — 판정기가 `items`/`alternatives` 를 그 최상위에서
 * 읽으므로, 둘이 다른 모양을 보면 판정은 아무것도 검증하지 못한다.
 *
 *   ./gradlew eval --args="anthropic 5"
 *   ./gradlew eval --args="openrouter 5"
 *   ./gradlew eval --args="openai 5"
 *
 * openrouter 와 openai 는 같은 어댑터를 탄다 — 엔드포인트와 키만 다르고 본문
 * 조립은 한 곳이다. 프로바이더마다 다른 어댑터를 쓰면 측정되는 것이 모델인지
 * 요청 조립 방식인지 흐려지기 때문이다.
 */
/**
 * 빈 값을 없는 값과 같이 다룬다.
 *
 * `System.getenv(...) ?: error(...)` 는 null 만 거른다. 키를 넣다 만 파일은 빈
 * 문자열을 주고, 그건 그대로 프로바이더까지 가서 401 로 돌아온다 — 그 401 은
 * "키가 틀렸다"와 "키를 안 넣었다"를 구분해 주지 않아서, 원인을 찾는 데 시간이
 * 든다. 실제로 그 혼동이 한 번 있었다.
 */
private fun requireCredential(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("$name is not set (or is empty) — put it in .env at the repository root, or export it")

fun main(args: Array<String>) {
    val providerName = args.getOrNull(0) ?: "anthropic"
    val runs = args.getOrNull(1)?.toIntOrNull() ?: 5

    val bundle = BundleLoader.load()
    val provider: ExplanationProvider = when (providerName) {
        "anthropic" -> AnthropicExplanationProvider(AnthropicOkHttpClient.fromEnv())
        "openrouter" -> OpenAiCompatibleExplanationProvider.openRouter(
            apiKey = requireCredential("OPENROUTER_API_KEY"),
            model = System.getenv("OPENROUTER_MODEL") ?: "nvidia/nemotron-nano-9b-v2:free",
        )
        "openai" -> OpenAiCompatibleExplanationProvider.openAi(
            apiKey = requireCredential("OPENAI_API_KEY"),
            model = System.getenv("OPENAI_MODEL") ?: "gpt-4o-mini",
        )
        else -> error("unknown provider: $providerName (expected anthropic, openrouter, or openai)")
    }

    val service = ExplanationService(PromptAssembler(bundle), CitationValidator(bundle), provider)

    val fixture = ObjectMapper().readTree(File("harness/fixtures/course-explanation-request.json"))
    val courseUuid = fixture.get("courseUuid").asText()
    val factsJson = FactsNormalizer.normalize(fixture).toString()
    val facts = BackendFacts(courseUuid, factsJson)

    // 실행마다 그 실행에서 있었던 위반의 전체 다중집합을 모아 뒀다가, 루프가
    // 끝난 뒤 ViolationTally.aggregate 로 한 번에 집계한다 — "실행당 최대 1"과
    // "원시 발생 횟수"를 같은 자리에서 섞지 않기 위해서다(ViolationTallyTest 가
    // 그 구분을 직접 검증한다).
    val perRunViolations = mutableListOf<List<Behaviour>>()
    var explained = 0
    var unavailable = 0

    // 판정은 기본으로 꺼져 있다 — 켜면 실행당 LLM 호출이 두 번이 되어 비용이
    // 두 배다. JUDGE_MODEL 을 넣는 행위가 그 비용에 대한 동의다.
    val judge = System.getenv("JUDGE_MODEL")?.takeIf { it.isNotBlank() }?.let { model ->
        QualityJudge(OpenAiCompatibleJudgeProvider.openAi(requireCredential("OPENAI_API_KEY"), model))
    }
    val verdicts = mutableListOf<JudgeVerdict>()

    repeat(runs) { i ->
        when (val outcome: ExplainOutcome = service.explain(facts)) {
            is Explained -> {
                explained++
                val violations = ForbiddenBehaviours.check(outcome.explanation, factsJson, bundle)
                perRunViolations += violations.map { it.behaviour }
                println("[$i] explained — violations: ${violations.ifEmpty { "none" }}")

                // 본문과 인용을 찍는다. 이 표의 0% 는 "위반이 없다"가 아니라 "이
                // 일곱 검사가 보는 범위에서 안 걸렸다"는 뜻이고, 둘을 가르는 것은
                // 결국 사람이 문장을 읽는 일이다. 검사들이 못 보는 것이 실제로
                // 있다 — INVENTED_PLACE 는 궁/사/마을/골목길로 끝나는 이름만 보고,
                // TIME_OF_DAY_REASON 은 문장을 넘는 인과를 놓치며,
                // DEFERRED_DESTINATION 은 대명사만 쓴 회피를 못 잡는다. 숫자만
                // 보여 주고 본문을 감추면 그 한계가 통과로 읽힌다.
                outcome.explanation.explanation.lineSequence().forEach { println("      $it") }
                println("      └ 인용: ${outcome.explanation.citations.joinToString(", ")}")

                judge?.let { verdicts += it.judge(outcome.explanation, factsJson, bundle) }
                println()
            }
            is Unavailable -> {
                unavailable++
                // ExplanationService 는 인용이 유효할 때만 Explained 를 반환한다 —
                // 그래서 UNCITED_CLAIM 신호는 여기, Unavailable.reason 에 있다.
                // ForbiddenBehaviours.check 쪽의 UNCITED_CLAIM 분기는 이 경로에서는
                // 절대 실행되지 않는다(직접 단위 테스트에서만 닿는다).
                perRunViolations += if (ForbiddenBehaviours.unavailableReasonIndicatesUncitedClaim(outcome.reason)) {
                    listOf(Behaviour.UNCITED_CLAIM)
                } else {
                    emptyList()
                }
                println("[$i] unavailable — ${outcome.reason}")
            }
        }
    }

    val tally = ViolationTally.aggregate(perRunViolations, explained)

    println()
    println("provider    : ${provider.name}")
    println("runs        : $runs")
    println("explained   : $explained")
    println("unavailable : $unavailable")
    // rate 의 분모는 runs 가 아니라 explained 다 — Refused/Failed/인용 무효로 끝난
    // 실행은 점검할 설명이 없어 분모에 넣으면 위반율이 희석된다(ViolationTally 문서
    // 참고). explained == 0 이면 rate() 가 null 을 내므로 "0%"가 아니라 명시적으로
    // "측정 불가"라고 찍는다 — 그렇지 않으면 "위반 없음"과 "잴 수 없음"이 같은
    // 숫자로 보인다.
    println("violations  : rate = runs-with-violation / explained (NOT /runs); occurrences = raw count (may exceed explained for INVENTED_PLACE)")
    Behaviour.entries.forEach { behaviour ->
        val runsCount = tally.runsWithViolation.getValue(behaviour)
        val occurrenceCount = tally.occurrences.getValue(behaviour)
        val rateLabel = when (val rate = tally.rate(behaviour)) {
            null -> "rate=UNMEASURED(explained=0)"
            else -> "rate=%.1f%%(%d/explained=%d)".format(rate * 100, runsCount, explained)
        }
        println("  ${behaviour.name.padEnd(22)} $rateLabel".padEnd(60) + "occurrences=$occurrenceCount")
    }

    // 판정 결과는 위 표와 **합치지 않는다.** 위는 결정론적이라 같은 입력에 같은
    // 답을 내고, 아래는 모델이 내는 의견이라 실행마다 달라질 수 있다. 둘을 한
    // 숫자로 묶으면 그 숫자는 재현되지 않으면서 재현되는 것처럼 보인다.
    if (judge == null) {
        println()
        println("quality     : 판정 안 함 (JUDGE_MODEL 미설정 — 켜면 실행당 LLM 호출이 2배가 된다)")
    } else {
        val notJudged = verdicts.filterIsInstance<NotJudged>()
        val all = verdicts.filterIsInstance<Judged>().flatMap { it.findings }
        // 인용문이 본문에 없는 지적은 무엇을 고칠지 가리키지 못한다. 버리지 않고
        // 따로 센다 — 실제 지적과 섞으면 개수만 부풀고, 감추면 판정자가 헛도는
        // 것을 알 수 없다.
        val (findings, unanchored) = all.partition { it.evidenceFound }

        println()
        println("── 품질 판정 (LLM · 위 표와 별개, 차단하지 않음) ──")
        println("judge model : ${System.getenv("JUDGE_MODEL")}")
        // "지적 없음"과 "판정 불가"를 절대 같은 줄에 두지 않는다 — 판정이 멈춘
        // 것을 깨끗한 결과로 읽으면 이 축이 있는 의미가 없어진다.
        println("판정함      : ${verdicts.size - notJudged.size}/${verdicts.size}")
        if (notJudged.isNotEmpty()) {
            println("판정 불가   : ${notJudged.size} — ${notJudged.map { it.reason }.distinct().joinToString("; ")}")
        }
        if (unanchored.isNotEmpty()) {
            println("확인 불가   : ${unanchored.size} — 인용문이 본문에 없다(판정자가 요약했거나 자리표시자를 냈다)")
        }
        if (findings.isEmpty()) {
            println("지적        : 없음")
        } else {
            QualityIssue.entries.forEach { issue ->
                val count = findings.count { it.issue == issue }
                if (count > 0) println("  ${issue.name.padEnd(22)} $count")
            }
            println()
            // 지적은 인용문과 함께 찍는다. 개수만 보면 무엇을 고칠지 알 수 없고,
            // 이 프로젝트의 프롬프트 결함 셋은 전부 문장을 읽어서 고쳤다.
            findings.forEach { finding ->
                println("  [${finding.issue}] \"${finding.evidence}\"")
                println("      └ ${finding.why}")
            }
        }
    }

    if (explained == 0) {
        System.err.println()
        System.err.println("all $runs runs were unavailable — nothing was explained, no violation count is trustworthy")
        exitProcess(1)
    }
}
