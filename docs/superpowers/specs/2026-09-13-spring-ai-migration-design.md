# ExplanationProvider 를 Spring AI 어댑터 하나로

- 날짜: 2026-09-13
- 상태: 설계 확정, 구현 전
- 범위: `server` 의 `llm` 모듈과 `shared/config`, `harness` 의 프로바이더 선택

## 왜

프로바이더 구현이 둘이고(`AnthropicExplanationProvider` 113줄,
`OpenAiCompatibleExplanationProvider` 164줄), 그 둘을 고르는 분기가 또 둘이다
(`LlmSelection` 과 `EvalMain`). 같은 세 이름을 두 군데서 손으로 맞추고 있고,
README 가 걱정하는 "잰 것과 띄우는 것이 갈라지는" 상황이 정확히 이 중복에서 나온다.

여기에 운영 지표가 없다. 토큰도 지연도 캐시 히트도 로그를 긁어야 보인다.

Spring AI 2.0 이 이 둘을 동시에 해결한다. 2.0.0 GA 가 2026-06-12 에 나왔고
(현재 2.0.1), Spring Boot 4.0/4.1 과 Framework 7.0 을 대상으로 한다. 이 저장소는
Boot 4.1.0 이라 그대로 맞물린다.

## 무엇을 지켜야 하는가

이 마이그레이션이 깨뜨리면 안 되는 것들이다. 전부 기존 설계가 명시적으로 방어하던 것이다.

1. **번들 원문은 한 바이트도 달라지지 않는다.** 접두사가 흔들리면 1시간 프롬프트
   캐시가 통째로 미스 난다.
2. **포트가 보장하는 비교의 공정성.** 프로바이더 비교가 서로 다른 조립 경로를 타면
   측정되는 것은 모델이 아니라 프롬프트다.
3. **`Refused` 는 일급 결과다.** 거절은 HTTP 200 에 빈 content 로 오고, 그것을
   `Failed` 와 뭉개면 재시도가 전액을 들여 같은 거절을 반복한다.
4. **단위 테스트는 네트워크도 API 키도 요구하지 않는다.**

## 사전 검증 (2026-09-13, 스파이크)

키 없이 잴 수 있는 만큼 쟀다. 로컬 캡처 서버에 양쪽 `baseUrl` 을 돌려 나가는 요청
본문을 받아 비교했다.

```
baseline (AnthropicExplanationProvider.buildParams)  sha256 2aca09f5b4b5f420…
spring-ai 2.0.1 (AnthropicChatModel)                 sha256 2aca09f5b4b5f420…
cmp → 바이트 단위 동일, 24,237 bytes
system[0]: 22,749 chars, cache_control {type: ephemeral, ttl: 1h}
```

같은 이유는 단순하다. Spring AI 2.0 이 자체 HTTP 구현을 버리고 **벤더 SDK를 그대로
쓴다.** `spring-ai-anthropic:2.0.1` 이 `anthropic-java-core:2.52.0` 을 끌어오고,
`AnthropicChatModel.builder().anthropicClient(...)` 에 SDK 클라이언트를 직접 꽂을 수
있다. 조립 하부가 같으니 결과가 같다.

멀티턴도 이 확인에 덮인다. `CourseQuestionService.buildUserText` 가 history 를 한
문자열로 접고 포트가 `explain(systemText, userText)` 뿐이라, 이 서버가 보내는 모든
요청이 system 1 + user 1 이다.

**아직 못 잰 것:** 실제 `cache_read_input_tokens`. `ANTHROPIC_API_KEY` 가 없다.
요청이 바이트로 같으니 캐시도 같아야 하지만 이것은 추론이다. 키가 생기면 호출 2회로
확정된다.

## 설계

### 포트는 남긴다

`ExplanationProvider` 와 `ProviderResult` sealed 계층은 그대로다. Spring AI 는 그
아래에서 HTTP 를 말할 뿐이다. 포트를 지우면 "같은 프롬프트와 같은 검증으로
비교한다"는 보장이 프레임워크로 넘어가고, 하네스가 낸 숫자의 의미가 한 겹 멀어진다.

### 어댑터

```kotlin
class SpringAiExplanationProvider(
    override val name: String,
    private val chatClient: ChatClient,
) : ExplanationProvider {
    override fun explain(systemText: String, userText: String): ProviderResult
}
```

하는 일은 셋이다.

- `Prompt(SystemMessage(systemText), UserMessage(userText))` 로 호출
- `ChatGenerationMetadata.finishReason == "refusal"` 이면 content 를 읽기 전에
  `Refused` 로 가른다
