# Spring AI 마이그레이션 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프로바이더 구현 2개와 그것을 고르는 분기 2개를, `ExplanationProvider` 포트 뒤의 Spring AI 어댑터 하나로 접는다.

**Architecture:** 포트(`ExplanationProvider`)와 결과 타입(`ProviderResult`)은 그대로 둔다. 그 뒤의 구현만 `SpringAiExplanationProvider` 하나로 바꾸고, 프로바이더별 차이는 `ChatClients` 의 순수 옵션 함수로 내린다. `LlmSelection` 이 유일한 조립 지점이 되고 `EvalMain` 은 자기 분기를 버리고 그것을 부른다.

**Tech Stack:** Kotlin 2.2.21, Spring Boot 4.1.0, Spring AI 2.0.1 (`spring-ai-anthropic`, `spring-ai-openai`), anthropic-java (Spring AI 가 끌어오는 버전), JUnit 5 + AssertJ, Gradle Kotlin DSL

**Spec:** `docs/superpowers/specs/2026-09-13-spring-ai-migration-design.md`

## Global Constraints

- **번들 원문은 한 바이트도 달라지지 않는다.** 접두사가 흔들리면 1시간 프롬프트 캐시가 통째로 미스 난다. `PromptAssembler` 와 `BundleLoader` 는 이 계획에서 **읽기만** 한다.
- **victools 는 4.38.0 으로 고정한다.** Spring AI 2.0.1 은 5.0.0 을 끌어오지만 Anthropic SDK 는 4.x 시그니처(`SchemaGenerator.generateSchema(Type, Type[])`)를 부른다. 핀이 없으면 컴파일은 되고 런타임에 `NoSuchMethodError` 로 죽는다.
- **단위 테스트는 외부 네트워크도 API 키도 요구하지 않는다.** 루프백(127.0.0.1)은 외부 네트워크가 아니다. `./gradlew build` 는 키 없이 통과해야 한다.
- **`Refused` 는 일급 결과다.** 거절은 HTTP 200 에 빈 content 로 온다. content 를 읽기 전에 갈라야 한다.
- **평가(`./gradlew eval`)는 CI 에서 돌지 않는다.** 실제 API 를 부르므로 돈이 들고 비결정적이다.
- **프로바이더 이름은 셋 뿐이다:** `anthropic`, `openai`, `openrouter`. 운영(`HERMES_LLM_PROVIDER`)과 하네스(`./gradlew eval <이름>`)가 같은 이름을 쓴다.
- **커밋 메시지는 한국어 본문, 영어 제목**(기존 히스토리 관례). 제목은 `feat:` / `fix:` / `test:` / `chore:` / `docs:` 접두사.

---

## File Structure

| 파일 | 책임 | 처리 |
| --- | --- | --- |
| `build.gradle.kts` | Spring AI BOM, 두 스타터, victools 핀 | 수정 (Task 1) |
| `server/src/main/kotlin/com/hermes/llm/ChatClients.kt` | 프로바이더별 옵션 조립. **순수 함수만** — 클라이언트도 키도 안 만진다 | 생성 (Task 2) |
| `server/src/main/kotlin/com/hermes/llm/SpringAiExplanationProvider.kt` | `ChatClient` 호출과 `ProviderResult` 로의 변환 | 생성 (Task 4) |
| `server/src/main/kotlin/com/hermes/shared/config/LlmSelection.kt` | 이름 → `ChatClient` → 프로바이더. 유일한 조립 지점 | 수정 (Task 5, 6) |
| `harness/src/main/kotlin/com/hermes/harness/EvalMain.kt` | 자기 분기를 버리고 `LlmSelection` 을 부른다 | 수정 (Task 6) |
| `server/src/test/kotlin/com/hermes/llm/CapturingEndpoint.kt` | 루프백 캡처 테스트 헬퍼 | 생성 (Task 3) |
| `server/src/test/kotlin/com/hermes/llm/SpringAiRequestShapeTest.kt` | 나가는 JSON 본문 검증 | 생성 (Task 3, 4) |
| `server/src/test/kotlin/com/hermes/llm/ChatClientsTest.kt` | 옵션 조립 검증 | 생성 (Task 2) |
| `server/src/test/kotlin/com/hermes/shared/config/DependencyPinTest.kt` | victools 4.x 핀 검증 | 생성 (Task 1) |
| `server/src/main/kotlin/com/hermes/llm/AnthropicExplanationProvider.kt` | — | 삭제 (Task 8) |
| `server/src/main/kotlin/com/hermes/llm/OpenAiCompatibleExplanationProvider.kt` | — | 삭제 (Task 8, 조건부) |
| `server/src/test/kotlin/com/hermes/llm/AnthropicRequestShapeTest.kt` | — | 삭제 (Task 8) |
| `server/src/test/kotlin/com/hermes/llm/RawParams.kt` | — | 삭제 (Task 8) |

`ChatClients` 가 옵션만 만들고 클라이언트를 안 만드는 이유: 지금 `buildParams` 가
순수 함수인 것과 같다. 키 없이 테스트할 수 있어야 한다.

---

### Task 1: Spring AI 의존성과 victools 핀

스파이크에서 실제로 밟은 지뢰다. 핀 없이 진행하면 Task 2 가 런타임에 죽는데,
컴파일은 멀쩡해서 원인을 찾는 데 시간이 간다. 그래서 맨 앞에 둔다.

**Files:**
- Modify: `build.gradle.kts`
- Test: `server/src/test/kotlin/com/hermes/shared/config/DependencyPinTest.kt`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `org.springframework.ai:spring-ai-anthropic` 과 `spring-ai-openai` 가 컴파일 클래스패스에 있고, `com.github.victools:jsonschema-generator` 가 4.38.0 으로 해결된다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`server/src/test/kotlin/com/hermes/shared/config/DependencyPinTest.kt`:

```kotlin
package com.hermes.shared.config

import com.github.victools.jsonschema.generator.SchemaGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Type

/**
 * Spring AI 2.0.1 은 victools 5.0.0 을 끌어오지만, Anthropic SDK 의 스키마 유도는
 * 4.x 시그니처 `generateSchema(Type, Type[])` 를 부른다. 5.0.0 이 충돌에서 이기면
 * `outputConfig(Class)` 가 **런타임에** NoSuchMethodError 로 죽는다 — 컴파일은
 * 멀쩡하다. 이 저장소가 스키마를 손으로 쓰지 않고 SDK 유도에 맡긴 이유가
 * 드리프트 방지였으니, 핀이 풀리면 그 방어선이 조용히 무너진다.
 */
class DependencyPinTest {

    @Test
    fun `victools 는 4점대 시그니처를 유지한다`() {
        val method = SchemaGenerator::class.java.getMethod(
            "generateSchema",
            Type::class.java,
            Array<Type>::class.java,
        )

        assertThat(method).isNotNull()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests '*DependencyPinTest*'`
