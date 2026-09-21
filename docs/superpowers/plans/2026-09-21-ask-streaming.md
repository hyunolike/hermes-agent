# 이어 묻기 스트리밍 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 이어 묻기 답변을 스트리밍하되, 인용 검증을 통과하지 않은 글자는 한 글자도 내보내지 않는다.

**Architecture:** `ExplanationProvider` 포트에 콜백형 `stream()` 을 더한다(기본 구현은 `explain()` 위임). 모델이 조각으로 보내는 JSON 을 `AskStreamParser` 가 해독하고, `AskStreamGate` 가 인용을 검증한 뒤에만 본문을 흘린다. `POST /agent/ask/stream` 이 그것을 SSE 로 내보내고, 프론트 `AskBox` 가 `fetch` + `ReadableStream` 으로 읽는다.

**Tech Stack:** Kotlin 2.2.21, Spring Boot 4.1.0 (webmvc, `SseEmitter`), Spring AI 2.0.1 (`ChatClient.stream()`, Reactor 는 어댑터 안에만), Next.js + React 19, vitest, zod

**Spec:** `docs/superpowers/specs/2026-09-21-ask-streaming-design.md`

## Global Constraints

- **검증을 통과하지 않은 본문은 한 글자도 나가지 않는다.** 인용 무효는 반드시 `unavailable` 로 끝나고, 그 전에 `delta` 는 0개다. 인용이 먼저 오든 본문이 먼저 오든 같다.
- **실패 이벤트는 불투명하다.** `unavailable` 은 `{"code":"EXPLANATION_UNAVAILABLE"}`, `aborted` 는 `{"code":"EXPLANATION_ABORTED"}` 만 싣는다. 사유는 서버 로그에만 남긴다(`ErrorResponseOpacityTest` 가 지키는 기존 계약).
- **`POST /agent/ask` 는 바꾸지 않는다.** 비스트리밍 계약과 하네스가 거기 걸려 있다.
- **요청 바이트는 그대로다.** 스트리밍 경로도 `PromptAssembler.systemText` 와 `CourseQuestionService` 의 `buildUserText` 를 그대로 쓴다. 1시간 프롬프트 캐시가 유지돼야 한다.
- **`ExplanationProvider` 에 Reactor 타입을 올리지 않는다.** 서비스 계층과 테스트 페이크가 Spring AI 없이 돌아야 한다.
- **인용 무효 사유 문자열은 기존과 한 글자도 다르지 않다** — `"no citations"`, `"citations not in bundle: ${paths.joinToString()}"`, `"refusal (${category ?: "unknown"})"`. 하네스의 `unavailableReasonIndicatesUncitedClaim` 이 이 문자열로 판별한다.
- **`./gradlew build` 는 자격 증명 없이 통과한다.** 유료 호출은 마지막 태스크의 선택 단계뿐이고, 사람 승인이 필요하다.
- **건드리지 않는다:** `server/src/main/resources/prompts/hanjeok-bundle.txt`, `server/src/main/kotlin/com/hermes/context/` 아래 전부(`CitationValidator` 포함), `ForbiddenBehaviours.kt`, `build.gradle.kts` 의 victools 핀.
- **커밋 메시지:** 영어 제목(`feat:`/`fix:`/`test:`/`refactor:`/`docs:`), 한국어 본문.

---

## File Structure

| 파일 | 책임 | 태스크 |
| --- | --- | --- |
| `server/src/main/kotlin/com/hermes/llm/ExplanationProvider.kt` | 포트에 `stream()` 과 `StreamEnd` 추가. 기본 구현은 `explain()` 위임 | 1 |
| `server/src/main/kotlin/com/hermes/llm/AskStreamParser.kt` | 조각 → `ParseEvent` 해독. 순수 상태 기계, 검증 안 함 | 2 |
| `server/src/main/kotlin/com/hermes/explain/AskStreamGate.kt` | `ParseEvent` → `AskStreamEvent`. **이 저장소의 약속이 사는 곳** | 3 |
| `server/src/main/kotlin/com/hermes/explain/CitationReasons.kt` | 인용 무효 → 사유 문자열. 두 서비스와 게이트가 공유 | 3 |
| `server/src/test/kotlin/com/hermes/llm/CapturingEndpoint.kt` | 미리 짠 응답을 돌려줄 수 있게 확장(기본값은 지금처럼 500) | 4 |
| `server/src/main/kotlin/com/hermes/llm/SpringAiExplanationProvider.kt` | `stream()` 을 실제 `ChatClient.stream()` 으로 재정의 | 4 |
| `server/src/main/kotlin/com/hermes/explain/CourseQuestionService.kt` | `askStream()` — 같은 `buildUserText` 로 조립 | 5 |
| `server/src/main/kotlin/com/hermes/explain/presentation/AskStreamController.kt` | `POST /agent/ask/stream`, `SseEmitter` | 6 |
| `server/src/main/kotlin/com/hermes/explain/presentation/AskStreamExecutor.kt` | 스트림 전용 실행기. 타입으로 구분해 빈 모호성을 피한다 | 6 |
| `server/src/main/kotlin/com/hermes/shared/config/HermesConfig.kt` | `AskStreamExecutor` 빈 | 6 |
| `frontend/src/lib/agent.ts` | `askCourseStream()` — SSE 판독기 | 7 |
| `frontend/src/app/course/[uuid]/AskBox.tsx` | 스트리밍 상태 | 8 |
| `README.md`, `README.ko.md` | 이어 묻기 절 갱신 | 9 |

---

### Task 1: 포트에 `stream()` 을 더한다

포트 구현체가 테스트에만 8개 있다(`FakeProvider`, `StubProvider`, `CountingProvider`, 익명 객체 2개, `Recorder`, `RecordingProvider`). 추상 메서드를 더하면 전부 컴파일이 깨진다. **기본 구현을 준다** — `explain()` 을 불러 결과 전체를 조각 하나로 낸다. 페이크는 그대로 컴파일되고, 같은 `explain()` 을 거치므로 프롬프트 조립이 갈라지지 않는다.

**Files:**
- Modify: `server/src/main/kotlin/com/hermes/llm/ExplanationProvider.kt`
- Test: `server/src/test/kotlin/com/hermes/llm/DefaultStreamTest.kt`

**Interfaces:**
- Consumes: 기존 `ExplanationProvider.explain`, `Answered`, `Refused`, `Failed`, `ProviderUsage`, `Explanation`
- Produces:
  - `sealed interface StreamEnd`
  - `data class StreamCompleted(val usage: ProviderUsage) : StreamEnd`
  - `data class StreamRefused(val category: String?) : StreamEnd`
  - `data class StreamFailed(val reason: String) : StreamEnd`
  - `ExplanationProvider.stream(systemText: String, userText: String, onChunk: (String) -> Unit): StreamEnd`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`server/src/test/kotlin/com/hermes/llm/DefaultStreamTest.kt`:

```kotlin
package com.hermes.llm

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 스트리밍하지 않는 프로바이더(테스트 페이크 포함)도 stream() 계약을 지켜야 한다.
 * 기본 구현은 explain() 결과를 모델이 보냈을 법한 JSON 한 조각으로 낸다.
 */
class DefaultStreamTest {

    private class OnlyExplain(private val result: ProviderResult) : ExplanationProvider {
        override val name = "only-explain"
        var seen: Pair<String, String>? = null
        override fun explain(systemText: String, userText: String): ProviderResult {
            seen = systemText to userText
            return result
        }
    }

    private val usage = ProviderUsage(1, 2, 3, 4)

    @Test
    fun `답변은 인용이 먼저인 JSON 한 조각으로 나온다`() {
        val provider = OnlyExplain(Answered(Explanation("본문", listOf("a.md", "b.md")), usage))
        val chunks = mutableListOf<String>()

        val end = provider.stream("sys", "user") { chunks += it }

        assertThat(chunks).hasSize(1)
        val json = ObjectMapper().readTree(chunks.single())
        assertThat(json.fieldNames().asSequence().toList()).containsExactly("citations", "explanation")
        assertThat(json["citations"].map { it.asText() }).containsExactly("a.md", "b.md")
        assertThat(json["explanation"].asText()).isEqualTo("본문")
        assertThat(end).isEqualTo(StreamCompleted(usage))
    }

    @Test
    fun `같은 system 과 user 텍스트가 explain 으로 그대로 간다`() {
        val provider = OnlyExplain(Answered(Explanation("x", listOf("a.md")), usage))

        provider.stream("시스템", "사용자") {}

        assertThat(provider.seen).isEqualTo("시스템" to "사용자")
    }

    @Test
    fun `거절은 조각 없이 StreamRefused 로 끝난다`() {
        val chunks = mutableListOf<String>()

        val end = OnlyExplain(Refused("violence")).stream("s", "u") { chunks += it }

        assertThat(chunks).isEmpty()
        assertThat(end).isEqualTo(StreamRefused("violence"))
    }

    @Test
    fun `실패는 조각 없이 StreamFailed 로 끝나고 사유를 보존한다`() {
        val chunks = mutableListOf<String>()

        val end = OnlyExplain(Failed("IOException: reset")).stream("s", "u") { chunks += it }

        assertThat(chunks).isEmpty()
        assertThat(end).isEqualTo(StreamFailed("IOException: reset"))
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew --no-daemon test --tests '*DefaultStreamTest*'`
Expected: FAIL — `stream`, `StreamCompleted` 등이 없어 컴파일 에러

- [ ] **Step 3: 포트를 확장한다**

`server/src/main/kotlin/com/hermes/llm/ExplanationProvider.kt` 에서 파일 맨 위 `package` 줄 아래에 import 를, `Failed` 선언 아래에 `StreamEnd` 계층을 더하고, 인터페이스에 `stream()` 을 더한다:

```kotlin
import com.fasterxml.jackson.databind.ObjectMapper
```

```kotlin
/** stream() 이 어떻게 끝났는가. 조각은 onChunk 로 이미 나갔다. */
sealed interface StreamEnd

data class StreamCompleted(val usage: ProviderUsage) : StreamEnd

data class StreamRefused(val category: String?) : StreamEnd

data class StreamFailed(val reason: String) : StreamEnd

private val STREAM_MAPPER = ObjectMapper()
```

인터페이스 본문을 아래로 바꾼다(기존 KDoc 은 유지):

