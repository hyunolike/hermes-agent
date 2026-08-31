# hermes-agent

보존된 근거 번들(evidence bundle)을 사용해 여행 코스를 설명하는 LLM 에이전트.

## 구조

단일 Gradle 모듈이다.

- `server/src/main/kotlin` — 애플리케이션 소스 루트
- `server/src/main/resources` — 리소스 (프롬프트 포함)
- `server/src/test/kotlin` — 단위 테스트
- `harness/src/main/kotlin` — 평가 소스셋. 단위 테스트가 아니다 — API 키가 필요하고 비용이 발생하며 비결정적이라 `./gradlew test`에는 포함되지 않는다.

## 빌드

```bash
./gradlew build
```

## 평가

```bash
./gradlew eval
```

금지 행동 6종을 센다. 실제 API 호출 비용이 발생한다.