Expected: FAIL — `SchemaGenerator` 를 import 할 수 없어 컴파일 에러. (아직 Spring AI 도 victools 도 테스트 클래스패스에 직접 없다.)

- [ ] **Step 3: 의존성과 핀을 추가한다**

`build.gradle.kts` 의 `dependencyManagement { imports { ... } }` 블록에 BOM 을 더한다:

```kotlin
dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:2.1.0")
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.1")
    }
}
```

`dependencies { ... }` 블록에 두 스타터를 더한다 (`com.anthropic:anthropic-java:2.34.0` 줄은 **아직 지우지 않는다** — Task 8 까지 구 프로바이더가 살아 있어야 한다):

```kotlin
    implementation("org.springframework.ai:spring-ai-anthropic")
    implementation("org.springframework.ai:spring-ai-openai")
```

그리고 기존 `implementation("com.anthropic:anthropic-java:2.34.0")` 줄을 아래 둘로
교체한다:

```kotlin
    implementation("com.anthropic:anthropic-java:2.52.0")
    implementation("com.openai:openai-java:4.49.0")
```

**이 둘은 지우면 안 된다.** Spring AI 는 `anthropic-java-core` 와
`openai-java-core` 만 끌어오고, 클라이언트를 만드는 `AnthropicOkHttpClient` 와
`OpenAIOkHttpClient` 는 `-client-okhttp` 모듈에 있다. spring-ai-bom 은 이 둘의
버전을 관리하지 않으므로(실측 확인: 버전 없이 쓰면 `FAILED`) 명시한다. 버전은
Spring AI 2.0.1 이 끌어오는 코어와 맞춘다 — Spring AI 를 올릴 때 함께 올린다.

파일 맨 아래(`tasks.register` 들 앞)에 핀을 둔다:

```kotlin
// Spring AI 2.0.1 은 victools 5.0.0 을 끌어온다. 그런데 Anthropic SDK 의 구조화
// 출력이 4.x 시그니처를 부르므로, 5.0.0 이 이기면 스키마 유도가 런타임에
// NoSuchMethodError 로 죽는다. 컴파일은 통과하므로 이 핀이 유일한 방어선이다.
// DependencyPinTest 가 이것을 지킨다.
configurations.all {
    resolutionStrategy.force(
        "com.github.victools:jsonschema-generator:4.38.0",
        "com.github.victools:jsonschema-module-jackson:4.38.0",
        "com.github.victools:jsonschema-module-swagger-2:4.38.0",
    )
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests '*DependencyPinTest*'`
Expected: PASS

- [ ] **Step 5: 기존 테스트가 전부 살아 있는지 확인한다**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. 의존성만 늘었고 코드는 안 바뀌었으므로 기존 테스트가 전부 통과해야 한다. 실패하면 Spring AI 가 끌어온 다른 라이브러리가 충돌한 것이므로, 그 충돌을 여기서 해결하고 넘어간다.

- [ ] **Step 6: 커밋**

```bash
git add build.gradle.kts server/src/test/kotlin/com/hermes/shared/config/DependencyPinTest.kt
git commit -m "chore: add Spring AI and pin victools to the 4.x signature

Spring AI 2.0.1 이 victools 5.0.0 을 끌어오는데 Anthropic SDK 의 스키마
유도는 4.x 시그니처를 부른다. 핀이 없으면 컴파일은 통과하고 런타임에
NoSuchMethodError 로 죽는다."
```

---

### Task 2: ChatClients — 프로바이더별 옵션 조립

**Files:**
- Create: `server/src/main/kotlin/com/hermes/llm/ChatClients.kt`
- Test: `server/src/test/kotlin/com/hermes/llm/ChatClientsTest.kt`

**Interfaces:**
- Consumes: Task 1 의 Spring AI 의존성
- Produces:
  - `ChatClients.anthropicOptions(model: String): AnthropicChatOptions`
  - `ChatClients.openAiCompatibleOptions(model: String, baseUrl: String): OpenAiChatOptions`
  - `ChatClients.OPENAI_BASE_URL: String`, `ChatClients.OPENROUTER_BASE_URL: String`
  - `ChatClients.MAX_TOKENS: Int` = 16000
  - `ChatClients.explanationSchema(): JsonOutputFormat`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`server/src/test/kotlin/com/hermes/llm/ChatClientsTest.kt`:

```kotlin
package com.hermes.llm

import com.anthropic.models.messages.OutputConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicCacheStrategy
import org.springframework.ai.anthropic.AnthropicCacheTtl
import org.springframework.ai.chat.messages.MessageType

/**
 * 옵션 조립을 순수 함수로 꺼내 둔 이유는 하나다 — 키 없이 검사할 수 있어야 한다.
 * 실제로 나가는 바이트는 SpringAiRequestShapeTest 가 본다. 이 테스트가 고정하는
 * 것은 **우리가 프레임워크에 넣는 것**이다.
 */
class ChatClientsTest {

    @Test
    fun `anthropic 은 system 블록만 1시간 TTL 로 캐시한다`() {
        val options = ChatClients.anthropicOptions("claude-opus-5")
        val cache = options.cacheOptions

        assertThat(cache.strategy).isEqualTo(AnthropicCacheStrategy.SYSTEM_ONLY)
        assertThat(cache.messageTypeTtl[MessageType.SYSTEM]).isEqualTo(AnthropicCacheTtl.ONE_HOUR)
    }

    @Test
    fun `anthropic 은 effort 를 낮게 두고 스키마를 강제한다`() {
        val options = ChatClients.anthropicOptions("claude-opus-5")
        val outputConfig = options.outputConfig

        assertThat(outputConfig.effort().orElse(null)).isEqualTo(OutputConfig.Effort.LOW)
        assertThat(outputConfig.format()).isPresent()
    }

    @Test
    fun `모델과 토큰 한도가 인자와 상수를 따른다`() {
        val options = ChatClients.anthropicOptions("claude-opus-5")

        assertThat(options.model).isEqualTo("claude-opus-5")
        // 8192 가 아니다 — max_tokens 는 thinking 과 응답을 합쳐 덮고,
        // Opus 5 는 thinking 이 기본 ON 이라 8192 는 잘릴 위험이 있다.
        assertThat(options.maxTokens).isEqualTo(16000)
    }

    @Test
    fun `유도된 스키마는 두 필드를 모두 요구한다`() {
        val schema = ChatClients.explanationSchema().schema().toString()

        assertThat(schema).contains("explanation").contains("citations")
    }

    @Test
    fun `openai 호환은 주어진 baseUrl 과 모델을 쓴다`() {
        val options = ChatClients.openAiCompatibleOptions("x/y", ChatClients.OPENROUTER_BASE_URL)

        assertThat(options.model).isEqualTo("x/y")
        assertThat(options.baseUrl).isEqualTo(ChatClients.OPENROUTER_BASE_URL)
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests '*ChatClientsTest*'`
Expected: FAIL — `ChatClients` 가 없어 컴파일 에러