```kotlin
interface ExplanationProvider {
    val name: String

    fun explain(systemText: String, userText: String): ProviderResult

    /**
     * 모델이 조각을 보낼 때마다 onChunk 를 부른다. 조각은 모델이 낸 JSON 텍스트 그대로다 —
     * 해독은 AskStreamParser 가, 검증은 AskStreamGate 가 한다.
     *
     * 기본 구현은 explain() 결과를 조각 하나로 낸다. 스트리밍하지 않는 프로바이더와
     * 테스트 페이크가 같은 계약을 쓸 수 있고, 같은 explain() 을 거치므로 프롬프트
     * 조립이 갈라지지 않는다. 인용을 먼저 두는 것은 실제 모델이 스키마 순서대로
     * 내보내는 것과 맞추기 위해서다 — 안전은 이 순서에 의존하지 않는다.
     */
    fun stream(systemText: String, userText: String, onChunk: (String) -> Unit): StreamEnd =
        when (val result = explain(systemText, userText)) {
            is Answered -> {
                onChunk(
                    STREAM_MAPPER.writeValueAsString(
                        linkedMapOf(
                            "citations" to result.explanation.citations,
                            "explanation" to result.explanation.explanation,
                        ),
                    ),
                )
                StreamCompleted(result.usage)
            }
            is Refused -> StreamRefused(result.category)
            is Failed -> StreamFailed(result.reason)
        }
}
```

- [ ] **Step 4: 테스트와 기존 페이크를 확인한다**

Run: `./gradlew --no-daemon test --tests '*DefaultStreamTest*'`
Expected: PASS (4개)

Run: `./gradlew --no-daemon build`
Expected: BUILD SUCCESSFUL. 기존 페이크 8개가 수정 없이 컴파일돼야 한다 — 그게 기본 구현의 존재 이유다.

- [ ] **Step 5: 커밋**

```bash
git add server/src/main/kotlin/com/hermes/llm/ExplanationProvider.kt server/src/test/kotlin/com/hermes/llm/DefaultStreamTest.kt
git commit -m "feat: give the provider port a streaming method that defaults to explain

포트 구현체가 테스트에만 8개라 추상 메서드를 더하면 전부 깨진다. 기본
구현이 explain() 결과를 조각 하나로 내므로 페이크는 그대로 컴파일되고,
같은 explain() 을 거쳐 프롬프트 조립이 갈라지지 않는다."
```

---

### Task 2: `AskStreamParser` — 조각을 해독한다

모델은 `{"citations":[...],"explanation":"..."}` 를 조각으로 보낸다. 파서는 인용 배열이 닫히는 순간과 본문 문자열의 내용을 알린다. **검증하지 않는다** — 규칙이 두 군데 생기면 갈라진다.

가장 까다로운 것은 이스케이프가 조각 경계에 걸리는 경우다(`\u` 가 한 조각, `AC00` 이 다음 조각). 상태를 필드에 두고 한 글자씩 처리하므로 경계는 문제가 되지 않아야 한다 — 그것을 **모든 분할 위치에서** 증명한다.

Spring AI 는 HTTP 층에서 UTF-8 바이트를 이미 `String` 으로 풀어 넘긴다. 이 파서가 다루는 경계는 바이트가 아니라 **문자** 경계다. 다만 UTF-16 서로게이트 쌍(이모지)은 문자 두 개라 쪼개질 수 있고, 그것도 다룬다.