- `ChatResponseMetadata.usage` 에서 네 토큰 수를 꺼내 `Answered` 에 싣는다

예외는 기존 규칙대로 `Failed("${클래스명}: ${메시지}")` 로 감싼다. 정체를 지우지
않는다.

### 프로바이더별 차이는 ChatClient 조립으로 내린다

`LlmSelection` 이 유일한 조립 지점이 된다.

| 프로바이더 | 조립 |
| --- | --- |
| anthropic | `AnthropicCacheStrategy.SYSTEM_ONLY`, `messageTypeTtl(SYSTEM, ONE_HOUR)`, `OutputConfig(effort=LOW, format=SDK 유도 스키마)` |
| openai | `responseFormat = json_schema` |
| openrouter | `responseFormat = json_schema`, `baseUrl` 교체 |

`OutputConfig` 는 Spring AI 가 `com.anthropic.models.messages.OutputConfig` 를
**그대로** 받는다(`AnthropicChatOptions.outputConfig(OutputConfig)`). 그래서 지금
코드에 있는 우회 — `outputConfig(Class)` 와 `outputConfig(OutputConfig)` 를 순서대로
두 번 불러 `effort` 를 살리는 해킹 — 가 통째로 사라진다.

### EvalMain 은 자기 분기를 버린다

`LlmSelection.provider(name, model, env)` 를 부른다. 모델 기본값 결정
(`OPENAI_MODEL` 등)은 `EvalMain` 에 남고, 프로바이더 조립만 넘긴다. 이름 셋을 두
군데서 맞추던 중복이 사라진다.

### 동작 변경: Anthropic 이 설정 모델을 따른다

지금 `LlmSelection` 의 anthropic 분기는 `model` 인자를 쓰지 않는다.
`AnthropicExplanationProvider.MODEL` 에 `claude-opus-5` 가 박혀 있다. 그런데
`docs/deploy.md` 는 `HERMES_LLM_MODEL` 을 "프로바이더에 맞는 모델 이름"이라고
약속한다 — 문서가 약속한 것을 코드가 조용히 무시하는 중이다.

통합하면 셋 다 설정을 따른다. **이것은 동작 변경이다.** `hermes.llm.model` 의
기본값은 `claude-opus-5` 그대로 두어, 설정을 건드리지 않은 배포는 지금과 같은
모델로 돈다.

## 테스트

`AnthropicRequestShapeTest` 는 `buildParams(...)` 라는 순수 함수의 반환값을
검사한다. Spring AI 에는 그런 함수가 없다 — 요청은 `AnthropicChatModel` 안에서
조립된다. 이 테스트를 그냥 버리면 캐시 분기점이 사라져도 아무도 모른다.

둘로 나눈다.

### 1. 옵션 테스트 (빠름)

`LlmSelection` 은 `ExplanationProvider` 를 돌려주므로 옵션이 밖에서 안 보인다.
지금 `buildParams` 가 순수 함수인 것과 같은 이유로, 옵션 조립도 순수 함수로
꺼내 둔다.

```kotlin
internal object ChatClients {
    fun anthropicOptions(model: String): AnthropicChatOptions
    fun openAiCompatibleOptions(model: String, baseUrl: String): OpenAiChatOptions
}
```

`LlmSelection` 은 이 함수들의 결과로 `ChatClient` 를 만들 뿐이다. 테스트는 함수를
직접 불러 `cacheOptions.strategy`, `messageTypeTtl[SYSTEM]`, `outputConfig.effort`,
유도된 스키마의 `required` 필드를 검사한다. **우리가 프레임워크에 넣는 것**을
고정한다.

### 2. 루프백 캡처 테스트 (결정적)

로컬 포트에 캡처 핸들러를 띄우고 `baseUrl` 을 거기로 돌려, 나가는 JSON 본문을
그대로 검증한다.

- `system[0].text == systemText` (한 바이트도 다르지 않게)
- `system[0].cache_control.ttl == "1h"`
- `output_config.effort == "low"`
- `messages` 는 user 하나

이것은 기존 테스트보다 강하다. SDK 파라미터 객체가 아니라 실제 바이트를 본다.
그리고 키도 외부 네트워크도 쓰지 않는다(루프백뿐) — CI 규칙을 그대로 지킨다.
스파이크가 일회성 실험으로 끝나지 않고 회귀 테스트로 남는다.

### 3. 의존성 핀 테스트

해결된 `jsonschema-generator` 버전을 읽어 4.x 인지 확인한다. 이유는 아래.

### 기존 테스트의 거취