- [ ] **Step 3: 최소 구현을 쓴다**

`server/src/main/kotlin/com/hermes/llm/ChatClients.kt`:

```kotlin
package com.hermes.llm

import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import org.springframework.ai.anthropic.AnthropicCacheOptions
import org.springframework.ai.anthropic.AnthropicCacheStrategy
import org.springframework.ai.anthropic.AnthropicCacheTtl
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.openai.OpenAiChatOptions

/**
 * 프로바이더별 요청 옵션을 만든다.
 *
 * **순수 함수만 둔다.** 클라이언트도 키도 여기서 만지지 않는다 — 지금
 * `buildParams` 가 순수 함수인 것과 같은 이유다. 캐시 분기점과 스키마 강제가
 * 살아 있는지는 키 없이 검사할 수 있어야 한다(ChatClientsTest).
 */
object ChatClients {

    const val OPENAI_BASE_URL = "https://api.openai.com"
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api"

    // 8192 가 아니다 — max_tokens 는 thinking 과 응답 텍스트를 합쳐 덮고,
    // Opus 5 는 thinking 이 기본 ON 이다.
    const val MAX_TOKENS = 16_000

    /**
     * `Explanation` 의 JSON 스키마를 SDK 가 직접 유도하게 한다 — 손으로 다시 쓰면
     * 데이터 클래스와 드리프트한다. 유도 함수 자체는 SDK 내부(internal)라 부를 수
     * 없어, 공개 경로인 `.outputConfig(Class)` 를 최소 요청 한 번에 태워 유도된
     * 포맷만 꺼낸다. `Explanation` 타입은 고정이므로 이 값은 요청마다 달라지지 않는다.
     */
    private val derivedExplanationFormat: JsonOutputFormat by lazy {
        MessageCreateParams.builder()
            .model("claude-opus-5")
            .maxTokens(MAX_TOKENS.toLong())
            .addUserMessage("schema derivation only")
            .outputConfig(Explanation::class.java)
            .build()
            .rawParams
            .outputConfig()
            .orElseThrow()
            .format()
            .orElseThrow()
    }

    fun explanationSchema(): JsonOutputFormat = derivedExplanationFormat

    /**
     * 구 코드에 있던 우회 — `outputConfig(Class)` 와 `outputConfig(OutputConfig)` 를
     * 순서대로 두 번 불러 effort 를 살리는 — 가 필요 없다. Spring AI 는 완성된
     * `OutputConfig` 를 그대로 받는다.
     */
    fun anthropicOptions(model: String): AnthropicChatOptions =
        AnthropicChatOptions.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .cacheOptions(
                AnthropicCacheOptions.builder()
                    .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                    .messageTypeTtl(MessageType.SYSTEM, AnthropicCacheTtl.ONE_HOUR)
                    .build(),
            )
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(derivedExplanationFormat)
                    .build(),
            )
            .build() as AnthropicChatOptions

    fun openAiCompatibleOptions(model: String, baseUrl: String): OpenAiChatOptions =
        OpenAiChatOptions.builder()
            .model(model)
            .baseUrl(baseUrl)
            .maxTokens(MAX_TOKENS)
            .build() as OpenAiChatOptions
}
```

빌더 체이닝이 상위 타입(`AbstractBuilder`)을 돌려주므로 `as` 캐스트가 필요하다. 캐스트 없이 컴파일되면 캐스트를 지운다.

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests '*ChatClientsTest*'`
Expected: PASS (5개)

- [ ] **Step 5: 커밋**

```bash
git add server/src/main/kotlin/com/hermes/llm/ChatClients.kt server/src/test/kotlin/com/hermes/llm/ChatClientsTest.kt
git commit -m "feat: assemble provider options as pure functions

옵션을 순수 함수로 꺼내 키 없이 검사할 수 있게 한다. 캐시 분기점과
스키마 강제가 살아 있는지가 이 저장소의 방어선이라 테스트가 필요하다."
```

---

### Task 3: 루프백 캡처 테스트 헬퍼

나가는 JSON 본문을 그대로 보는 장치다. 기존 `RawParams.kt` 가 SDK 파라미터
객체를 들여다보던 것보다 강하다 — 실제 바이트를 본다. 키도 외부 네트워크도
쓰지 않는다.

**Files:**
- Create: `server/src/test/kotlin/com/hermes/llm/CapturingEndpoint.kt`
- Test: `server/src/test/kotlin/com/hermes/llm/CapturingEndpointTest.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `CapturingEndpoint()` — `AutoCloseable`
  - `CapturingEndpoint.baseUrl: String` (예: `http://127.0.0.1:53411`)
  - `CapturingEndpoint.capturedBody(): JsonNode` — 마지막으로 받은 POST 본문. 아직 없으면 `IllegalStateException`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`server/src/test/kotlin/com/hermes/llm/CapturingEndpointTest.kt`:

```kotlin
package com.hermes.llm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class CapturingEndpointTest {

    @Test
    fun `받은 POST 본문을 그대로 돌려준다`() {
        CapturingEndpoint().use { endpoint ->
            val request = HttpRequest.newBuilder(URI.create("${endpoint.baseUrl}/v1/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"hello":"world"}"""))
                .build()

            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

            assertThat(endpoint.capturedBody()["hello"].asText()).isEqualTo("world")
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests '*CapturingEndpointTest*'`
Expected: FAIL — `CapturingEndpoint` 가 없어 컴파일 에러

- [ ] **Step 3: 최소 구현을 쓴다**

`server/src/test/kotlin/com/hermes/llm/CapturingEndpoint.kt`:

```kotlin
package com.hermes.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * 요청 본문을 받아 두고 500 을 돌려주는 루프백 엔드포인트.
 *
 * 캐시가 맞느냐는 결국 "직렬화된 요청이 같으냐"의 문제라, SDK 파라미터 객체가
 * 아니라 실제로 나가는 바이트를 봐야 한다. 127.0.0.1 만 듣고 키를 요구하지
 * 않으므로 `./gradlew build` 가 여전히 네트워크에도 자격 증명에도 묶이지 않는다.
 *
 * 응답을 500 으로 주는 이유: 성공 응답을 흉내 내려면 프로바이더마다 다른 스키마를
 * 지어내야 하고, 그 가짜가 진짜와 어긋나는 순간 테스트가 거짓말을 시작한다.
 * 여기서 필요한 것은 요청뿐이다.
 */
class CapturingEndpoint : AutoCloseable {

    private val mapper = ObjectMapper()
    private var body: ByteArray? = null

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                body = exchange.requestBody.readBytes()
                val payload = """{"type":"error","error":{"type":"api_error","message":"captured"}}"""
                    .toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(500, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            start()
        }

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    fun capturedBody(): JsonNode =
        mapper.readTree(body ?: error("no request was captured — did the call reach $baseUrl?"))

    override fun close() = server.stop(0)
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests '*CapturingEndpointTest*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add server/src/test/kotlin/com/hermes/llm/CapturingEndpoint.kt server/src/test/kotlin/com/hermes/llm/CapturingEndpointTest.kt
git commit -m "test: capture outgoing request bodies over loopback

캐시가 맞느냐는 직렬화된 요청이 같으냐의 문제라, SDK 파라미터 객체가
아니라 실제 바이트를 봐야 한다. 루프백만 쓰므로 키도 외부 네트워크도
필요 없다."
```

---

### Task 4: SpringAiExplanationProvider

**Files:**
- Create: `server/src/main/kotlin/com/hermes/llm/SpringAiExplanationProvider.kt`
- Test: `server/src/test/kotlin/com/hermes/llm/SpringAiRequestShapeTest.kt`

**Interfaces:**
- Consumes: `ChatClients.anthropicOptions(model)` (Task 2), `CapturingEndpoint` (Task 3), 기존 `ExplanationProvider` / `ProviderResult` / `Answered` / `Refused` / `Failed` / `ProviderUsage`
- Produces: `SpringAiExplanationProvider(name: String, chatClient: ChatClient) : ExplanationProvider`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`server/src/test/kotlin/com/hermes/llm/SpringAiRequestShapeTest.kt`:

```kotlin
package com.hermes.llm

import com.hermes.context.BundleLoader
import com.hermes.context.PromptAssembler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.client.ChatClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient

/**
 * 실제로 나가는 요청 본문을 검사한다.
 *
 * 구 `AnthropicRequestShapeTest` 가 `buildParams` 라는 순수 함수의 반환값을 보던
 * 자리다. Spring AI 에는 그런 함수가 없어 요청이 모델 안에서 조립되므로, 나가는
 * 바이트를 직접 본다. 이쪽이 더 강하다 — 프레임워크가 무엇을 덧붙이거나 지워도
 * 여기서 잡힌다.
 */
class SpringAiRequestShapeTest {

    private val systemText = PromptAssembler(BundleLoader.load()).systemText
    private val factsJson = """{"courseUuid":"3f6c2b18-9a4d-4c77-8b21-5e0f7c9d1a44"}"""

    private fun provider(baseUrl: String): SpringAiExplanationProvider {
        val client = AnthropicOkHttpClient.builder()
            .apiKey("sk-ant-not-a-real-key")
            .baseUrl(baseUrl)
            .maxRetries(0)
            .build()
        val model = AnthropicChatModel.builder()
            .anthropicClient(client)
            .options(ChatClients.anthropicOptions("claude-opus-5"))
            .build()
        return SpringAiExplanationProvider("anthropic", ChatClient.create(model))
    }

    @Test
    fun `번들은 system 블록에 1시간 캐시 분기점과 함께 들어간다`() {
        CapturingEndpoint().use { endpoint ->
            provider(endpoint.baseUrl).explain(systemText, factsJson)
            val body = endpoint.capturedBody()

            assertThat(body["system"]).hasSize(1)
            assertThat(body["system"][0]["text"].asText()).isEqualTo(systemText)
            assertThat(body["system"][0]["cache_control"]["ttl"].asText()).isEqualTo("1h")
        }
    }

    @Test
    fun `매 요청 달라지는 사실은 캐시 분기점 뒤 user 턴에 있다`() {
        CapturingEndpoint().use { endpoint ->
            provider(endpoint.baseUrl).explain(systemText, factsJson)
            val body = endpoint.capturedBody()

            assertThat(body["messages"]).hasSize(1)
            assertThat(body["messages"][0]["role"].asText()).isEqualTo("user")
            assertThat(body["messages"][0]["content"].asText()).isEqualTo(factsJson)
        }
    }

    @Test
    fun `모델과 토큰 한도와 effort 가 스펙과 일치한다`() {
        CapturingEndpoint().use { endpoint ->
            provider(endpoint.baseUrl).explain(systemText, factsJson)
            val body = endpoint.capturedBody()

            assertThat(body["model"].asText()).isEqualTo("claude-opus-5")
            assertThat(body["max_tokens"].asInt()).isEqualTo(16000)
            assertThat(body["output_config"]["effort"].asText()).isEqualTo("low")
        }
    }

    @Test
    fun `엔드포인트가 죽어도 예외가 아니라 Failed 로 끝난다`() {
        CapturingEndpoint().use { endpoint ->
            val result = provider(endpoint.baseUrl).explain(systemText, factsJson)

            assertThat(result).isInstanceOf(Failed::class.java)
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests '*SpringAiRequestShapeTest*'`
Expected: FAIL — `SpringAiExplanationProvider` 가 없어 컴파일 에러

- [ ] **Step 3: 최소 구현을 쓴다**

`server/src/main/kotlin/com/hermes/llm/SpringAiExplanationProvider.kt`:

```kotlin
package com.hermes.llm

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt

/**
 * 포트 뒤의 단일 구현. 프로바이더별 차이는 주입된 `ChatClient` 가 들고 있고,
 * 여기서는 어느 프로바이더인지 알 필요가 없다.
 *
 * 포트를 남긴 이유는 바뀌지 않았다 — "같은 프롬프트와 같은 검증으로 비교한다"는
 * 보장이 프레임워크가 아니라 이 저장소 코드에 남아 있어야 한다.
 */
class SpringAiExplanationProvider(
    override val name: String,
    private val chatClient: ChatClient,
) : ExplanationProvider {

    private val log = LoggerFactory.getLogger(SpringAiExplanationProvider::class.java)

    override fun explain(systemText: String, userText: String): ProviderResult = try {
        val response = chatClient
            .prompt(Prompt(listOf(SystemMessage(systemText), UserMessage(userText))))
            .call()
            .chatResponse()
            ?: return Failed("response was null")

        val generation = response.result ?: return Failed("response carried no generation")

        // 거절을 content 읽기 전에 가른다. 거절은 HTTP 200 에 빈 content 로 오므로
        // 본문을 무조건 읽는 코드는 여기서 깨진다.
        //
        // 구 코드가 싣던 stopDetails.category 는 여기까지 오지 않는다 — Spring AI 는
        // StopReason.toString() 을 finishReason 문자열로만 노출한다. 사유 해상도는
        // 떨어지지만 Refused 와 Failed 를 가르는 판단에는 이것으로 충분하다.
        if (generation.metadata?.finishReason.equals(REFUSAL, ignoreCase = true)) {
            return Refused(category = null)
        }

        val text = generation.output?.text
        if (text.isNullOrBlank()) return Failed("response carried no structured content")

        val usage = response.metadata.usage
        Answered(
            explanation = text,
            usage = ProviderUsage(
                cacheReadTokens = usage.nativeUsage.cacheReadInputTokens(),
                cacheCreationTokens = usage.nativeUsage.cacheCreationInputTokens(),
                inputTokens = usage.promptTokens.toLong(),
                outputTokens = usage.completionTokens.toLong(),
            ),
        )
    } catch (e: Exception) {
        // 예외의 정체를 지우지 않는다 — 영구적 프로그래밍 오류가 소켓 타임아웃과
        // 구분이 안 되면, 호출자가 Failed 를 재시도할 때 전액을 들여 같은 버그를
        // 반복한다.
        log.warn("$name explain failed", e)
        Failed("${e::class.simpleName}: ${e.message}")
    }

    private companion object {
        const val REFUSAL = "refusal"
    }
}
```

`usage.nativeUsage` 의 실제 타입은 프로바이더마다 다르다. 컴파일이 안 되면 Step 3.1 로 간다.

- [ ] **Step 3.1: 캐시 토큰 접근 경로를 실측한다 (Step 3 이 컴파일되지 않을 때만)**

`nativeUsage` 는 `Any?` 이거나 프로바이더별 타입일 수 있다. 실제 타입을 확인한다:

```bash
cd /tmp && unzip -o ~/.gradle/caches/modules-2/files-2.1/org.springframework.ai/spring-ai-model/2.0.1/*/spring-ai-model-2.0.1.jar 'org/springframework/ai/chat/metadata/Usage.class' -d usagex
javap -p /tmp/usagex/org/springframework/ai/chat/metadata/Usage.class
```

Anthropic 의 네이티브 usage 는 `com.anthropic.models.messages.Usage` 이고
`cacheReadInputTokens()` / `cacheCreationInputTokens()` 가 `Optional<Long>` 이다.
`Optional` 이면 `.orElse(0L)` 을 붙인다. OpenAI 계열은 캐시 토큰이 없으므로 안전하게
0 으로 떨어지도록 `runCatching { ... }.getOrDefault(0L)` 로 감싼다.

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests '*SpringAiRequestShapeTest*'`
Expected: PASS (4개)

- [ ] **Step 5: 구 테스트와 나란히 통과하는지 확인한다**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. 구 `AnthropicRequestShapeTest` 도 아직 살아 있고 둘 다 통과해야 한다 — 같은 명제를 두 방법으로 재는 상태이고, 이것이 Task 8 에서 구 테스트를 지울 근거가 된다.

- [ ] **Step 6: 커밋**

```bash
git add server/src/main/kotlin/com/hermes/llm/SpringAiExplanationProvider.kt server/src/test/kotlin/com/hermes/llm/SpringAiRequestShapeTest.kt
git commit -m "feat: add the Spring AI provider behind the existing port

포트와 ProviderResult 는 그대로다. 구 AnthropicRequestShapeTest 와
나란히 통과하므로 같은 명제를 두 방법으로 재고 있다 — 구 테스트를
지울 근거가 여기서 생긴다."
```

---

### Task 5: LlmSelection 이 Spring AI 로 anthropic 을 만든다

한 프로바이더씩 옮긴다. 셋을 한꺼번에 바꾸면 `eval` 이 깨졌을 때 어느 전환
때문인지 가려낼 수 없다.

**Files:**
- Modify: `server/src/main/kotlin/com/hermes/shared/config/LlmSelection.kt`
- Test: `server/src/test/kotlin/com/hermes/shared/config/LlmSelectionTest.kt`

**Interfaces:**
- Consumes: `ChatClients.anthropicOptions(model)` (Task 2), `SpringAiExplanationProvider` (Task 4)
- Produces: `LlmSelection.provider(name, model, env)` 가 `anthropic` 일 때 `SpringAiExplanationProvider` 를 돌려준다. 시그니처는 바뀌지 않는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`LlmSelectionTest` 의 `기본값은 anthropic 이다` 를 아래로 **교체**하고, 새 테스트를 하나 더한다:

```kotlin
    @Test
    fun `anthropic 은 Spring AI 로 돈다`() {
        val provider = LlmSelection.provider("anthropic", "claude-opus-5") { "key" }

        assertThat(provider).isInstanceOf(SpringAiExplanationProvider::class.java)
        assertThat(provider.name).isEqualTo("anthropic")
    }

    @Test
    fun `anthropic 도 설정한 모델을 따른다`() {
        // 구 코드는 이 인자를 버리고 claude-opus-5 를 박아 두었다. docs/deploy.md 는
        // HERMES_LLM_MODEL 이 "프로바이더에 맞는 모델 이름"이라고 약속하므로,
        // 문서가 약속한 것을 코드가 지키지 않던 상태였다.
        val options = ChatClients.anthropicOptions("claude-sonnet-5")

        assertThat(options.model).isEqualTo("claude-sonnet-5")
    }
```

import 에 `com.hermes.llm.SpringAiExplanationProvider` 와 `com.hermes.llm.ChatClients` 를 더한다.

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests '*LlmSelectionTest*'`
Expected: FAIL — `anthropic 은 Spring AI 로 돈다` 가 `AnthropicExplanationProvider` 를 받아 실패

- [ ] **Step 3: anthropic 분기를 바꾼다**

`LlmSelection.kt` 의 `"anthropic" ->` 줄을 교체한다:

```kotlin
            "anthropic" -> SpringAiExplanationProvider(
                name = "anthropic",
                chatClient = ChatClient.create(
                    AnthropicChatModel.builder()
                        .anthropicClient(AnthropicOkHttpClient.fromEnv())
                        .options(ChatClients.anthropicOptions(model))
                        .build(),
                ),
            )
```

import 를 정리한다: `com.hermes.llm.AnthropicExplanationProvider` 를 지우고
`com.hermes.llm.ChatClients`, `com.hermes.llm.SpringAiExplanationProvider`,
`org.springframework.ai.anthropic.AnthropicChatModel`,
`org.springframework.ai.chat.client.ChatClient` 를 더한다.
`AnthropicOkHttpClient` import 는 그대로 둔다.

`application.yml` 의 `hermes.llm.model` 기본값은 **건드리지 않는다** — `claude-opus-5`
그대로다. 설정을 손대지 않은 배포가 지금과 같은 모델로 돌아야 한다.

`model` 인자가 이제 anthropic 에서도 쓰인다는 KDoc 한 줄을 더한다:

```kotlin
 * 세 프로바이더 모두 `hermes.llm.model` 을 따른다. 이전에는 anthropic 만 이 인자를
 * 버리고 모델을 코드에 박아 두어, `docs/deploy.md` 가 약속한 `HERMES_LLM_MODEL` 이
 * 그 경로에서만 조용히 무시됐다.
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests '*LlmSelectionTest*'`
Expected: PASS

- [ ] **Step 5: 전체 빌드와 컨텍스트 기동을 확인한다**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. `ApplicationContextTest` 와 `ModuleBoundaryTest` 가 특히 중요하다 — 새 import 가 모듈 경계를 넘으면 여기서 잡힌다.

- [ ] **Step 6: 커밋**

```bash
git add server/src/main/kotlin/com/hermes/shared/config/LlmSelection.kt server/src/test/kotlin/com/hermes/shared/config/LlmSelectionTest.kt
git commit -m "feat: run the anthropic provider through Spring AI

anthropic 이 hermes.llm.model 을 따르게 된다. 이전에는 이 인자를 버리고
모델을 코드에 박아 두어, deploy.md 가 약속한 HERMES_LLM_MODEL 이 이
경로에서만 조용히 무시됐다."
```

---

### Task 6: EvalMain 이 LlmSelection 을 부른다

이름 셋을 두 군데서 손으로 맞추던 중복을 없앤다. 이 중복이 "잰 것과 띄우는 것이
갈라지는" 위험의 출처다.

**Files:**
- Modify: `harness/src/main/kotlin/com/hermes/harness/EvalMain.kt:65-81`
- Test: `server/src/test/kotlin/com/hermes/shared/config/LlmSelectionTest.kt`

**Interfaces:**
- Consumes: `LlmSelection.provider(name, model, env)` (Task 5)
- Produces: 없음 (하네스는 진입점이다)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`LlmSelectionTest` 에 더한다. 하네스는 테스트가 없으므로, 하네스가 의존하게 될
계약을 여기서 고정한다:

```kotlin
    @Test
    fun `세 이름 모두 같은 함수 하나로 만들어진다`() {
        val names = listOf("anthropic", "openai", "openrouter")

        val made = names.map { LlmSelection.provider(it, "some-model") { "key" }.name }

        assertThat(made).isEqualTo(names)
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests '*LlmSelectionTest*'`
Expected: 이 테스트는 Task 5 이후 **통과할 수도 있다**. 통과하면 그대로 두고 Step 3 으로 간다 — 이 테스트의 목적은 Task 6 의 회귀 방지이지 새 동작 요구가 아니다.

- [ ] **Step 3: EvalMain 의 분기를 지운다**

`EvalMain.kt` 의 `val provider: ExplanationProvider = when (providerName) { ... }` 블록 전체(70-81줄 부근)를 아래로 교체한다:

```kotlin
    // 프로바이더 조립은 LlmSelection 하나에만 둔다. 여기에 분기를 복제하면
    // 하네스가 재는 것과 서버가 띄우는 것이 조용히 갈라진다 — 이 저장소가
    // 한 번 겪은 일이다.
    val model = when (providerName) {
        "anthropic" -> System.getenv("ANTHROPIC_MODEL") ?: "claude-opus-5"
        "openrouter" -> System.getenv("OPENROUTER_MODEL") ?: "nvidia/nemotron-nano-9b-v2:free"
        "openai" -> System.getenv("OPENAI_MODEL") ?: "gpt-4o-mini"
        else -> error("unknown provider: $providerName (expected anthropic, openrouter, or openai)")
    }
    val provider: ExplanationProvider = LlmSelection.provider(providerName, model, System::getenv)
```

import 를 정리한다: `com.hermes.llm.AnthropicExplanationProvider`,
`com.hermes.llm.OpenAiCompatibleExplanationProvider`,
`com.anthropic.client.okhttp.AnthropicOkHttpClient` 를 지우고
`com.hermes.shared.config.LlmSelection` 을 더한다.
`com.hermes.llm.ExplanationProvider` 는 남긴다.

`requireCredential` 이 더는 쓰이지 않으면 지운다. 다른 곳(`HANJEOK_BASE_URL`)에서
쓰고 있으면 남긴다 — 컴파일러 경고로 확인한다.

- [ ] **Step 4: 빌드와 하네스 컴파일을 확인한다**

Run: `./gradlew build harnessClasses`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 하네스가 키 없이도 이름을 제대로 거절하는지 확인한다**

Run: `./gradlew eval --args="nonsense 1"`
Expected: 실패하되, 메시지가 `unknown provider: nonsense (expected anthropic, openrouter, or openai)` 여야 한다. API 호출은 일어나지 않는다.

- [ ] **Step 6: 커밋**

```bash
git add harness/src/main/kotlin/com/hermes/harness/EvalMain.kt server/src/test/kotlin/com/hermes/shared/config/LlmSelectionTest.kt
git commit -m "refactor: build harness providers through LlmSelection

프로바이더 조립이 두 군데 있었다. 그 중복이 잰 것과 띄우는 것이
갈라지는 경로다 — 이 저장소가 한 번 겪었다."
```

---

### Task 7: openai 와 openrouter 를 Spring AI 로 옮기고 다시 잰다

**돈이 든다.** 이 태스크만 실제 API 를 부른다. `.env` 에
`OPENAI_API_KEY` 와 `OPENROUTER_API_KEY` 가 있어야 한다.

