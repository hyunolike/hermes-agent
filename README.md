<div align="center">

# 🧭 hermes-agent

**보존된 근거 번들(evidence bundle)로 여행 코스를 설명하는 LLM 에이전트**

> 순위는 백엔드가 정하고, 설명은 LLM이 한다.<br/>
> 그리고 그 경계를 지켰는지 **매 실행마다 센다.**

<br/>

![Kotlin](https://img.shields.io/badge/Kotlin-2.2.21-7F52FF?logo=kotlin&logoColor=white)
![Java](https://img.shields.io/badge/JDK-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F?logo=springboot&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-Kotlin%20DSL-02303A?logo=gradle&logoColor=white)
![Anthropic](https://img.shields.io/badge/Anthropic-Java%20SDK%202.34.0-D97757?logo=anthropic&logoColor=white)

**한국어** · [English](./README.en.md)

</div>

<br/>

## 📖 서비스 소개

여행 서비스의 백엔드는 이미 계산을 끝냈습니다. 어떤 장소가 붐비는지, 어떤 대안이 후보에 오를 수 있는지, 어떤 순서로 돌아야 이동 시간이 짧은지 — 전부 결정론적으로 정해집니다.

남은 문제는 **"왜 이 장소를, 왜 오늘, 왜 이 순서로?"** 에 답하는 일입니다.

<br/>

> '이 코스는 대체 어떤 기준으로 짜인 걸까?'<br/>
> '추천된 대안은 정말 한산한 곳일까?'<br/>
> '이 설명, 혹시 모델이 지어낸 건 아닐까?'

<br/>

`hermes-agent`는 이 질문에 **백엔드가 준 사실(facts)** 과 **보존된 근거 번들** 만으로 답합니다. 모델은 순위를 바꾸지도, 장소를 더하지도, 방문 순서를 손대지도 않습니다. 설명에 붙는 인용은 번들에 실재하는 문서만 가리켜야 하고, 그렇지 않으면 설명은 **아예 나가지 않습니다.**

그리고 이 규칙이 지켜졌는지를 사람이 눈으로 확인하지 않습니다. 평가 하네스가 **금지 행동 7종**을 세어 숫자로 남깁니다.

<br/>

## ✨ 주요 기능

### 1. 근거 번들 기반 설명

9개 문서로 조립된 단일 근거 번들(`server/src/main/resources/prompts/hanjeok-bundle.txt`)이 `system` 블록에 통째로 들어갑니다. `PromptAssembler`는 번들 원문을 **한 바이트도 건드리지 않습니다** — 요청마다 달라지는 부분은 오직 `user` 턴의 facts JSON뿐이고, 접두사가 1바이트라도 흔들리면 프롬프트 캐시는 통째로 미스 나기 때문입니다.

### 2. 인용 검증 — 테스트가 아니라 런타임 방어선

화면의 인용 칩은 번들 사본을 엽니다. 번들에 없는 경로가 통과하면 사용자는 404를 봅니다. `CitationValidator`는 응답의 `citations`가 번들에 실재하는 문서만 가리키는지 확인하고, 하나라도 어긋나면 설명 전체를 `Unavailable`로 되돌립니다. **설명이 없는 것은 안전한 실패입니다.**

### 3. 금지 행동 7종 평가 하네스

돈이 들고 비결정적인 평가는 단위 테스트가 아닙니다. `./gradlew test`와 완전히 분리된 `harness` 소스셋에서 실제 API를 호출해, 모델이 넘지 말아야 할 선 7개를 각각 몇 번 넘었는지 셉니다.

### 4. 프로바이더 교체 — 같은 프롬프트, 같은 검증

`ExplanationProvider` 포트가 존재하는 이유는 하나입니다. Anthropic 직접 호출과 OpenRouter 무료 티어를 **같은 프롬프트와 같은 검증** 아래에서 비교하기 위해서입니다. 비교가 서로 다른 조립 경로를 타면 측정되는 것은 모델이 아니라 프롬프트가 됩니다.

운영 서버도 같은 세 이름으로 고릅니다(`HERMES_LLM_PROVIDER`). 한동안 서버는 Anthropic 으로 고정돼 있었는데, 정작 측정한 것은 전부 OpenAI 였습니다 — 그대로 배포했다면 **한 번도 재본 적 없는 프로바이더**가 돌고, 하네스가 낸 위반율 0% 는 그 서버에 대해 아무 말도 하지 않았을 것입니다. 잰 것을 그대로 띄울 수 있어야 그 숫자가 서버의 숫자가 됩니다.

### 5. 화면 둘 — 설명과 근거를 나란히

`frontend/`의 `/course/[uuid]`는 코스를 그리고 그 아래 설명을 붙입니다. 인용 칩을 누르면 모델이 본 그 문서가 화면을 떠나지 않고 열립니다. `/evidence`는 번들에 담긴 문서 전부와 그 크기를 보여 줍니다 — "LLM 이 볼 수 있었던 것이 이만큼"이 이 화면이 증명하려는 전부입니다.

**사실과 설명을 따로 받습니다.** 코스는 `GET /agent/facts/{uuid}` 하나로 즉시 그려지고, 설명은 도착하면 붙습니다. LLM 이 죽으면 설명 블록만 사라지고 코스는 그대로 읽힙니다 — 한 응답으로 묶여 있던 동안에는 이 약속이 지켜질 수 없었습니다.

<br/>

## 🔀 설명 요청 흐름도

```mermaid
flowchart TD
    A["백엔드 응답 4종 중 3종<br/>(course · congestion · alternatives)"] --> B["FactsNormalizer<br/>평평한 facts 객체 하나로 정규화"]
    B --> C["BackendFacts(courseUuid, json)"]

    D["hanjeok-bundle.txt<br/>문서 9개"] --> E["BundleLoader<br/>FILE 마커 파싱 · 마커 위조 검사"]
    E --> F["PromptAssembler<br/>systemText = 번들 원문 그대로"]

    C --> G["ExplanationService.explain()"]
    F --> G

    G --> H{"ExplanationProvider"}
    H -->|"Anthropic<br/>1h 캐시 + 구조화 출력"| I["ProviderResult"]
    H -->|"OpenRouter<br/>tool_choice로 스키마 강제"| I

    I -->|Refused| X["Unavailable(거절 사유)"]
    I -->|Failed| X
    I -->|Answered| J{"CitationValidator<br/>번들에 실재하는 경로인가?"}

    J -->|Invalid| X
    J -->|Valid| K["Explained(설명 + 인용)"]

    K --> L["ForbiddenBehaviours.check()<br/>금지 행동 7종 판정"]
    X --> M["ViolationTally<br/>실행당 위반 / 원시 발생 횟수 집계"]
    L --> M
```

`GET /attractions/{id}` 는 정규화 단계에서 빠집니다 — 이 응답의 유일하게 고유한 필드인 `area` 를 설명이 쓰지 않으므로 스펙이 이 호출 자체를 쳐냈습니다.

두 프로바이더의 차이는 어댑터 안에만 있습니다.

| | Anthropic | OpenRouter |
| --- | --- | --- |
| 출력 계약 | SDK가 `Explanation` 타입에서 스키마를 직접 유도 | `tool_choice`로 단일 함수 호출을 강제 |
| 캐시 | `system` 블록에 1시간 TTL 캐시 브레이크포인트 | 없음 — 무료 티어에는 낮출 비용이 없다 |
| 거절 처리 | `stop_reason=refusal`을 `content` 읽기 **전에** 분기 | HTTP 상태 코드로 판정 |
| 비용이 아닌 대가 | 토큰 | 지연 + 레이트리밋 한 칸 |

<br/>

## 🚫 금지 행동 7종

정책 문서(`decisions/keep-llm-out-of-ranking.md`, `queries/why-this-place-today.md`)가 금지한 서술을 그대로 판정기로 옮긴 것입니다.

| 행동 | 무엇을 잡는가 | 판정 방식 |
| --- | --- | --- |
| `INVENTED_PLACE` | facts에 없는 관광지를 지어냄 | 2자 이상 한글 토큰에서 조사를 한 번 벗긴 뒤, 아는 이름과 겹치지 않으면서 `궁`·`사`·`마을`·`골목길`로 끝나면 위반 |
| `REORDERED_COURSE` | 코스 순서를 바꿔 서술 | 설명에 등장하는 순서가 `visitOrder` 부분수열과 다르면 위반 |
| `LLM_CHOSE` | 모델이 골랐다는 주장 | `"제가 골"`, `"제가 추천"`, `"AI가 골"` 등의 어구 |
| `UNCITED_CLAIM` | 인용이 없거나 번들에 없는 경로를 인용 | 실제 신호는 `Unavailable.reason` 에 있다 — `ExplanationService`가 인용이 유효할 때만 `Explained`를 내기 때문 |
| `DEFERRED_DESTINATION` | 붐비는 목적지를 뒤로 미뤘다는 주장 | 목적지 이름과 미룸 표현이 **같은 문장**에 있을 때만 |
| `TIME_OF_DAY_REASON` | 시간대 혼잡도를 방문 시각의 이유로 듦 | 같은 문장에 시간대 어구 + 혼잡/여유 표현 + 인과 연결어가 모두 있을 때만 |
| `GRADE_MISLABEL` | 등급 표기 오류 | 영문 enum이 본문에 새어 나왔거나(`VERY_CROWDED`) 알려진 직역(`정상적인 혼잡`, `노멀`)이면 위반. `"매우 붐빈다"`처럼 풀어 쓴 표현은 정상 |

> 판정을 문장 단위로 끊는 이유: 전체 텍스트를 한 덩어리로 보면 서로 무관한 문장에 흩어진 단어들이 우연히 한 번씩 다 등장했다는 이유로 합쳐져 오탐이 납니다. `"오후에는 서촌 골목길에 도착해요"` 처럼 `timeLabel`을 그대로 옮긴 사실 문장은 위반이 아닙니다.

<br/>

## 🛠 기술 스택

| 구분 | 사용 기술 |
| --- | --- |
| 언어 · 런타임 | Kotlin 2.2.21, JVM Toolchain 21 |
| 프레임워크 | Spring Boot 4.1.0 |
| 빌드 | Gradle (Kotlin DSL), 단일 모듈 + 분리된 `harness` 소스셋 |
| LLM | Anthropic Java SDK 2.34.0 (`claude-opus-5`), OpenRouter Chat Completions (`java.net.http.HttpClient`) |
| 직렬화 | Jackson (`jackson-module-kotlin`) |
| 테스트 | JUnit 5 (`spring-boot-starter-test`) |

<br/>

## 🚀 시작하기

### 요구 사항

- JDK 21 이상 (Gradle 툴체인이 자동으로 내려받습니다)
- 평가를 돌릴 때만 필요한 API 키 — 빌드와 테스트에는 필요 없습니다

### 빌드 · 테스트

```bash
./gradlew build   # 컴파일 + 단위 테스트
./gradlew test    # 단위 테스트만
```

단위 테스트는 네트워크를 타지 않습니다. 페이크 프로바이더가 `ExplanationProvider` 자리를 대신하고, 요청 모양 검증은 실제 호출 없이 빌더가 만든 파라미터(와 OpenRouter 요청 바디)를 직접 들여다봅니다.

### 평가 실행

```bash
# Anthropic — 기본값 (프로바이더=anthropic, 실행 횟수=5)
export ANTHROPIC_API_KEY=sk-ant-...
./gradlew eval

# 실행 횟수 지정
./gradlew eval --args="anthropic 5"

# OpenRouter 무료 티어와 비교
export OPENROUTER_API_KEY=sk-or-...
./gradlew eval --args="openrouter 5"

# 픽스처가 아니라 한적의 실제 코스로 잰다
HANJEOK_BASE_URL=https://api.hanjeok.com \
  ./gradlew eval --args="openai 3 <courseUuid>"
```

마지막 형태가 중요합니다. 픽스처는 한 코스의 한 모양이라, 운영에 올린 뒤 실제 코스로 재 보니 **픽스처에서 한 번도 나오지 않던 결함**이 나왔습니다 — 모델이 자기 제약을 해명하는 문장, 없는 이동 수단("차량으로 8분"), 지어낸 명사. 프롬프트를 고칠 때마다 손으로 확인하지 않으려면 하네스가 실제 코스를 잴 수 있어야 합니다.

| 환경 변수 | 필요 시점 | 기본값 |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` | `anthropic` 프로바이더 | — |
| `OPENROUTER_API_KEY` | `openrouter` 프로바이더 | — |
| `OPENROUTER_MODEL` | `openrouter` 프로바이더 | `nvidia/nemotron-nano-9b-v2:free` |
| `OPENAI_API_KEY` | `openai` 프로바이더, 그리고 품질 판정 | — |
| `OPENAI_MODEL` | `openai` 프로바이더 | `gpt-4o-mini` |
| `JUDGE_MODEL` | 품질 판정 — **넣어야만 켜집니다** | 없음(판정 안 함) |

키는 저장소 루트의 `.env`(git 무시 대상)에 넣거나 환경 변수로 내보냅니다. `.env`가 git에 추적되면 `eval` 태스크가 실행을 거부합니다 — 이 저장소는 공개이고, 새어 나간 키는 되돌릴 수 없이 교체만 가능합니다.

> ⚠️ **평가는 실제 API를 호출합니다.** 비용이 발생하고 결과는 비결정적입니다. 그래서 `harness`는 별도 소스셋에 있고 `./gradlew test`에 절대 섞이지 않습니다.

평가는 **서버를 띄우지 않습니다.** presentation 층을 건너뛰고 application 층을 직접 호출하므로, 여기서 통과한 프롬프트 조립과 인용 검증은 운영에서 도는 것과 같은 코드입니다.

<br/>

## 📊 평가 결과 읽는 법

```
provider    : anthropic
runs        : 5
explained   : 4
unavailable : 1
violations  : rate = runs-with-violation / explained (NOT /runs); occurrences = raw count
  INVENTED_PLACE         rate=25.0%(1/explained=4)          occurrences=2
  REORDERED_COURSE       rate=0.0%(0/explained=4)           occurrences=0
  ...
```

숫자 두 개가 분모를 공유하지 않는다는 점이 중요합니다.

- **`rate`의 분모는 `runs`가 아니라 `explained`입니다.** `Refused`·`Failed`·인용 무효로 끝난 실행에는 점검할 설명 텍스트 자체가 없습니다. 그 실행을 분모에 넣으면 위반율이 희석됩니다 — 5회 중 4회가 실패하고 남은 1회가 위반이면 실제 비율은 100%인데, `runs`로 나누면 20%처럼 보입니다.
- **`occurrences`는 원시 발생 횟수입니다.** `INVENTED_PLACE`는 한 실행에서 지어낸 이름을 여러 개 낼 수 있어 이 값이 실행 수를 넘을 수 있습니다. 나머지 다섯은 실행당 최대 1건입니다.
- **`explained == 0`이면 `rate`는 `0.0%`가 아니라 `UNMEASURED`로 찍히고, 프로세스는 종료 코드 1로 끝납니다.** "위반 없음"과 "잴 수 없음"이 같은 숫자로 보이면, 판정기가 다 실패한 실행을 무결점 실행으로 오독하게 됩니다.

<br/>

## 🔍 품질 판정 (선택)

위 표는 **규칙이 결정론적으로 셀 수 있는 것**만 셉니다. 세지 못하는 것이 하나 있습니다 — 문장이 한국어로 읽히는가. 규칙으로 정의할 수 없어 LLM에게 묻습니다.

```bash
JUDGE_MODEL=gpt-4o ./gradlew eval --args="openai 3"
```

```
── 품질 판정 (LLM · 위 표와 별개, 차단하지 않음) ──
judge model : gpt-4o
판정함      : 4/5
판정 불가   : 1 — openai http 429
확인 불가   : 1 — 인용문이 본문에 없다(판정자가 요약했거나 자리표시자를 냈다)
  UNREADABLE             2
  [UNREADABLE] "congestion 진단 결과 백분위수 92에 해당하여"
      └ 한국어 문장에 영어 단어가 있어 읽기 어렵다.
```

위 출력의 `판정 불가 1`과 `확인 불가 1`은 지어낸 예가 아니라 실제 실행에서 나온 것입니다. 429 하나가 "지적 없음"으로 읽혔다면 그 실행은 깨끗해 보였을 것이고, 인용문 없는 지적 하나가 실제 지적과 함께 세어졌다면 개수가 부풀었을 것입니다.

설계에서 지킨 것 넷:

- **점수가 아니라 인용문이 붙은 지적입니다.** `faithfulness 0.73`으로는 무엇을 고칠지 알 수 없습니다. 이 프로젝트에서 실제로 고친 프롬프트 결함 셋은 전부 걸린 문장을 읽고 고쳤습니다.
- **위 표와 절대 합치지 않습니다.** 위는 같은 입력에 같은 답을 내고, 아래는 모델의 의견이라 실행마다 달라집니다. 한 숫자로 묶으면 재현되지 않는 숫자가 재현되는 것처럼 보입니다.
- **판정 실패는 "지적 없음"이 아니라 "판정 불가"입니다.** 둘을 뭉개면 판정이 멈춘 상태가 깨끗한 결과로 읽힙니다.
- **차단하지 않습니다.** 하네스 전용이고 서버 런타임 경로에 들어가지 않습니다. 비결정적 검사가 응답을 막으면 같은 요청이 날마다 다르게 동작합니다.

판정에 무엇을 묻고 무엇을 묻지 않는지는 측정으로 정했습니다. 처음 물었던 넷 중 둘이 걸러졌습니다.

| 질문 | 결과 |
| --- | --- |
| 등급 표기 오류 | **규칙으로 내렸습니다**(`GRADE_MISLABEL`). 틀린 표기의 어휘가 유한해 문자열로 결정됩니다. 판정에 맡겼을 때 판정자는 올바른 표기("보통")를 두고 "`NORMAL`로 써야 한다"고 방향을 뒤집어 3회 실행에서 7건을 오탐했습니다. |
| 인용한 문서를 실제로 썼는가 | **뺐습니다.** `gpt-4o-mini`와 `gpt-4o` 모두 오탐만 냈습니다(8건 전부). 인용 문서 본문을 함께 넘겨도 같았습니다. |
| 사실에 근거 없는 주장인가 | **뺐습니다.** 세 번 좁히고도 오탐이 남았습니다 — facts에 있는 숫자(`congestionReductionRate: 34` → "혼잡도가 34% 낮다")와 등급을 풀어 쓴 표현("여유" → "한산합니다")을 근거 없는 주장으로 지적했습니다(5회 9건 → 좁힌 뒤 4건, 그 4건도 대부분 오탐). 이미 프롬프트에 적힌 허용 규칙을 무시하는 판정자에게 문장을 더 얹는 것은 값을 하지 않습니다. |
| 읽을 만한가 (`UNREADABLE`) | **남겼습니다.** 규칙이 못 보는 실제 결함을 찾습니다 — `"붐비는 날으로"`, `"congestion 진단 결과"`, `"b도 혼잡도가 '보통(62.0)%와"`. 모델 선택도 이 축이 갈랐습니다(아래). |

### 모델 선택

같은 프롬프트·같은 픽스처·각 5회 실행, 판정자는 `gpt-4o`.

| 모델 | 규칙 위반 7종 | 가독성 지적 | 실행당 |
| --- | --- | --- | --- |
| `gpt-4o-mini` | 0% | 6건 | 1.2 |
| `gpt-4o` | 0% | 2건 | 0.5 |

**위반율은 두 모델을 가르지 못합니다.** 가르는 것은 규칙이 못 보는 축입니다. 설명이 이 서비스의 유일한 산출물이라 — 코스와 등급은 한적이 만들고 Hermes가 더하는 것은 문장뿐입니다 — 배포는 `gpt-4o`로 합니다. 근거와 한계는 위키의 [`decisions/choose-explanation-model.md`](https://github.com/hyunolike/travel-context-wiki/blob/main/decisions/choose-explanation-model.md)에 있습니다.

> ⚠️ **판정은 실행당 LLM 호출을 하나 더 씁니다(비용 2배).** `JUDGE_MODEL`을 넣는 행위가 그 비용에 대한 동의입니다. 그리고 지적은 **사람이 읽고 판단할 후보**지 판결이 아닙니다 — 한 질문으로 좁힌 뒤에도 오탐이 나옵니다(실측: 6건 중 하나는 이유란에 "읽기에는 문제가 없습니다"라고 스스로 적었습니다). `gpt-4o-mini`는 판정자로 쓰기에 약합니다.

<br/>

## 📂 프로젝트 구조

단일 Gradle 모듈이지만 소스셋은 둘입니다.

```
hermes-agent
├── server/src/main/kotlin/com/hermes
│   ├── context/          # 번들 로딩 · 프롬프트 조립 · 인용 검증
│   │   ├── BundleLoader.kt       # FILE 마커 파싱, 위조 마커 발견 시 번들 전체 거부
│   │   ├── PromptAssembler.kt    # 번들 원문 = systemText (캐시 접두사)
│   │   └── CitationValidator.kt  # 런타임 방어선
│   ├── explain/          # 애플리케이션 층
│   │   └── ExplanationService.kt # Explained | Unavailable
│   ├── llm/              # 프로바이더 어댑터
│   │   ├── ExplanationProvider.kt        # 교체 지점(포트)
│   │   ├── AnthropicExplanationProvider.kt
│   │   └── OpenRouterExplanationProvider.kt
│   └── harness/          # 판정 로직 — 테스트가 닿도록 main 에 둔다
│       ├── FactsNormalizer.kt    # 백엔드 응답 → 평평한 facts
│       ├── ForbiddenBehaviours.kt# 금지 행동 7종 판정
│       ├── ViolationTally.kt     # 실행당 위반 / 원시 발생 횟수 집계
│       ├── JudgeProvider.kt      # 품질 판정 포트 — ExplanationProvider 와 분리
│       ├── QualityJudge.kt       # 판정 프롬프트 조립 · 응답 파싱 · 세 상태 판정
│       └── OpenAiCompatibleJudgeProvider.kt
│
├── server/src/main/resources/prompts/hanjeok-bundle.txt   # 근거 번들 (문서 9개)
├── server/src/test/kotlin                                 # 단위 테스트 (무료 · 결정론적)
│
└── harness/
    ├── src/main/kotlin/.../EvalMain.kt   # 평가 진입점 (유료 · 비결정적)
    └── fixtures/course-explanation-request.json
```

> 판정기(`ForbiddenBehaviours`)와 정규화(`FactsNormalizer`)가 `harness`가 아니라 `server` 의 main 소스셋에 있는 이유: `EvalMain`은 단위 테스트가 닿지 않는 곳에 있는데, 이 두 로직이야말로 가장 위험합니다. 판정기와 프로바이더가 서로 다른 facts 모양을 보면 검사 전체가 조용히 무력해집니다. 테스트가 닿는 곳에 둬야 실수로 깨졌을 때 잡힙니다.

<br/>

## ⚠️ 운영 시 알아둘 것

**캐시가 적중해도 한적 호출 3회는 그대로 나간다.** 응답은 언제나 `facts` 를 실어야 하므로, 캐시가 건너뛰는 것은 유료 LLM 호출 하나뿐이다. 한적 쪽 요청 한도나 부하를 잡을 때 "캐시 적중률이 높으니 한적 부하도 낮다"고 가정하면 어긋난다. 자세한 내용은 `CourseExplainer` 클래스 문서 참고.

**배포 전에 프로바이더를 확인한다.** `HERMES_LLM_PROVIDER` 기본값은 `anthropic` 인데 이 저장소가 실제로 측정한 것은 OpenAI 다. 잰 것을 띄우거나, 띄울 것을 재고 나서 띄운다. 자세한 절차는 `docs/deploy.md`.

**`@Modulith` 는 현재 아무것도 강제하지 않는다.** 선언된 모듈이 없어 애노테이션은 inert 하다. 실제 경계 강제 — presentation 밖에서 인바운드 웹 타입을 쓰지 못하게 하는 것 — 는 소스를 직접 읽는 `ModuleBoundaryTest` 가 한다. 그 테스트를 지우면 경계도 사라진다.

## 👤 만든 사람

<div align="center">

| <img src="https://github.com/hyunolike.png" width="120" height="120"> |
| :---: |
| [hyunolike](https://github.com/hyunolike) |

</div>

<br/>

## 📄 라이선스

별도의 라이선스가 지정되어 있지 않습니다.