**Files:**
- Create: `server/src/main/kotlin/com/hermes/llm/AskStreamParser.kt`
- Test: `server/src/test/kotlin/com/hermes/llm/AskStreamParserTest.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `sealed interface ParseEvent`
  - `data class CitationsClosed(val citations: List<String>) : ParseEvent`
  - `data class BodyText(val text: String) : ParseEvent`
  - `class AskStreamParser { fun feed(chunk: String): List<ParseEvent>; val complete: Boolean }`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`server/src/test/kotlin/com/hermes/llm/AskStreamParserTest.kt`:

```kotlin
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
        // 이스케이프를 전부 섞은 본문. 가 은 "가", 😀 은 이모지 하나다.
        val raw = """{"citations":["a\"b.md","c\\d.md"],"explanation":"줄\n탭\t따옴\"역\\슬\/가가웃😀끝"}"""
        val expectedBody = "줄\n탭\t따옴\"역\\슬/가가웃😀끝"
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
        val raw = """{"citations":["a.md"],"explanation":"가가😀"}"""
        val (events, _) = run(*raw.map { it.toString() }.toTypedArray())

        assertThat(body(events)).isEqualTo("가가😀")
    }

    @Test
    fun `서로게이트 쌍의 앞쪽 반만 담긴 본문 조각은 내지 않는다`() {
        // 이모지 앞쪽 반까지만 먹인 시점에 나온 본문 조각들이 짝 없는 서로게이트로 끝나면 안 된다.
        val parser = AskStreamParser()
        val first = parser.feed("""{"citations":["a.md"],"explanation":"x\uD83D""")
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
    fun `공백과 줄바꿈이 끼어도 된다`() {
        val (events, parser) = run("{\n  \"citations\" : [ \"a.md\" ] ,\n  \"explanation\" : \"x\"\n}")

        assertThat(citations(events)).containsExactly("a.md")
        assertThat(body(events)).isEqualTo("x")
        assertThat(parser.complete).isTrue()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew --no-daemon test --tests '*AskStreamParserTest*'`
Expected: FAIL — `AskStreamParser` 가 없어 컴파일 에러

- [ ] **Step 3: 구현한다**

`server/src/main/kotlin/com/hermes/llm/AskStreamParser.kt`:

```kotlin
package com.hermes.llm

sealed interface ParseEvent

/** 인용 배열이 닫혔다. 판정은 여기서 하지 않는다 — 규칙이 두 군데 생기면 갈라진다. */
data class CitationsClosed(val citations: List<String>) : ParseEvent

/** 본문 문자열에서 이스케이프를 푼 조각. 빈 조각은 내지 않는다. */
data class BodyText(val text: String) : ParseEvent

/**
 * 모델이 조각으로 보내는 `{"citations":[...],"explanation":"..."}` 를 해독한다.
 *
 * 스키마가 strict 라 키는 정확히 둘이고 순서만 모른다. 그래서 범용 JSON 파서가 아니라
 * 이 모양 하나만 아는 상태 기계로 둔다 — 범용 비동기 파서(Jackson)는 문자열이 **완성된
 * 뒤에야** 토큰을 내므로 본문을 흘리는 데 쓸 수 없다.
 *
 * 상태를 전부 필드에 두고 한 글자씩 처리하므로 조각 경계가 어디든 결과가 같다. 이벤트는
 * 스트림에 나타난 순서대로 낸다 — 본문 조각을 모아 두었다가 인용 이벤트 뒤에 내면 순서가
 * 뒤집힌다.
 */
class AskStreamParser {

    private enum class Where { OBJECT, AFTER_KEY, VALUE, ARRAY }

    private enum class Target { KEY, CITATION, BODY }

    private var where = Where.OBJECT
    private var key = ""
    private var inString = false
    private var target = Target.KEY
    private var escaping = false
    private var hex: StringBuilder? = null
    private var pendingHigh: Char? = null
    private val text = StringBuilder()
    private val citations = mutableListOf<String>()
    private var citationsClosed = false
    private var bodyClosed = false

    /** 두 필드가 모두 닫혔는가. 스트림이 끝났는데 거짓이면 응답이 잘린 것이다. */
    val complete: Boolean get() = citationsClosed && bodyClosed

    fun feed(chunk: String): List<ParseEvent> {
        val events = mutableListOf<ParseEvent>()
        val body = StringBuilder()
        for (c in chunk) {
            if (inString) stringChar(c, body) else structural(c, body, events)
        }
        flushBody(body, events)
        return events
    }

    private fun structural(c: Char, body: StringBuilder, events: MutableList<ParseEvent>) {
        when (where) {
            Where.OBJECT -> if (c == '"') startString(Target.KEY)
            Where.AFTER_KEY -> if (c == ':') where = Where.VALUE
            Where.VALUE -> when {
                key == "citations" && c == '[' -> where = Where.ARRAY
                key == "explanation" && c == '"' -> startString(Target.BODY)
            }
            Where.ARRAY -> when (c) {
                '"' -> startString(Target.CITATION)
                ']' -> {
                    // 앞서 모인 본문 조각을 먼저 낸다 — 이벤트가 스트림 순서를 지키도록.
                    flushBody(body, events)
                    citationsClosed = true
                    events += CitationsClosed(citations.toList())
                    where = Where.OBJECT
                }
            }
        }
    }

    private fun startString(t: Target) {
        inString = true
        target = t
        text.clear()
    }

    private fun stringChar(c: Char, body: StringBuilder) {
        val digits = hex
        when {
            digits != null -> {
                digits.append(c)
                if (digits.length == 4) {
                    hex = null
                    put(digits.toString().toInt(16).toChar(), body)
                }
            }
            escaping -> {
                escaping = false
                when (c) {
                    'u' -> hex = StringBuilder(4)
                    'n' -> put('\n', body)
                    't' -> put('\t', body)
                    'r' -> put('\r', body)
                    'b' -> put('\b', body)
                    'f' -> put('\u000C', body)
                    else -> put(c, body) // \"  \\  \/
                }
            }
            c == '\\' -> escaping = true
            c == '"' -> endString(body)
            else -> put(c, body)
        }
    }

    private fun put(ch: Char, body: StringBuilder) {
        if (target != Target.BODY) {
            text.append(ch)
            return
        }
        // 서로게이트 쌍의 앞쪽 반만 조각 끝에 내보내면 받는 쪽이 깨진 글자를 본다.
        // 뒤쪽 반이 올 때까지 쥐고 있는다.
        val high = pendingHigh
        when {
            high != null -> {
                pendingHigh = null
                body.append(high).append(ch)
            }
            ch.isHighSurrogate() -> pendingHigh = ch
            else -> body.append(ch)
        }
    }

    private fun endString(body: StringBuilder) {
        inString = false
        when (target) {
            Target.KEY -> {
                key = text.toString()
                where = Where.AFTER_KEY
            }
            Target.CITATION -> citations += text.toString()
            Target.BODY -> {
                pendingHigh?.let {
                    body.append(it)
                    pendingHigh = null
                }
                bodyClosed = true
                where = Where.OBJECT
            }
        }
    }

    private fun flushBody(body: StringBuilder, events: MutableList<ParseEvent>) {
        if (body.isEmpty()) return
        events += BodyText(body.toString())
        body.clear()
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew --no-daemon test --tests '*AskStreamParserTest*'`
Expected: PASS (9개). `모든 분할 위치에서 결과가 같다` 가 이 파서의 존재 이유다 — 실패하면 어느 `cut` 에서 깨졌는지 메시지에 나온다.

- [ ] **Step 5: 변이 검사 — 경계 처리에 이빨이 있는지**

`hex` 를 지역 변수처럼 다루도록 망가뜨린다: `stringChar` 첫 줄 `val digits = hex` 를 그대로 두고, `feed` 시작에 `hex = null` 을 한 줄 넣는다(조각이 바뀌면 `\u` 진행 상태를 잃는 버그). `모든 분할 위치에서 결과가 같다` 가 **빨개지는지** 확인하고 되돌린다. 실행 출력을 보고서에 붙인다.

- [ ] **Step 6: 커밋**

```bash
git add server/src/main/kotlin/com/hermes/llm/AskStreamParser.kt server/src/test/kotlin/com/hermes/llm/AskStreamParserTest.kt
git commit -m "feat: decode the model's streamed JSON across chunk boundaries

\\u 가 한 조각, 16진수 넷이 다음 조각에 오는 경우가 실제로 생긴다. 상태를
필드에 두고 한 글자씩 처리해 조각 경계를 무의미하게 만들었고, 모든 분할
위치에서 결과가 같음을 테스트로 보인다. 검증은 하지 않는다."
```

---

### Task 3: `AskStreamGate` — 검증된 본문만 흘린다

**이 저장소의 약속이 사는 곳이다.** 인용이 검증되기 전의 본문은 쥐고 있고, 검증을 통과해야 내보내며, 무효면 쥔 것을 버리고 `delta` 없이 끝낸다.

사유 문자열도 여기서 정한다. 비스트리밍 경로(`CourseQuestionService.ask`, `ExplanationService`)가 쓰는 문자열과 **한 글자도 달라선 안 된다** — 하네스가 그 문자열로 `UNCITED_CLAIM` 을 판별한다. 세 곳이 각자 문자열을 조립하면 언젠가 갈라지므로 함수 하나로 모은다.

**Files:**
- Create: `server/src/main/kotlin/com/hermes/explain/CitationReasons.kt`
- Create: `server/src/main/kotlin/com/hermes/explain/AskStreamGate.kt`
- Modify: `server/src/main/kotlin/com/hermes/explain/CourseQuestionService.kt` (사유 조립을 공유 함수로)
- Modify: `server/src/main/kotlin/com/hermes/explain/ExplanationService.kt` (같은 매핑이 있으면 공유 함수로)
- Test: `server/src/test/kotlin/com/hermes/explain/AskStreamGateTest.kt`

**Interfaces:**
- Consumes: `ParseEvent`, `CitationsClosed`, `BodyText` (Task 2), `CitationValidator`, `Valid`, `Invalid`
- Produces:
  - `internal fun invalidCitationReason(invalid: Invalid): String`
  - `sealed interface AskStreamEvent`
  - `data class CitationsEvent(val citations: List<String>)`, `data class DeltaEvent(val text: String)`, `data object DoneEvent`, `data class UnavailableEvent(val reason: String)`, `data class AbortedEvent(val reason: String)` — 전부 `AskStreamEvent`
  - `class AskStreamGate(validator: CitationValidator, emit: (AskStreamEvent) -> Unit) { fun accept(event: ParseEvent); fun finish(parseComplete: Boolean); fun fail(reason: String) }`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`server/src/test/kotlin/com/hermes/explain/AskStreamGateTest.kt`:

```kotlin
package com.hermes.explain

import com.hermes.context.BundleLoader
import com.hermes.context.CitationValidator
import com.hermes.llm.BodyText
import com.hermes.llm.CitationsClosed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 이 테스트 파일이 이 기능의 존재 이유다: 검증 안 된 글자는 한 글자도 나가지 않는다.
 */
class AskStreamGateTest {

    private val bundle = BundleLoader.load()
    private val known = bundle.paths().first()
    private val validator = CitationValidator(bundle)

    private fun gate(): Pair<AskStreamGate, MutableList<AskStreamEvent>> {
        val out = mutableListOf<AskStreamEvent>()
        return AskStreamGate(validator) { out += it } to out
    }

    private fun deltas(out: List<AskStreamEvent>) = out.filterIsInstance<DeltaEvent>()

    @Test
    fun `인용 먼저 + 유효 — 인용 뒤 본문이 흐르고 끝난다`() {
        val (g, out) = gate()

        g.accept(CitationsClosed(listOf(known)))
        g.accept(BodyText("안"))
        g.accept(BodyText("녕"))
        g.finish(parseComplete = true)

        assertThat(out).containsExactly(
            CitationsEvent(listOf(known)),
            DeltaEvent("안"),
            DeltaEvent("녕"),
            DoneEvent,
        )
    }

    @Test
    fun `인용 먼저 + 무효 — delta 없이 unavailable`() {
        val (g, out) = gate()

        g.accept(CitationsClosed(listOf("not/in/bundle.md")))
        g.accept(BodyText("새면 안 되는 문장"))
        g.finish(parseComplete = true)

        assertThat(deltas(out)).isEmpty()
        assertThat(out).containsExactly(UnavailableEvent("citations not in bundle: not/in/bundle.md"))
    }

    @Test
    fun `본문 먼저 + 유효 — 쥐고 있다가 인용 검증 뒤 한 번에 낸다`() {
        val (g, out) = gate()

        g.accept(BodyText("앞"))
        g.accept(BodyText("뒤"))
        assertThat(deltas(out)).describedAs("검증 전에는 아무것도 나가지 않는다").isEmpty()

        g.accept(CitationsClosed(listOf(known)))
        g.finish(parseComplete = true)

        assertThat(out).containsExactly(CitationsEvent(listOf(known)), DeltaEvent("앞뒤"), DoneEvent)
    }

    @Test
    fun `본문 먼저 + 무효 — 쥔 본문을 버리고 delta 없이 unavailable`() {
        val (g, out) = gate()

        g.accept(BodyText("새면 안 되는 문장"))
        g.accept(CitationsClosed(listOf("not/in/bundle.md")))
        g.finish(parseComplete = true)

        assertThat(deltas(out)).isEmpty()
        assertThat(out.single()).isInstanceOf(UnavailableEvent::class.java)
    }

    @Test
    fun `빈 인용은 기존과 같은 사유로 unavailable`() {
        val (g, out) = gate()

        g.accept(CitationsClosed(emptyList()))

        assertThat(out).containsExactly(UnavailableEvent("no citations"))
    }

    @Test
    fun `본문을 보낸 뒤의 실패는 aborted 다 — unavailable 이 아니다`() {
        val (g, out) = gate()

        g.accept(CitationsClosed(listOf(known)))
        g.accept(BodyText("미완"))
        g.fail("IOException: reset")

        assertThat(out.last()).isEqualTo(AbortedEvent("IOException: reset"))
    }

    @Test
    fun `본문을 보내기 전의 실패는 unavailable 이다`() {
        val (g, out) = gate()

        g.accept(CitationsClosed(listOf(known)))
        g.fail("refusal (unknown)")

        assertThat(out).containsExactly(CitationsEvent(listOf(known)), UnavailableEvent("refusal (unknown)"))
    }

    @Test
    fun `인용 없이 스트림이 끝나면 잘린 응답으로 unavailable`() {
        val (g, out) = gate()

        g.accept(BodyText("쥔 본문"))
        g.finish(parseComplete = false)

        assertThat(deltas(out)).isEmpty()
        assertThat(out).containsExactly(UnavailableEvent("truncated response"))
    }

    @Test
    fun `본문이 닫히기 전에 끝나면 보낸 뒤라 aborted`() {
        val (g, out) = gate()

        g.accept(CitationsClosed(listOf(known)))
        g.accept(BodyText("잘리"))
        g.finish(parseComplete = false)

        assertThat(out.last()).isEqualTo(AbortedEvent("truncated response"))
    }

    @Test
    fun `끝난 뒤에 온 이벤트는 무시한다`() {
        val (g, out) = gate()

        g.accept(CitationsClosed(listOf("not/in/bundle.md")))
        g.accept(CitationsClosed(listOf(known)))
        g.accept(BodyText("늦게 온 본문"))
        g.finish(parseComplete = true)

        assertThat(out).hasSize(1)
        assertThat(deltas(out)).isEmpty()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew --no-daemon test --tests '*AskStreamGateTest*'`
Expected: FAIL — `AskStreamGate` 가 없어 컴파일 에러

- [ ] **Step 3: 사유 함수를 만들고 기존 두 서비스가 쓰게 한다**

`server/src/main/kotlin/com/hermes/explain/CitationReasons.kt`:

```kotlin
package com.hermes.explain

import com.hermes.context.Invalid

/**
 * 인용 무효를 사유 문자열로 만든다.
 *
 * 비스트리밍 두 서비스와 스트리밍 게이트가 모두 이것을 쓴다. 하네스의
 * `unavailableReasonIndicatesUncitedClaim` 이 이 문자열로 UNCITED_CLAIM 을 판별하므로,
 * 세 곳이 각자 문자열을 조립하다 한 곳이 어긋나면 그 경로의 위반이 조용히 안 세어진다.
 */
internal fun invalidCitationReason(invalid: Invalid): String =
    if (invalid.unknownPaths.isEmpty()) {
        "no citations"
    } else {
        "citations not in bundle: ${invalid.unknownPaths.joinToString()}"
    }
```

`CourseQuestionService.ask` 의 `is Invalid -> Unavailable(if ... else ...)` 블록을 `is Invalid -> Unavailable(invalidCitationReason(citations))` 로 바꾼다.

`ExplanationService.kt` 를 열어 같은 `if (unknownPaths.isEmpty()) "no citations" else "citations not in bundle: ..."` 매핑이 있으면 같은 방식으로 바꾼다. 문자열이 **조금이라도 다르면 바꾸지 말고 멈추고 보고한다** — 두 경로의 사유가 원래 달랐다는 뜻이고, 그건 하네스 판별에 영향을 주는 발견이다.

- [ ] **Step 4: 게이트를 구현한다**

`server/src/main/kotlin/com/hermes/explain/AskStreamGate.kt`:

```kotlin
package com.hermes.explain

import com.hermes.context.CitationValidator
import com.hermes.context.Invalid
import com.hermes.context.Valid
import com.hermes.llm.BodyText
import com.hermes.llm.CitationsClosed
import com.hermes.llm.ParseEvent

sealed interface AskStreamEvent

data class CitationsEvent(val citations: List<String>) : AskStreamEvent

data class DeltaEvent(val text: String) : AskStreamEvent

data object DoneEvent : AskStreamEvent

/** 본문을 하나도 보내기 전의 실패. 이것이 오면 그 전에 DeltaEvent 는 0개다. */
data class UnavailableEvent(val reason: String) : AskStreamEvent

/** 본문을 보내기 시작한 뒤의 실패. 앞의 본문은 검증됐지만 문장이 미완이다. */
data class AbortedEvent(val reason: String) : AskStreamEvent

/**
 * 해독된 조각을 받아, 인용 검증을 통과한 본문만 내보낸다.
 *
 * **검증을 통과하지 않은 글자는 한 글자도 나가지 않는다.** 인용이 먼저 오면 그 자리에서
 * 검증하고 통과해야 본문을 흘린다. 본문이 먼저 오면 쥐고 있다가 인용이 오면 검증하고,
 * 통과하면 한 번에 내보내고 무효면 버린다. 필드 순서는 속도만 좌우하고 안전은 좌우하지
 * 못한다.
 *
 * 한 번 끝나면(무효, 실패, 완료) 이후 이벤트는 전부 무시한다.
 */
class AskStreamGate(
    private val validator: CitationValidator,
    private val emit: (AskStreamEvent) -> Unit,
) {
    private var validated = false
    private var closed = false
    private var deltas = 0
    private val held = StringBuilder()

    fun accept(event: ParseEvent) {
        if (closed) return
        when (event) {
            is CitationsClosed -> when (val result = validator.validate(event.citations)) {
                is Valid -> {
                    validated = true
                    emit(CitationsEvent(event.citations))
                    if (held.isNotEmpty()) {
                        send(held.toString())
                        held.clear()
                    }
                }
                is Invalid -> fail(invalidCitationReason(result))
            }
            is BodyText -> if (validated) send(event.text) else held.append(event.text)
        }
    }

    /** 프로바이더 스트림이 정상적으로 끝났을 때. 응답이 잘렸으면 실패로 닫는다. */
    fun finish(parseComplete: Boolean) {
        if (closed) return
        if (!parseComplete || !validated) {
            fail("truncated response")
            return
        }
        closed = true
        emit(DoneEvent)
    }

    fun fail(reason: String) {
        if (closed) return
        closed = true
        held.clear()
        emit(if (deltas == 0) UnavailableEvent(reason) else AbortedEvent(reason))
    }

    private fun send(text: String) {
        deltas++
        emit(DeltaEvent(text))
    }
}
```

- [ ] **Step 5: 테스트와 기존 테스트를 확인한다**

Run: `./gradlew --no-daemon test --tests '*AskStreamGateTest*'`
Expected: PASS (10개)

Run: `./gradlew --no-daemon build`
Expected: BUILD SUCCESSFUL. `CourseQuestionServiceTest`, `ExplanationServiceTest`, `AskControllerTest` 가 그대로 통과해야 한다 — 사유 문자열이 바뀌지 않았다는 증거다.

- [ ] **Step 6: 변이 검사 — 약속에 이빨이 있는지**

게이트의 `is BodyText -> if (validated) send(event.text) else held.append(event.text)` 를 `is BodyText -> send(event.text)` 로 바꾼다(검증 전에도 흘리는 버그). `인용 먼저 + 무효`, `본문 먼저 + 유효`, `본문 먼저 + 무효` 가 **빨개지는지** 확인하고 되돌린다. 실행 출력을 보고서에 붙인다.

- [ ] **Step 7: 커밋**

```bash
git add server/src/main/kotlin/com/hermes/explain/CitationReasons.kt server/src/main/kotlin/com/hermes/explain/AskStreamGate.kt server/src/main/kotlin/com/hermes/explain/CourseQuestionService.kt server/src/main/kotlin/com/hermes/explain/ExplanationService.kt server/src/test/kotlin/com/hermes/explain/AskStreamGateTest.kt
git commit -m "feat: let only citation-validated text leave the stream

인용이 검증되기 전의 본문은 쥐고 있고, 통과해야 흘리며, 무효면 버리고
delta 없이 끝낸다. 인용이 먼저 오든 본문이 먼저 오든 같다. 사유 문자열은
세 경로가 함수 하나로 공유한다 — 하네스가 그 문자열로 UNCITED_CLAIM 을
판별한다."
```

---

### Task 4: `SpringAiExplanationProvider.stream()` — 실제로 스트리밍한다

스파이크는 raw HTTP 로 했다. **Spring AI 의 스트리밍 경로가 `stream: true` 와 `response_format` 을 둘 다 싣는지는 아직 증명되지 않았다.** 루프백 캡처로 무료로 증명한다. 그러려면 `CapturingEndpoint` 가 미리 짠 SSE 를 돌려줄 수 있어야 한다.

**Files:**
- Modify: `server/src/test/kotlin/com/hermes/llm/CapturingEndpoint.kt`
- Modify: `server/src/main/kotlin/com/hermes/llm/SpringAiExplanationProvider.kt`
- Test: `server/src/test/kotlin/com/hermes/llm/SpringAiStreamTest.kt`

**Interfaces:**
- Consumes: `StreamEnd` 계층 (Task 1), `LlmSelection.provider(name, model, env, baseUrlOverride)` (기존)
- Produces:
  - `CapturingEndpoint(response: CannedResponse? = null)` — `null` 이면 지금처럼 500
  - `data class CannedResponse(val status: Int, val contentType: String, val body: String)`
  - `SpringAiExplanationProvider.stream(...)` 재정의

- [ ] **Step 1: `CapturingEndpoint` 를 확장한다**

`server/src/test/kotlin/com/hermes/llm/CapturingEndpoint.kt` 에서 클래스 선언과 핸들러를 바꾼다. 인자가 없으면 **지금과 똑같이** 동작해야 한다 — 기존 테스트가 전부 그것에 기대고 있다.

```kotlin
/** 루프백 엔드포인트가 돌려줄 응답. 없으면 지금처럼 500 이다. */
data class CannedResponse(val status: Int, val contentType: String, val body: String)

class CapturingEndpoint(private val response: CannedResponse? = null) : AutoCloseable {
```

`createContext("/") { exchange -> ... }` 안의 응답 부분을 바꾼다:

```kotlin
            createContext("/") { exchange ->
                body = exchange.requestBody.readBytes()
                path = exchange.requestURI.path
                val canned = response
                    ?: CannedResponse(
                        500,
                        "application/json",
                        """{"type":"error","error":{"type":"api_error","message":"captured"}}""",
                    )
                val payload = canned.body.toByteArray()
                exchange.responseHeaders.add("Content-Type", canned.contentType)
                exchange.sendResponseHeaders(canned.status, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
```

필드 `body`, `path` 와 `baseUrl`, `capturedBody()`, `capturedPath()` 는 그대로 둔다. 바꾸는 것은 응답을 고르는 부분뿐이다.

Run: `./gradlew --no-daemon test --tests '*CapturingEndpoint*' --tests '*SpringAiRequestShapeTest*' --tests '*LlmSelectionTest*'`
Expected: PASS — 인자 없는 기존 사용처가 그대로여야 한다.

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`server/src/test/kotlin/com/hermes/llm/SpringAiStreamTest.kt`:

```kotlin
package com.hermes.llm

import com.hermes.shared.config.LlmSelection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 스파이크(raw HTTP)가 보인 것을 Spring AI 경로에서 다시 증명한다: 스트리밍 요청도
 * response_format 을 싣고, 조각이 순서대로 onChunk 에 도착한다.
 */
class SpringAiStreamTest {

    /** openai-java 가 파싱할 수 있는 청크. id/object/created/model 은 SDK 가 요구한다. */
    private fun chunk(content: String, finish: String? = null): String {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
        val finishJson = if (finish == null) "null" else "\"$finish\""
        return """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o","choices":[{"index":0,"delta":{"content":"$escaped"},"finish_reason":$finishJson}]}""" + "\n\n"
    }

    private val sse = chunk("""{"citations":["concepts/a.md"]""") +
        chunk(""","explanation":"가나""") +
        chunk("""다"}""", finish = "stop") +
        "data: [DONE]\n\n"

    private fun provider(endpoint: CapturingEndpoint): ExplanationProvider =
        LlmSelection.provider("openai", "gpt-4o", { "sk-not-a-real-key" }, endpoint.baseUrl)

    @Test
    fun `스트리밍 요청도 stream 과 response_format 을 싣는다`() {
        CapturingEndpoint(CannedResponse(200, "text/event-stream", sse)).use { endpoint ->
            provider(endpoint).stream("sys", "user") {}
            val body = endpoint.capturedBody()

            assertThat(body["stream"].asBoolean()).isTrue()
            assertThat(body["response_format"]["type"].asText()).isEqualTo("json_schema")
            assertThat(body["response_format"]["json_schema"]["strict"].asBoolean()).isTrue()
        }
    }

    @Test
    fun `조각이 순서대로 도착하고 이어 붙이면 모델이 보낸 JSON 이다`() {
        CapturingEndpoint(CannedResponse(200, "text/event-stream", sse)).use { endpoint ->
            val chunks = mutableListOf<String>()

            val end = provider(endpoint).stream("sys", "user") { chunks += it }

            assertThat(chunks.joinToString("")).isEqualTo("""{"citations":["concepts/a.md"],"explanation":"가나다"}""")
            assertThat(chunks.size).describedAs("한 조각으로 뭉치지 않고 흘러야 한다").isGreaterThan(1)
            assertThat(end).isInstanceOf(StreamCompleted::class.java)
        }
    }

    @Test
    fun `엔드포인트가 죽으면 예외가 아니라 StreamFailed 로 끝난다`() {
        CapturingEndpoint().use { endpoint ->
            val end = provider(endpoint).stream("sys", "user") {}

            assertThat(end).isInstanceOf(StreamFailed::class.java)
        }
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

Run: `./gradlew --no-daemon test --tests '*SpringAiStreamTest*'`
Expected: FAIL. 기본 구현(`explain()` 위임)이 도는 상태라 `stream` 이 `false` 이거나, 조각이 하나로 뭉쳐 `isGreaterThan(1)` 이 실패한다.

- [ ] **Step 4: `stream()` 을 재정의한다**

`server/src/main/kotlin/com/hermes/llm/SpringAiExplanationProvider.kt` 에 재정의를 더한다. 필요한 import: `org.springframework.ai.chat.model.ChatResponse`.

```kotlin
    /**
     * 실제 스트리밍. Reactor 는 여기 가둔다 — 포트는 콜백과 블로킹 반환만 안다.
     *
     * 거절은 마지막 조각의 finishReason 으로 온다. 거절 응답은 content 가 비어 있어
     * onChunk 가 불리지 않으므로, 게이트는 delta 없이 unavailable 로 닫는다.
     */
    override fun stream(systemText: String, userText: String, onChunk: (String) -> Unit): StreamEnd = try {
        var last: ChatResponse? = null
        chatClient
            .prompt(Prompt(listOf(SystemMessage(systemText), UserMessage(userText))))
            .stream()
            .chatResponse()
            .doOnNext { response ->
                last = response
                val text = response.result?.output?.text
                if (!text.isNullOrEmpty()) onChunk(text)
            }
            .blockLast()

        val finish = last?.result?.metadata?.finishReason
        if (finish.equals(REFUSAL, ignoreCase = true)) {
            StreamRefused(category = null)
        } else {
            // 스트리밍에서는 프로바이더가 usage 를 안 줄 수 있다. 스트리밍 경로는 usage 를
            // 집계에 쓰지 않으므로 0 으로 둔다 — 하네스는 비스트리밍 explain() 으로 잰다.
            StreamCompleted(last?.let { usageOf(it) } ?: ProviderUsage(0, 0, 0, 0))
        }
    } catch (e: Exception) {
        log.warn("$name stream failed", e)
        StreamFailed("${e::class.simpleName}: ${e.message}")
    }
```

`usageOf(response: ChatResponse): ProviderUsage` 는 기존 `toProviderResult` 안의 usage 추출을 **그대로 옮겨** companion 의 private 함수로 꺼내고, `toProviderResult` 도 그것을 쓰게 한다. 추출 로직을 두 벌 두지 않는다. `REFUSAL` 상수가 이미 있으면 그것을 쓴다.

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `./gradlew --no-daemon test --tests '*SpringAiStreamTest*'`
Expected: PASS (3개)

첫 테스트가 실패하면(`response_format` 이 스트리밍 요청에 안 실림) **멈추고 보고한다.** 스파이크의 전제가 Spring AI 경로에서 깨진 것이고, 그러면 모델이 산문으로 답해 게이트가 전부 `unavailable` 로 닫는다. 우회하지 않는다.

Run: `./gradlew --no-daemon build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add server/src/test/kotlin/com/hermes/llm/CapturingEndpoint.kt server/src/main/kotlin/com/hermes/llm/SpringAiExplanationProvider.kt server/src/test/kotlin/com/hermes/llm/SpringAiStreamTest.kt
git commit -m "feat: stream model output through Spring AI behind the port

스파이크는 raw HTTP 로 했으므로 Spring AI 경로가 스트리밍에서도
response_format 을 싣는지는 증명되지 않았었다. 루프백으로 미리 짠 SSE 를
돌려주게 해 무료로 증명한다. Reactor 는 어댑터 안에 가둔다."
```

---

### Task 5: `CourseQuestionService.askStream()` — 같은 프롬프트로 잇는다

스트리밍 경로가 비스트리밍 경로와 **같은 `systemText`/`userText`** 를 조립해야 하네스가 잰 숫자가 여기도 유효하다. `buildUserText` 를 그대로 쓴다.

**Files:**
- Modify: `server/src/main/kotlin/com/hermes/explain/CourseQuestionService.kt`
- Test: `server/src/test/kotlin/com/hermes/explain/CourseQuestionStreamTest.kt`

**Interfaces:**
- Consumes: `AskStreamParser` (Task 2), `AskStreamGate`, `AskStreamEvent` 계층 (Task 3), `StreamEnd` 계층 (Task 1)
- Produces: `CourseQuestionService.askStream(facts: BackendFacts, question: String, history: List<QuestionTurn>, emit: (AskStreamEvent) -> Unit)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`server/src/test/kotlin/com/hermes/explain/CourseQuestionStreamTest.kt`:

```kotlin
package com.hermes.explain

import com.hermes.context.BundleLoader
import com.hermes.context.CitationValidator
import com.hermes.context.PromptAssembler
import com.hermes.llm.Answered
import com.hermes.llm.Explanation
import com.hermes.llm.ExplanationProvider
import com.hermes.llm.ProviderResult
import com.hermes.llm.ProviderUsage
import com.hermes.llm.StreamCompleted
import com.hermes.llm.StreamEnd
import com.hermes.llm.StreamFailed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CourseQuestionStreamTest {

    private val bundle = BundleLoader.load()
    private val known = bundle.paths().first()
    private val facts = BackendFacts("course-1", """{"courseUuid":"course-1"}""")

    /** 두 경로가 받은 텍스트를 기록하고, stream 은 주어진 조각을 흘린다. */
    private class Recorder(
        private val chunks: List<String>,
        private val end: StreamEnd = StreamCompleted(ProviderUsage(0, 0, 0, 0)),
    ) : ExplanationProvider {
        override val name = "recorder"
        val explained = mutableListOf<Pair<String, String>>()
        val streamed = mutableListOf<Pair<String, String>>()

        override fun explain(systemText: String, userText: String): ProviderResult {
            explained += systemText to userText
            return Answered(Explanation("x", listOf("a.md")), ProviderUsage(0, 0, 0, 0))
        }

        override fun stream(systemText: String, userText: String, onChunk: (String) -> Unit): StreamEnd {
            streamed += systemText to userText
            chunks.forEach(onChunk)
            return end
        }
    }

    private fun service(provider: ExplanationProvider) =
        CourseQuestionService(PromptAssembler(bundle), CitationValidator(bundle), provider)

    @Test
    fun `스트리밍과 비스트리밍이 같은 system 과 user 텍스트를 조립한다`() {
        val recorder = Recorder(listOf("""{"citations":["$known"],"explanation":"x"}"""))
        val history = listOf(QuestionTurn("앞 질문", "앞 답"))

        service(recorder).ask(facts, "질문", history)
        service(recorder).askStream(facts, "질문", history) {}

        // 이 동일성이 캐시가 유지되고 하네스 숫자가 스트리밍에도 유효하다는 근거다.
        assertThat(recorder.streamed.single()).isEqualTo(recorder.explained.single())
    }

    @Test
    fun `조각을 해독하고 검증해 이벤트로 낸다`() {
        val recorder = Recorder(listOf("""{"citations":["$known"],""", """"explanation":"가나"}"""))
        val out = mutableListOf<AskStreamEvent>()

        service(recorder).askStream(facts, "질문", emptyList()) { out += it }

        assertThat(out.first()).isEqualTo(CitationsEvent(listOf(known)))
        assertThat(out.filterIsInstance<DeltaEvent>().joinToString("") { it.text }).isEqualTo("가나")
        assertThat(out.last()).isEqualTo(DoneEvent)
    }

    @Test
    fun `무효 인용이면 본문이 한 글자도 나가지 않는다`() {
        val recorder = Recorder(listOf("""{"citations":["not/in/bundle.md"],"explanation":"새면 안 됨"}"""))
        val out = mutableListOf<AskStreamEvent>()

        service(recorder).askStream(facts, "질문", emptyList()) { out += it }

        assertThat(out.filterIsInstance<DeltaEvent>()).isEmpty()
        assertThat(out.single()).isInstanceOf(UnavailableEvent::class.java)
    }

    @Test
    fun `프로바이더가 본문 도중 실패하면 aborted`() {
        val recorder = Recorder(
            listOf("""{"citations":["$known"],"explanation":"미"""),
            end = StreamFailed("IOException: reset"),
        )
        val out = mutableListOf<AskStreamEvent>()

        service(recorder).askStream(facts, "질문", emptyList()) { out += it }

        assertThat(out.last()).isEqualTo(AbortedEvent("IOException: reset"))
    }

    @Test
    fun `stream 을 재정의하지 않은 프로바이더도 기본 구현으로 돈다`() {
        val plain = object : ExplanationProvider {
            override val name = "plain"
            override fun explain(systemText: String, userText: String): ProviderResult =
                Answered(Explanation("본문", listOf(known)), ProviderUsage(0, 0, 0, 0))
        }
        val out = mutableListOf<AskStreamEvent>()

        service(plain).askStream(facts, "질문", emptyList()) { out += it }

        assertThat(out).containsExactly(CitationsEvent(listOf(known)), DeltaEvent("본문"), DoneEvent)
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew --no-daemon test --tests '*CourseQuestionStreamTest*'`
Expected: FAIL — `askStream` 이 없어 컴파일 에러

- [ ] **Step 3: 구현한다**

`CourseQuestionService` 에 메서드를 더한다. 필요한 import: `com.hermes.llm.AskStreamParser`, `com.hermes.llm.StreamCompleted`, `com.hermes.llm.StreamFailed`, `com.hermes.llm.StreamRefused`.

```kotlin
    /**
     * [ask] 의 스트리밍 변형. **같은 [buildUserText] 로 조립한다** — 두 경로의 프롬프트가
     * 같아야 1시간 캐시가 유지되고, 하네스가 비스트리밍 경로로 잰 숫자가 여기도 유효하다.
     *
     * 안전 판단은 [AskStreamGate] 가 전부 한다. 여기는 배선뿐이다.
     */
    fun askStream(
        facts: BackendFacts,
        question: String,
        history: List<QuestionTurn>,
        emit: (AskStreamEvent) -> Unit,
    ) {
        val parser = AskStreamParser()
        val gate = AskStreamGate(validator, emit)

        val end = provider.stream(assembler.systemText, buildUserText(facts, question, history)) { chunk ->
            parser.feed(chunk).forEach(gate::accept)
        }

        when (end) {
            is StreamCompleted -> gate.finish(parser.complete)
            is StreamRefused -> gate.fail("refusal (${end.category ?: "unknown"})")
            is StreamFailed -> gate.fail(end.reason)
        }
    }
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew --no-daemon test --tests '*CourseQuestionStreamTest*'`
Expected: PASS (5개)

Run: `./gradlew --no-daemon build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 변이 검사 — 같은 프롬프트 단언에 이빨이 있는지**

`askStream` 에서 `buildUserText(facts, question, history)` 를 `buildUserText(facts, question, emptyList())` 로 바꾼다(스트리밍 경로가 이전 대화를 빠뜨리는 버그). `스트리밍과 비스트리밍이 같은 system 과 user 텍스트를 조립한다` 가 **빨개지는지** 확인하고 되돌린다.

- [ ] **Step 6: 커밋**

```bash
git add server/src/main/kotlin/com/hermes/explain/CourseQuestionService.kt server/src/test/kotlin/com/hermes/explain/CourseQuestionStreamTest.kt
git commit -m "feat: stream follow-up answers with the same prompt as the blocking path

두 경로가 같은 buildUserText 로 조립해야 캐시가 유지되고 하네스가
비스트리밍으로 잰 숫자가 스트리밍에도 유효하다. 테스트가 두 경로의
system/user 텍스트가 같음을 직접 단언한다."
```

---

### Task 6: `POST /agent/ask/stream` — SSE 로 내보낸다

**Files:**
- Create: `server/src/main/kotlin/com/hermes/explain/presentation/AskStreamExecutor.kt`
- Create: `server/src/main/kotlin/com/hermes/explain/presentation/AskStreamController.kt`
- Modify: `server/src/main/kotlin/com/hermes/shared/config/HermesConfig.kt`
- Test: `server/src/test/kotlin/com/hermes/explain/presentation/AskStreamControllerTest.kt`

**Interfaces:**
- Consumes: `CourseQuestionService.askStream` (Task 5), `AskStreamEvent` 계층 (Task 3), 기존 `AskRequest`, `InvalidAskRequestException`, `FactsSource`, `HanjeokUnavailableException`, `QuestionTurn`, `BackendFacts`
- Produces: `POST /agent/ask/stream` (SSE), `class AskStreamExecutor(executor: ExecutorService) : Executor, AutoCloseable`

- [ ] **Step 1: 실행기 타입을 만든다**

`server/src/main/kotlin/com/hermes/explain/presentation/AskStreamExecutor.kt`:

```kotlin
package com.hermes.explain.presentation

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

/**
 * 스트림 전용 실행기.
 *
 * `factsExecutor`(스레드 4개)와 나눈다. 스트림 하나는 모델이 답하는 몇 초 동안 스레드를
 * 붙잡는데, 같은 풀을 쓰면 동시 스트림 몇 개가 한적 병렬 호출을 굶긴다 — 그리고 스트림
 * 자체가 한적 호출을 기다리므로 서로를 기다리는 교착이 된다.
 *
 * `ExecutorService` 빈을 하나 더 두면 타입으로 주입하던 곳이 모호해진다. 타입을 따로
 * 두어 그 모호함을 만들지 않는다. Spring 이 종료 시 [close] 를 부른다.
 */
class AskStreamExecutor(private val executor: ExecutorService) : Executor by executor, AutoCloseable {
    override fun close() = executor.shutdown()
}
```

`HermesConfig.kt` 에 빈을 더한다(import `com.hermes.explain.presentation.AskStreamExecutor`):

```kotlin
    /** 스트림은 I/O 대기라 스레드를 조금 넉넉히 둔다. Cloud Run 인스턴스당 동시 요청이 적다. */
    @Bean
    fun askStreamExecutor(): AskStreamExecutor = AskStreamExecutor(Executors.newFixedThreadPool(8))
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`AskControllerTest` 와 **같은 배선 방식**(standalone `MockMvc`, 진짜 `FactsSource`·`CourseQuestionService`, 가짜는 한적 클라이언트와 프로바이더 둘뿐)을 따른다.

`server/src/test/kotlin/com/hermes/explain/presentation/AskStreamControllerTest.kt`:

```kotlin
package com.hermes.explain.presentation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.hermes.context.BundleLoader
import com.hermes.context.CitationValidator
import com.hermes.context.PromptAssembler
import com.hermes.explain.CourseQuestionService
import com.hermes.facts.FactsSource
import com.hermes.facts.HanjeokClient
import com.hermes.llm.ExplanationProvider
import com.hermes.llm.ProviderResult
import com.hermes.llm.ProviderUsage
import com.hermes.llm.StreamCompleted
import com.hermes.llm.StreamEnd
import com.hermes.llm.StreamFailed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.concurrent.Executors

/**
 * AskControllerTest 와 같이 진짜 객체를 조립한다. 가짜는 한적 클라이언트와 프로바이더뿐이다.
 */
class AskStreamControllerTest {

    private val mapper = ObjectMapper()
    private val bundle = BundleLoader.load()
    private val known = bundle.paths().first()
    private val factsExecutor = Executors.newFixedThreadPool(2)

    /** AskControllerTest 의 것과 같은 사실. 두 경로가 같은 코스를 본다. */
    private inner class FakeClient : HanjeokClient {
        override fun course(courseUuid: String): JsonNode = mapper.readTree(
            """{"targetDate":"2026-09-12","title":"제목","congestionReductionRate":34,"summary":"요약",
                "recommendedDate":null,
                "items":[{"attractionId":1001,"name":"경복궁","visitOrder":1,"timeLabel":"오전 10:00",
                          "grade":"VERY_CROWDED","reason":"첫 방문지","travelMinutesFromPrev":null}]}""",
        )

        override fun congestion(attractionId: Long, date: String): JsonNode = mapper.readTree(
            """{"diagnosis":{"concentration":87.3,"percentile":92,"grade":"VERY_CROWDED","message":"붐빈다"},
                "betterDates":[]}""",
        )

        override fun alternatives(attractionId: Long, date: String, radiusKm: Int): JsonNode =
            mapper.readTree("[]")
    }

    /** 조각을 흘린다. explain 이 불리면 스트리밍 경로가 비스트리밍으로 새고 있다는 뜻이다. */
    private class StreamingProvider(
        private val chunks: List<String>,
        private val end: StreamEnd = StreamCompleted(ProviderUsage(0, 0, 0, 0)),
    ) : ExplanationProvider {
        override val name = "streaming"

        override fun explain(systemText: String, userText: String): ProviderResult =
            error("스트리밍 경로에서 explain 이 불리면 안 된다")

        override fun stream(systemText: String, userText: String, onChunk: (String) -> Unit): StreamEnd {
            chunks.forEach(onChunk)
            return end
        }
    }

    private fun mvc(provider: ExplanationProvider): MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                AskStreamController(
                    FactsSource(FakeClient(), 15, factsExecutor),
                    CourseQuestionService(PromptAssembler(bundle), CitationValidator(bundle), provider),
                    AskStreamExecutor(Executors.newSingleThreadExecutor()),
                    "gpt-4o",
                ),
            )
            .setControllerAdvice(ApiErrorHandler())
            .build()

    /** 스트림을 끝까지 받아 본문을 돌려준다. SSE 는 UTF-8 로 읽어야 한글이 안 깨진다. */
    private fun streamed(provider: ExplanationProvider): String {
        val mvc = mvc(provider)
        val started = mvc
            .perform(
                post("/agent/ask/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"courseUuid":"abc","question":"왜 이 순서예요?"}"""),
            )
            .andExpect(request().asyncStarted())
            .andReturn()
        return mvc.perform(asyncDispatch(started)).andReturn().response.getContentAsString(Charsets.UTF_8)
    }

    @Test
    fun `인용이 유효하면 citations 뒤에 delta 와 done 이 나간다`() {
        val body = streamed(StreamingProvider(listOf("""{"citations":["$known"],""", """"explanation":"가나"}""")))

        assertThat(body).contains("event:citations").contains("event:delta").contains("event:done")
        assertThat(body.indexOf("event:citations")).isLessThan(body.indexOf("event:delta"))
        assertThat(body).contains("가나")
    }

    @Test
    fun `인용이 무효하면 delta 없이 불투명한 unavailable 만 나간다`() {
        val body = streamed(
            StreamingProvider(listOf("""{"citations":["not/in/bundle.md"],"explanation":"새면 안 되는 문장"}""")),
        )

        assertThat(body).doesNotContain("event:delta")
        assertThat(body).contains("event:unavailable").contains("EXPLANATION_UNAVAILABLE")
        // 불투명성: 사유(인용 경로)도 본문도 브라우저로 새면 안 된다.
        assertThat(body).doesNotContain("not/in/bundle.md")
        assertThat(body).doesNotContain("citations not in bundle")
        assertThat(body).doesNotContain("새면 안 되는 문장")
    }

    @Test
    fun `본문 도중 실패하면 불투명한 aborted 가 나간다`() {
        val body = streamed(
            StreamingProvider(
                listOf("""{"citations":["$known"],"explanation":"미"""),
                end = StreamFailed("INTERNAL DETAIL THAT MUST NOT LEAK"),
            ),
        )

        assertThat(body).contains("event:aborted").contains("EXPLANATION_ABORTED")
        assertThat(body).doesNotContain("INTERNAL DETAIL THAT MUST NOT LEAK")
    }

    @Test
    fun `질문이 비어 있으면 스트림을 열지 않고 400`() {
        mvc(StreamingProvider(emptyList()))
            .perform(
                post("/agent/ask/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"courseUuid":"abc","question":"   "}"""),
            )
            .andExpect(status().isBadRequest)
    }
}
```

`asyncDispatch` 는 에미터가 `complete()` 될 때까지 기다린다. 오래 걸리거나 빈 본문이 오면, 백그라운드 실행기가 `emitter.complete()` 에 닿는지부터 확인한다 — 닿지 않으면 테스트가 아니라 컨트롤러가 스트림을 닫지 않는 것이다.

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

Run: `./gradlew --no-daemon test --tests '*AskStreamControllerTest*'`
Expected: FAIL — 엔드포인트가 없어 404 또는 컴파일 에러

- [ ] **Step 4: 컨트롤러를 구현한다**

`server/src/main/kotlin/com/hermes/explain/presentation/AskStreamController.kt`:

```kotlin
package com.hermes.explain.presentation

import com.hermes.explain.AbortedEvent
import com.hermes.explain.AskStreamEvent
import com.hermes.explain.BackendFacts
import com.hermes.explain.CitationsEvent
import com.hermes.explain.CourseQuestionService
import com.hermes.explain.DeltaEvent
import com.hermes.explain.DoneEvent
import com.hermes.explain.QuestionTurn
import com.hermes.explain.UnavailableEvent
import com.hermes.facts.FactsSource
import com.hermes.facts.HanjeokUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant

/**
 * 이어 묻기의 스트리밍 변형. 요청 계약은 `/agent/ask` 와 같다 — 사실은 백엔드에서만 온다.
 *
 * **실패 이벤트는 불투명하다.** 사유는 여기서 로그로 남기고 브라우저에는 코드만 보낸다.
 * `ApiErrorHandler` 가 비스트리밍 응답에 지키는 계약과 같다 — 스트리밍이라고 예외를 두면
 * 인용 경로, 예외 메시지, 거절 범주가 샌다.
 */
@RestController
class AskStreamController(
    private val source: FactsSource,
    private val service: CourseQuestionService,
    private val executor: AskStreamExecutor,
    @param:Value("\${hermes.llm.model}") private val model: String,
) {

    private val log = LoggerFactory.getLogger(AskStreamController::class.java)

    @PostMapping("/agent/ask/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@RequestBody request: AskRequest): SseEmitter {
        // 스트림을 열기 전에 거른다 — 열고 나면 상태 코드를 바꿀 수 없다.
        if (request.courseUuid.isBlank()) throw InvalidAskRequestException("courseUuid is blank")
        if (request.question.isBlank()) throw InvalidAskRequestException("question is blank")

        val emitter = SseEmitter(TIMEOUT_MS)
        executor.execute {
            try {
                run(request, emitter)
                emitter.complete()
            } catch (e: Exception) {
                log.warn("ask stream crashed for course {}", request.courseUuid, e)
                emitter.completeWithError(e)
            }
        }
        return emitter
    }

    private fun run(request: AskRequest, emitter: SseEmitter) {
        val facts = try {
            source.fetch(request.courseUuid)
        } catch (e: HanjeokUnavailableException) {
            log.warn("facts unavailable for course {}", request.courseUuid, e)
            send(emitter, "unavailable", UNAVAILABLE)
            return
        }

        val history = request.history.orEmpty().map { QuestionTurn(it.question, it.answer) }

        service.askStream(BackendFacts(facts.courseUuid, facts.json), request.question, history) { event ->
            when (event) {
                is CitationsEvent -> send(emitter, "citations", mapOf("citations" to event.citations))
                is DeltaEvent -> send(emitter, "delta", mapOf("text" to event.text))
                is DoneEvent -> send(
                    emitter,
                    "done",
                    mapOf("generatedAt" to Instant.now().toString(), "model" to model),
                )
                is UnavailableEvent -> {
                    log.warn("answer unavailable for course {}: {}", request.courseUuid, event.reason)
                    send(emitter, "unavailable", UNAVAILABLE)
                }
                is AbortedEvent -> {
                    log.warn("answer aborted for course {}: {}", request.courseUuid, event.reason)
                    send(emitter, "aborted", ABORTED)
                }
            }
        }
    }

    private fun send(emitter: SseEmitter, name: String, data: Any) {
        emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON))
    }

    private companion object {
        /** 모델 호출이 수 초이고 사실 조회가 앞에 붙는다. 컨테이너 기본 비동기 타임아웃에 맡기지 않는다. */
        const val TIMEOUT_MS = 90_000L
        val UNAVAILABLE = mapOf("code" to "EXPLANATION_UNAVAILABLE")
        val ABORTED = mapOf("code" to "EXPLANATION_ABORTED")
    }
}
```

`AskRequest` 는 `AskController.kt` 에 선언돼 있다. 같은 패키지라 그대로 쓴다.

- [ ] **Step 5: 테스트와 경계 테스트를 확인한다**

Run: `./gradlew --no-daemon test --tests '*AskStreamControllerTest*'`
Expected: PASS

Run: `./gradlew --no-daemon build`
Expected: BUILD SUCCESSFUL. `ModuleBoundaryTest`(presentation 밖 인바운드 웹 타입 금지)와 `ErrorResponseOpacityTest` 가 특히 중요하다. `AskStreamExecutor` 가 presentation 에 있고 `HermesConfig` 가 그것을 import 하는 것이 경계 규칙에 걸리면, 규칙을 약화하지 말고 멈추고 보고한다.

- [ ] **Step 6: 변이 검사 — 불투명성에 이빨이 있는지**

`UnavailableEvent` 분기의 `send(emitter, "unavailable", UNAVAILABLE)` 를 `send(emitter, "unavailable", mapOf("reason" to event.reason))` 로 바꾼다. `인용이 무효하면 delta 없이 불투명한 unavailable 만 나간다` 가 **빨개지는지** 확인하고 되돌린다.

- [ ] **Step 7: 커밋**

```bash
git add server/src/main/kotlin/com/hermes/explain/presentation/AskStreamExecutor.kt server/src/main/kotlin/com/hermes/explain/presentation/AskStreamController.kt server/src/main/kotlin/com/hermes/shared/config/HermesConfig.kt server/src/test/kotlin/com/hermes/explain/presentation/AskStreamControllerTest.kt
git commit -m "feat: serve follow-up answers as a server-sent event stream

실패 이벤트는 코드만 싣고 사유는 로그로 남긴다. 비스트리밍 응답이
지키는 불투명성 계약과 같다. 스트림은 사실 조회 풀과 다른 실행기에서
돈다 — 같은 풀을 쓰면 서로를 기다린다."
```

---

### Task 7: 프론트 — SSE 판독기

`EventSource` 는 POST 를 못 하므로 `fetch` + `ReadableStream` 으로 읽는다. 두 가지 경계를 다뤄야 한다 — **SSE 프레임이 여러 read 에 걸치는 것**, 그리고 **한글 UTF-8 바이트가 read 경계에서 잘리는 것**. 후자는 `TextDecoder` 의 `{ stream: true }` 가 처리한다.

**계약: 판독기는 던지지 않고, 반드시 종결 이벤트(`done`/`unavailable`/`aborted`) 하나로 끝난다.** 기존 `get()` 이 연결 실패를 예외 대신 값으로 만드는 것과 같은 이유다.

**Files:**
- Modify: `frontend/src/lib/agent.ts`
- Test: `frontend/src/lib/askCourseStream.test.ts`

**Interfaces:**
- Consumes: 기존 `AGENT_BASE_URL`, `AskTurn`, `Fetch` 타입
- Produces:
  - `export type AskStreamEvent = { kind: 'citations'; citations: string[] } | { kind: 'delta'; text: string } | { kind: 'done'; generatedAt: string; model: string } | { kind: 'unavailable' } | { kind: 'aborted' }`
  - `export async function askCourseStream(courseUuid: string, question: string, history: AskTurn[], onEvent: (event: AskStreamEvent) => void, fetchImpl?: Fetch): Promise<void>`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`frontend/src/lib/askCourseStream.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { askCourseStream, type AskStreamEvent } from './agent'

/** 주어진 바이트 조각을 차례로 흘리는 fetch. */
function fetchStreaming(chunks: Uint8Array[], status = 200): typeof fetch {
  return async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          for (const chunk of chunks) controller.enqueue(chunk)
          controller.close()
        },
      }),
      { status, headers: { 'Content-Type': 'text/event-stream' } },
    )
}

const encode = (text: string) => new TextEncoder().encode(text)

async function collect(fetchImpl: typeof fetch): Promise<AskStreamEvent[]> {
  const events: AskStreamEvent[] = []
  await askCourseStream('c', 'q', [], (e) => events.push(e), fetchImpl)
  return events
}

const frames =
  'event:citations\ndata:{"citations":["a.md"]}\n\n' +
  'event:delta\ndata:{"text":"가나"}\n\n' +
  'event:delta\ndata:{"text":"다"}\n\n' +
  'event:done\ndata:{"generatedAt":"t","model":"gpt-4o"}\n\n'

describe('askCourseStream', () => {
  it('이벤트를 순서대로 낸다', async () => {
    const events = await collect(fetchStreaming([encode(frames)]))

    expect(events).toEqual([
      { kind: 'citations', citations: ['a.md'] },
      { kind: 'delta', text: '가나' },
      { kind: 'delta', text: '다' },
      { kind: 'done', generatedAt: 't', model: 'gpt-4o' },
    ])
  })

  it('프레임이 read 경계에 걸려도 같다', async () => {
    const bytes = encode(frames)
    for (let cut = 1; cut < bytes.length; cut++) {
      const events = await collect(fetchStreaming([bytes.slice(0, cut), bytes.slice(cut)]))
      expect(events.map((e) => e.kind), `cut=${cut}`).toEqual(['citations', 'delta', 'delta', 'done'])
      expect(
        events.flatMap((e) => (e.kind === 'delta' ? [e.text] : [])).join(''),
        `cut=${cut}`,
      ).toBe('가나다')
    }
  })

  it('done 없이 끝나면 본문을 받았으면 aborted', async () => {
    const events = await collect(
      fetchStreaming([encode('event:citations\ndata:{"citations":["a.md"]}\n\nevent:delta\ndata:{"text":"미"}\n\n')]),
    )

    expect(events.at(-1)).toEqual({ kind: 'aborted' })
  })

  it('done 없이 끝나면 본문을 못 받았으면 unavailable', async () => {
    const events = await collect(fetchStreaming([encode('event:citations\ndata:{"citations":["a.md"]}\n\n')]))

    expect(events.at(-1)).toEqual({ kind: 'unavailable' })
  })

  it('서버가 unavailable 을 보내면 그것으로 끝나고 덧붙이지 않는다', async () => {
    const events = await collect(
      fetchStreaming([encode('event:unavailable\ndata:{"code":"EXPLANATION_UNAVAILABLE"}\n\n')]),
    )

    expect(events).toEqual([{ kind: 'unavailable' }])
  })

  it('연결이 아예 안 되면 던지지 않고 unavailable', async () => {
    const events = await collect(async () => {
      throw new TypeError('fetch failed')
    })

    expect(events).toEqual([{ kind: 'unavailable' }])
  })

  it('4xx/5xx 면 unavailable', async () => {
    const events = await collect(fetchStreaming([], 503))

    expect(events).toEqual([{ kind: 'unavailable' }])
  })
})
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd frontend && pnpm test -- askCourseStream`
Expected: FAIL — `askCourseStream` 이 없다

- [ ] **Step 3: 구현한다**

`frontend/src/lib/agent.ts` 의 `askCourse` 아래에 더한다:

```ts
export type AskStreamEvent =
  | { kind: 'citations'; citations: string[] }
  | { kind: 'delta'; text: string }
  | { kind: 'done'; generatedAt: string; model: string }
  | { kind: 'unavailable' }
  | { kind: 'aborted' }

/**
 * 이어 묻기를 스트림으로 받는다.
 *
 * **던지지 않고, 반드시 종결 이벤트 하나(done·unavailable·aborted)로 끝난다.** 연결 실패를
 * 예외로 올리면 백엔드가 내려간 것이 화면 전체를 무너뜨린다 — `get()` 과 같은 이유다.
 *
 * `done` 없이 연결이 끊기면, 본문을 받았으면 aborted 를, 못 받았으면 unavailable 을 낸다.
 * 받은 본문은 인용 검증을 통과했지만 문장이 미완이므로 호출자는 그것을 설명으로 남기면
 * 안 된다.
 */
export async function askCourseStream(
  courseUuid: string,
  question: string,
  history: AskTurn[],
  onEvent: (event: AskStreamEvent) => void,
  fetchImpl: Fetch = fetch,
): Promise<void> {
  let response: Response
  try {
    response = await fetchImpl(`${AGENT_BASE_URL}/agent/ask/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ courseUuid, question, history }),
      cache: 'no-store',
    })
  } catch {
    onEvent({ kind: 'unavailable' })
    return
  }
  if (!response.ok || !response.body) {
    onEvent({ kind: 'unavailable' })
    return
  }

  let terminated = false
  let deltas = 0

  const dispatch = (frame: string) => {
    let name = ''
    let data = ''
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) data += line.slice(5).replace(/^ /, '')
    }
    if (terminated || !name) return
    const payload = data ? JSON.parse(data) : {}
    switch (name) {
      case 'citations':
        onEvent({ kind: 'citations', citations: payload.citations })
        break
      case 'delta':
        deltas++
        onEvent({ kind: 'delta', text: payload.text })
        break
      case 'done':
        terminated = true
        onEvent({ kind: 'done', generatedAt: payload.generatedAt, model: payload.model })
        break
      case 'unavailable':
        terminated = true
        onEvent({ kind: 'unavailable' })
        break
      case 'aborted':
        terminated = true
        onEvent({ kind: 'aborted' })
        break
    }
  }

  const reader = response.body.getReader()
  // stream: true — 한글 UTF-8 바이트가 read 경계에서 잘려도 다음 read 와 이어 푼다.
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      let cut: number
      while ((cut = buffer.indexOf('\n\n')) >= 0) {
        dispatch(buffer.slice(0, cut))
        buffer = buffer.slice(cut + 2)
      }
    }
  } catch {
    // 연결이 도중에 끊겼다. 아래에서 종결 이벤트를 채운다.
  }

  if (!terminated) onEvent(deltas > 0 ? { kind: 'aborted' } : { kind: 'unavailable' })
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `cd frontend && pnpm test -- askCourseStream`
Expected: PASS (7개)

Run: `cd frontend && pnpm typecheck && pnpm test`
Expected: 전부 통과

- [ ] **Step 5: 변이 검사 — UTF-8 경계에 이빨이 있는지**

`decoder.decode(value, { stream: true })` 를 `decoder.decode(value)` 로 바꾼다. `프레임이 read 경계에 걸려도 같다` 가 한글 바이트 중간에서 자른 `cut` 에서 **빨개지는지** 확인하고 되돌린다.

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/lib/agent.ts frontend/src/lib/askCourseStream.test.ts
git commit -m "feat: read the follow-up stream without throwing

EventSource 는 POST 를 못 해 fetch 와 ReadableStream 으로 읽는다. 판독기는
던지지 않고 반드시 종결 이벤트 하나로 끝난다. 한글 바이트가 read 경계에서
잘리는 경우를 모든 분할 위치에서 확인한다."
```

---

### Task 8: `AskBox` — 흐르는 답변

**Files:**
- Modify: `frontend/src/app/course/[uuid]/AskBox.tsx`
- Modify: `frontend/src/app/course/[uuid]/AskBox.test.tsx`

**Interfaces:**
- Consumes: `askCourseStream`, `AskStreamEvent` (Task 7), 기존 `AskTurn`, `CitationSheet`
- Produces: 없음 (화면)

- [ ] **Step 1: 기존 테스트를 읽는다**

Run: `cat "frontend/src/app/course/[uuid]/AskBox.test.tsx"`

기존 테스트는 `askCourse` 를 흉내 내고 있을 것이다. 이 태스크에서 `AskBox` 는 `askCourseStream` 으로 바꾸므로 **흉내 대상도 바꾼다.** 기존 테스트가 지키던 명제(실패한 질문도 목록에 남는다, 이전 대화를 history 로 보낸다, 인용 칩을 누르면 문서가 열린다)는 **하나도 빠뜨리지 않고** 새 흉내로 옮긴다. 옮기기 전에 명제 목록을 보고서에 적는다.

- [ ] **Step 2: 새 명제를 테스트로 쓴다**

`AskBox.test.tsx` 에 더한다(흉내 방식은 기존 테스트의 것을 따르되 `askCourseStream` 을 흉내 낸다 — 흉내가 `onEvent` 를 차례로 부르게 한다):

- `delta` 가 올 때마다 본문이 이어 붙어 보인다 — 첫 `delta` 뒤, 둘째 `delta` 뒤 화면을 각각 확인한다
- `citations` 가 오면 본문보다 먼저 인용 칩이 보인다
- `unavailable` 이면 "답을 만들지 못했어요" 가 보이고 본문은 없다
- **`delta` 몇 개 뒤 `aborted` 면 받은 본문이 화면에서 사라지고 실패 문구가 보인다** — 미완 문장을 설명으로 남기지 않는다
- 다음 질문의 history 에는 `done` 으로 끝난 답만 들어가고, 실패하거나 중단된 것은 빠진다

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

Run: `cd frontend && pnpm test -- AskBox`
Expected: FAIL

- [ ] **Step 4: `AskBox` 를 바꾼다**

타입과 import 를 바꾼다:

```tsx
import { askCourseStream, type AskTurn } from '@/lib/agent'

type Exchange =
  | { question: string; status: 'streaming'; citations: string[]; text: string }
  | { question: string; status: 'answered'; answer: string; citations: string[] }
  | { question: string; status: 'failed' }
```

`ask()` 를 바꾼다:

```tsx
  async function ask(text: string) {
    const trimmed = text.trim()
    if (!trimmed || asking) return

    setAsking(true)
    setQuestion('')
    // 끝까지 답한 turn 만 이전 대화로 보낸다. 실패하거나 중단된 turn 은 답이 없다.
    const history: AskTurn[] = exchanges.flatMap((e) =>
      e.status === 'answered' ? [{ question: e.question, answer: e.answer }] : [],
    )

    // asking 이 동시 질문을 막으므로 이 자리는 끝날 때까지 이 질문의 것이다.
    const index = exchanges.length
    const put = (next: Exchange) => setExchanges((prev) => prev.map((e, i) => (i === index ? next : e)))
    setExchanges((prev) => [...prev, { question: trimmed, status: 'streaming', citations: [], text: '' }])

    let citations: string[] = []
    let body = ''
    await askCourseStream(courseUuid, trimmed, history, (event) => {
      switch (event.kind) {
        case 'citations':
          citations = event.citations
          put({ question: trimmed, status: 'streaming', citations, text: body })
          break
        case 'delta':
          body += event.text
          put({ question: trimmed, status: 'streaming', citations, text: body })
          break
        case 'done':
          // 본문을 확정하는 조건은 이것 하나뿐이다.
          put({ question: trimmed, status: 'answered', answer: body, citations })
          break
        case 'unavailable':
        case 'aborted':
          // 받은 본문이 있어도 버린다 — 인용은 검증됐지만 문장이 미완이다.
          put({ question: trimmed, status: 'failed' })
          break
      }
    })
    setAsking(false)
  }
```

렌더링에서 `'failed' in exchange` 분기를 `exchange.status === 'failed'` 로 바꾸고, 답변 본문을 `exchange.status === 'answered' ? exchange.answer : exchange.text` 로 보인다. 인용 칩 목록은 `answered` 와 `streaming` 둘 다에서 `exchange.citations` 로 그린다. 나머지 JSX(추천 질문, 입력창, `CitationSheet`)는 그대로 둔다.

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `cd frontend && pnpm typecheck && pnpm test`
Expected: 전부 통과

Run: `cd frontend && pnpm build`
Expected: 성공 — 백엔드 없이 빌드된다는 것 자체가 CI 검사 대상이다.

- [ ] **Step 6: 변이 검사 — 미완 본문을 남기지 않는지**

`case 'aborted':` 를 `case 'aborted': put({ question: trimmed, status: 'answered', answer: body, citations }); break` 로 바꾼다. `aborted 면 받은 본문이 사라진다` 가 **빨개지는지** 확인하고 되돌린다.

- [ ] **Step 7: 커밋**

```bash
git add "frontend/src/app/course/[uuid]/AskBox.tsx" "frontend/src/app/course/[uuid]/AskBox.test.tsx"
git commit -m "feat: show follow-up answers as they stream in

인용 칩이 본문보다 먼저 뜨고 본문은 조각마다 이어 붙는다. 본문을
확정하는 조건은 done 하나뿐이다 — 중단된 답은 인용이 검증됐어도 문장이
미완이라 설명으로 남기지 않는다."
```

---

### Task 9: 문서, 그리고 실제 모델로 한 번 (선택, 유료)

**Files:**
- Modify: `README.md`, `README.ko.md` (이어 묻기 절)

- [ ] **Step 1: 두 README 의 이어 묻기 절을 갱신한다**

두 파일 모두 `### 6.` 절(이어 묻기)에 한 단락을 더한다. 영문은 `README.md`, 한국어는 `README.ko.md` 다. 코드에 대조해서 쓴다. 담을 것:

- 답변이 흐른다 — `POST /agent/ask/stream`, 인용 칩이 먼저 뜬다
- **검증 안 된 글자는 흐르지 않는다** — 인용이 먼저 오면 검증 뒤 흘리고, 본문이 먼저 오면 쥐고 있다가 검증 뒤 낸다. 필드 순서는 속도만 좌우한다
- 스파이크 실측: 첫 글자까지 4.7~6.9초 → 1.1~3.5초 (`gpt-4o`, 3회). 표본이 작다는 것도 적는다
- `/agent/ask` 는 그대로 남는다

각 파일 자기 어조로 쓴다. 번역체로 옮기지 않는다.

- [ ] **Step 2: 빌드와 커밋**

Run: `./gradlew --no-daemon build && (cd frontend && pnpm typecheck && pnpm test && pnpm build)`
Expected: 전부 통과

```bash
git add README.md README.ko.md
git commit -m "docs: describe the streamed follow-up answers"
```

- [ ] **Step 3 (선택, 유료, 사람 승인 필요): 실제 모델로 한 번**

**이 단계는 실제 API 를 부른다. 사람의 명시적 승인 없이 실행하지 않는다.** 승인이 없으면 이 단계를 건너뛰고 건너뛰었다고 보고한다.

승인되면: 서버를 로컬로 띄우고(`HERMES_LLM_PROVIDER=openai HERMES_LLM_MODEL=gpt-4o`, 키는 `.env`), 데모 코스 하나로 `/agent/ask/stream` 을 **한 번** 부른다. 확인할 것:

- `citations` 가 `delta` 보다 먼저 오는가
- 첫 `delta` 까지의 시간이 스파이크 범위(1.1~3.5초)와 비슷한가
- `done` 으로 끝나는가

출력 전문을 파일로 받아 보고서에 붙인다 — 이전 마이그레이션에서 유료 실행의 증거가 터미널과 함께 사라질 뻔했다. 키는 출력하지 않는다.

---

## 완료 조건

- `./gradlew build` 와 `pnpm typecheck && pnpm test && pnpm build` 가 자격 증명 없이 통과한다
- 인용이 무효하면 `delta` 가 0개임을 인용 먼저·본문 먼저 **양쪽 순서에서** 테스트가 증명한다
- 파서가 **모든 분할 위치에서** 같은 결과를 낸다
- 스트리밍과 비스트리밍이 같은 `systemText`/`userText` 를 조립함을 테스트가 단언한다
- 실패 이벤트에 사유가 실리지 않음을 테스트가 단언한다
- `POST /agent/ask` 가 바뀌지 않았다

## 열려 있는 것

- **Anthropic 과 OpenRouter 의 필드 순서** — 재지 않았다. 원칙 덕에 안전은 무관하고, 순서가 다르면 느려질 뿐이다
- **첫 설명(`/agent/explain`) 스트리밍** — 이 계획 밖. 같은 파서와 게이트로 붙일 수 있다