**Files:**
- Modify: `server/src/main/kotlin/com/hermes/shared/config/LlmSelection.kt`
- Test: `server/src/test/kotlin/com/hermes/shared/config/LlmSelectionTest.kt`

**Interfaces:**
- Consumes: `ChatClients.openAiCompatibleOptions(model, baseUrl)`, `ChatClients.OPENAI_BASE_URL`, `ChatClients.OPENROUTER_BASE_URL` (Task 2)
- Produces: `LlmSelection.provider` 가 세 이름 모두에 대해 `SpringAiExplanationProvider` 를 돌려준다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`LlmSelectionTest` 의 `openai 를 고를 수 있다` 를 교체한다:

```kotlin
    @Test
    fun `openai 도 Spring AI 로 돈다`() {
        val provider = LlmSelection.provider("openai", "gpt-4o") { "key" }

        assertThat(provider).isInstanceOf(SpringAiExplanationProvider::class.java)
        assertThat(provider.name).isEqualTo("openai")
    }

    @Test
    fun `openrouter 도 Spring AI 로 돈다`() {
        val provider = LlmSelection.provider("openrouter", "x/y") { "key" }

        assertThat(provider).isInstanceOf(SpringAiExplanationProvider::class.java)
        assertThat(provider.name).isEqualTo("openrouter")
    }
```

import 에서 `com.hermes.llm.OpenAiCompatibleExplanationProvider` 를 지운다.

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests '*LlmSelectionTest*'`
Expected: FAIL — 아직 `OpenAiCompatibleExplanationProvider` 를 돌려준다

- [ ] **Step 3: 두 분기를 바꾼다**

```kotlin
            "openai" -> springAiOpenAiCompatible(
                name = "openai",
                apiKey = require(env, "OPENAI_API_KEY"),
                model = model,
                baseUrl = ChatClients.OPENAI_BASE_URL,
            )
            "openrouter" -> springAiOpenAiCompatible(
                name = "openrouter",
                apiKey = require(env, "OPENROUTER_API_KEY"),
                model = model,
                baseUrl = ChatClients.OPENROUTER_BASE_URL,
            )
```

같은 파일 안에 private 헬퍼를 둔다:

```kotlin
    private fun springAiOpenAiCompatible(
        name: String,
        apiKey: String,
        model: String,
        baseUrl: String,
    ): ExplanationProvider =
        SpringAiExplanationProvider(
            name = name,
            chatClient = ChatClient.create(
                OpenAiChatModel.builder()
                    .openAiClient(
                        OpenAIOkHttpClient.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .build(),
                    )
                    .options(ChatClients.openAiCompatibleOptions(model, baseUrl))
                    .build(),
            ),
        )
```

빌더 이름은 실측 확인했다 — `OpenAiChatModel.Builder` 에는 `openAiApi` 도
`defaultOptions` 도 없고, Anthropic 쪽과 같은 모양인 `openAiClient(OpenAIClient)` 와
`options(OpenAiChatOptions)` 뿐이다. import 는
`com.openai.client.okhttp.OpenAIOkHttpClient`,
`org.springframework.ai.openai.OpenAiChatModel`.

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: OpenAI 를 다시 잰다 (돈이 든다)**

Run: `./gradlew eval --args="openai 5"`
Expected: 금지 행동 8종의 위반율이 **전부 0%**. 이전 측정과 같아야 한다.

0% 가 아니면 멈추고 보고한다. 조립이 바뀌어 모델 행동이 달라졌다는 뜻이고,
그것이 이 태스크가 측정을 요구하는 이유다.

- [ ] **Step 6: OpenRouter 를 다시 잰다 (돈이 든다)**

Run: `./gradlew eval --args="openrouter 5"`
Expected: 실행이 끝나고 숫자가 나온다.

**스키마 오류로 죽으면** 그것이 설계가 예상한 분기다 — 현행 코드는
`response_format: json_schema` 를 피하고 `tool_choice` 로 강제했는데, 무료 티어
모델이 json_schema 를 못 받기 때문이다. 멈추고 보고한다. 선택지는 둘이고
(프로바이더를 접거나, `OpenAiChatOptions.toolChoice` 로 강제를 재현하거나)
**측정 결과를 보고 사람이 정한다.** 계획이 미리 정하지 않는다.

- [ ] **Step 7: 측정 결과를 커밋한다**

```bash
git add server/src/main/kotlin/com/hermes/shared/config/LlmSelection.kt server/src/test/kotlin/com/hermes/shared/config/LlmSelectionTest.kt
git commit -m "feat: run the OpenAI-compatible providers through Spring AI

조립이 바뀌었으므로 다시 쟀다. eval openai 5 에서 위반율 8종이
그대로 0% 다 — 잰 것을 띄운다는 원칙이 이 커밋에서 지켜진다."
```

커밋 메시지의 숫자는 **실제 측정값으로 고쳐 쓴다.** 0% 가 아니었으면 그 값을 적는다.

---

### Task 8: 구 프로바이더와 구 테스트를 지운다

**Files:**
- Delete: `server/src/main/kotlin/com/hermes/llm/AnthropicExplanationProvider.kt`
- Delete: `server/src/test/kotlin/com/hermes/llm/AnthropicRequestShapeTest.kt`
- Delete: `server/src/test/kotlin/com/hermes/llm/RawParams.kt`
- Delete (조건부): `server/src/main/kotlin/com/hermes/llm/OpenAiCompatibleExplanationProvider.kt`, `server/src/test/kotlin/com/hermes/llm/OpenAiCompatibleRequestShapeTest.kt`
- Modify: `build.gradle.kts`

**Interfaces:**
- Consumes: Task 7 까지의 전부
- Produces: `llm` 패키지에 `ExplanationProvider`, `ProviderResult` 계층, `ChatClients`, `SpringAiExplanationProvider` 만 남는다

- [ ] **Step 1: 구 테스트의 명제가 전부 옮겨졌는지 대조한다**

`AnthropicRequestShapeTest` 의 테스트 이름을 하나씩 읽고 `SpringAiRequestShapeTest`
에 대응이 있는지 확인한다.

| 구 테스트 | 대응 |
| --- | --- |
| 번들은 system 블록에 1시간 캐시 분기점과 함께 들어간다 | 같은 이름으로 존재 |
| 매 요청 달라지는 사실은 캐시 분기점 뒤 user 턴에 있다 | 같은 이름으로 존재 |
| 모델과 토큰 한도가 스펙과 일치한다 | `모델과 토큰 한도와 effort 가 스펙과 일치한다` |
| 같은 입력이면 system 블록이 캐시 제어까지 완전히 같다 | **없다 → Step 2 에서 추가** |
| effort 는 LOW 이고 스키마는 Explanation 의 필드를 요구한다 | `ChatClientsTest` 의 `anthropic 은 effort 를 낮게 두고 스키마를 강제한다` + `유도된 스키마는 두 필드를 모두 요구한다`, 그리고 `SpringAiRequestShapeTest` 의 `모델과 토큰 한도와 effort 가 스펙과 일치한다` |

구 파일은 테스트 다섯 개다. 위 표가 전수이고, 대조해서 다르면 멈추고 보고한다.
**명제를 옮기기 전에는 지우지 않는다.**

- [ ] **Step 2: 빠진 명제를 추가한다**

`SpringAiRequestShapeTest` 에 더한다:

```kotlin
    @Test
    fun `같은 입력이면 요청 본문이 바이트까지 완전히 같다`() {
        fun capture(): String = CapturingEndpoint().use { endpoint ->
            provider(endpoint.baseUrl).explain(systemText, factsJson)
            endpoint.capturedBody().toString()
        }

        // 접두사가 1바이트만 흔들려도 1시간 프롬프트 캐시는 통째로 미스 난다.
        assertThat(capture()).isEqualTo(capture())
    }