| 파일 | 처리 |
| --- | --- |
| `AnthropicRequestShapeTest` | 5 단계에서 캡처 테스트로 대체. 검사하는 명제(번들이 1시간 캐시 분기점과 함께 system 에 있다, 사실은 분기점 뒤 user 턴에 있다, 모델과 토큰 한도)는 **한 건도 빠짐없이** 캡처 테스트로 옮긴다 |
| `OpenAiCompatibleRequestShapeTest` | 4 단계 결과에 달렸다. OpenRouter 를 접으면 함께 삭제하고, `tool_choice` 재현을 택하면 캡처 테스트로 옮긴다 |
| `RawParams.kt` (테스트 헬퍼) | `StructuredMessageCreateParams` 전용이라 5 단계에서 삭제 |
| `LlmSelectionTest` | 확장. 세 이름이 각각 맞는 옵션을 만드는지까지 본다 |
| `FakeExplanationProviderTest` | 그대로. 포트가 남으므로 영향 없다 |

명제를 옮기기 전에는 구 테스트를 지우지 않는다. 5 단계가 마지막인 이유가 이것이다.

## 의존성 핀

```kotlin
resolutionStrategy.force(
    "com.github.victools:jsonschema-generator:4.38.0",
    "com.github.victools:jsonschema-module-jackson:4.38.0",
    "com.github.victools:jsonschema-module-swagger-2:4.38.0",
)
```

Spring AI 2.0.1 은 victools 5.0.0 을, Anthropic SDK 는 4.x 시그니처를 요구한다
(`SchemaGenerator.generateSchema(Type, Type[])`). 5.0.0 이 충돌에서 이기면
`outputConfig(Class)` 의 스키마 유도가 **런타임에** `NoSuchMethodError` 로 터진다.
컴파일은 멀쩡하다. 스파이크에서 실제로 이것으로 한 번 죽었다.

이 저장소가 스키마를 손으로 쓰지 않고 SDK 에 유도를 맡긴 이유가 드리프트 방지였으니,
핀이 없으면 그 방어선이 조용히 무너진다.

## 알려진 손실

refusal 의 `stopDetails.category` 가 넘어오지 않는다. Spring AI 는
`StopReason.toString()` 을 `ChatGenerationMetadata.finishReason` 문자열로만 노출한다.
사유가 `"refusal (unknown)"` 이 된다.

이미 nullable 로 처리돼 있어 동작은 바뀌지 않고 로그 해상도만 떨어진다. 거절을
`Failed` 와 가르는 판단 자체는 `finishReason` 으로 충분하다.

## 단계와 검증

| 단계 | 검증 |
| --- | --- |
| 1. 어댑터 + Anthropic 경로 | 캡처 테스트가 현행 바이트와 일치. `./gradlew build` |
| 2. `LlmSelection` 단일화, `EvalMain` 중복 제거 | `LlmSelectionTest` 확장 |
| 3. OpenAI 전환 | `./gradlew eval openai 5` — 위반율 8종이 0% 유지 |
| 4. OpenRouter 전환 | `./gradlew eval openrouter 5` |
| 5. 구 프로바이더 2개 삭제 | `build` 와 `eval` 재확인 |

3 단계와 4 단계는 돈이 든다. 조립이 바뀌었으니 다시 재는 것이 이 저장소의
원칙이고, 재측정 결과 자체가 마이그레이션이 안전했다는 증거가 된다.

### OpenRouter 가 깨지면

현행 OpenAI 호환 프로바이더는 `response_format: json_schema` 를 **일부러 피하고**
`tool_choice` 로 스키마를 강제한다 — OpenRouter 무료 티어 모델이 json_schema 를 다
지원하지는 않기 때문이다. Spring AI 의 기본은 `response_format` 이다.

4 단계에서 OpenRouter 가 스키마를 못 받으면 선택지는 둘이다.

- 그 프로바이더를 접는다. 무료 티어 비교용이라 운영 위험은 없다.
- `OpenAiChatOptions.toolChoice` 와 `ToolExecutionEligibilityChecker` 로 강제를
  재현한다. 가능은 하지만 프레임워크의 정상 경로가 아니고, 이 마이그레이션의 동기
  자체와 어긋난다.

측정 결과를 보고 정한다. 미리 정하지 않는다.

## 하지 않는 것

- 프롬프트 조립과 인용 검증은 손대지 않는다
- Advisor 로 인용 검증을 옮기지 않는다. 검증은 런타임 방어선이고, 그 위치가
  프레임워크 확장점으로 옮겨 가면 검증이 붙어 있는지가 설정 문제가 된다
- tool calling 을 도입하지 않는다. 이 프로젝트에 모델이 부를 도구는 없다
- 프론트엔드는 건드리지 않는다