```

Run: `./gradlew test --tests '*SpringAiRequestShapeTest*'`
Expected: PASS (5개)

- [ ] **Step 3: 구 파일을 지운다**

```bash
git rm server/src/main/kotlin/com/hermes/llm/AnthropicExplanationProvider.kt \
       server/src/test/kotlin/com/hermes/llm/AnthropicRequestShapeTest.kt \
       server/src/test/kotlin/com/hermes/llm/RawParams.kt
```

Task 7 Step 6 에서 OpenRouter 가 정상이었으면 함께 지운다:

```bash
git rm server/src/main/kotlin/com/hermes/llm/OpenAiCompatibleExplanationProvider.kt \
       server/src/test/kotlin/com/hermes/llm/OpenAiCompatibleRequestShapeTest.kt
```

OpenRouter 를 `tool_choice` 로 되살리기로 했으면 이 둘은 **남긴다.**

- [ ] **Step 4: 쓰이지 않는 의존성을 정리한다**

`build.gradle.kts` 에서 `implementation("com.anthropic:anthropic-java:2.34.0")` 를
지운다 — Spring AI 가 자기 버전을 끌어온다. victools 핀은 **그대로 둔다.**

- [ ] **Step 5: 전체 검증**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

Run: `./gradlew eval --args="openai 3"`
Expected: 위반율 8종이 0% 유지. 삭제가 배선을 건드리지 않았음을 확인한다. (돈이 든다.)

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "refactor: drop the hand-rolled providers

명제는 전부 SpringAiRequestShapeTest 로 옮겼다. 구 테스트가 SDK
파라미터 객체를 보던 자리를, 실제로 나가는 바이트를 보는 테스트가
대신한다."
```

---

### Task 9: 문서를 코드와 맞춘다

`docs/deploy.md` 가 약속하는 것과 코드가 하는 것이 어긋나면 배포가 조용히
기본값으로 돈다. Task 5 에서 동작이 하나 바뀌었다.

**Files:**
- Modify: `docs/deploy.md`
- Modify: `README.md`
- Test: `server/src/test/kotlin/com/hermes/shared/config/DeployDocumentationTest.kt`

**Interfaces:**
- Consumes: Task 8 까지의 전부
- Produces: 없음 (마지막 태스크)

- [ ] **Step 1: 기존 문서 테스트가 무엇을 검사하는지 읽는다**

Run: `cat server/src/test/kotlin/com/hermes/shared/config/DeployDocumentationTest.kt`

이 테스트가 `docs/deploy.md` 의 환경 변수 표와 `application.yml` 을 대조하고
있으면, 변수 목록은 바뀌지 않았으므로 그대로 통과해야 한다. 통과하는지 먼저
확인한다:

Run: `./gradlew test --tests '*DeployDocumentationTest*'`
Expected: PASS

- [ ] **Step 2: deploy.md 의 모델 행을 고친다**

`HERMES_LLM_MODEL` 행의 설명을 바꾼다:

```
| `HERMES_LLM_MODEL` | 아니오 | `claude-opus-5` | 프로바이더에 맞는 모델 이름. **세 프로바이더 모두 이 값을 따른다** — 이전에는 anthropic 만 이 값을 무시하고 코드에 박힌 모델로 돌았다 |
```

- [ ] **Step 3: README 의 프로바이더 절을 고친다**

README `### 4. 프로바이더 교체` 절에서 "Anthropic 직접 호출과 OpenRouter 무료
티어" 를 설명하는 문단 뒤에 한 문장을 더한다:

```
포트 뒤의 구현은 Spring AI 어댑터 하나이고, 프로바이더별 차이는 `ChatClients` 의 옵션 조립으로만 나타납니다. 포트를 남긴 이유는 그대로입니다 — 비교의 공정성이 프레임워크가 아니라 이 저장소 코드에 있어야 합니다.
```

- [ ] **Step 4: 문서 테스트와 전체 빌드를 확인한다**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add docs/deploy.md README.md
git commit -m "docs: record that every provider now honours HERMES_LLM_MODEL

문서가 약속한 것을 코드가 지키지 않던 자리다. 이제 셋 다 따른다."
```

---

## 완료 조건

- `./gradlew build` 가 키 없이 통과한다
- `./gradlew eval --args="openai 5"` 의 위반율 8종이 0% 다
- `server/src/main/kotlin/com/hermes/llm/` 에 프로바이더 구현이 하나뿐이다
- 프로바이더 조립 지점이 `LlmSelection` 하나다
- 나가는 요청 본문이 마이그레이션 전과 같다 (`SpringAiRequestShapeTest`)

## 열려 있는 결정

**OpenRouter 의 거취** (Task 7 Step 6 에서 결정). 무료 티어가 `json_schema` 를
받으면 그대로 두고, 못 받으면 접거나 `tool_choice` 로 되살린다. 측정 결과를
보고 사람이 정한다 — 계획이 미리 정하지 않는다.

**`cache_read_input_tokens` 실측.** `ANTHROPIC_API_KEY` 가 생기면 anthropic 으로
두 번 호출해 두 번째의 캐시 읽기가 번들 크기만큼인지 확인한다. 요청이 바이트로
같으니 맞아야 하지만, 그것은 아직 추론이다.
