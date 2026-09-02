# syntax=docker/dockerfile:1

# ── 빌드 ───────────────────────────────────────────────────────────────────
# 번들(server/src/main/resources/prompts/hanjeok-bundle.txt)은 이 컨텍스트에 이미
# 있어야 한다. CI 가 위키를 체크아웃해 build-bundle.sh 로 만들어 그 자리에 둔다.
# 런타임에 위키를 clone 하지 않는 이유: 위키가 잠깐 안 되면 서버가 못 뜨고, 같은
# 이미지가 날마다 다른 근거로 답하게 된다. 근거는 이미지에 고정되어야 한다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# 래퍼와 빌드 스크립트를 먼저 복사해 의존성 해석 레이어를 캐시한다.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY server ./server

# bootJar 는 두 개를 만든다 — 실행 가능한 것과 `-plain` 붙은 라이브러리 jar.
# 와일드카드로 복사하면 둘 다 딸려와 ENTRYPOINT 가 가리키는 이름이 성립하지 않는다.
RUN ./gradlew --no-daemon bootJar -x test && \
    cp "$(ls build/libs/*.jar | grep -v -- '-plain')" /app.jar

# ── 런타임 ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# 루트로 돌지 않는다.
RUN useradd --system --uid 10001 hermes
# 평가 하네스(harness/)는 애초에 이 스테이지로 오지 않는다 — 돈이 드는 경로가
# 운영 이미지 안에 있으면 언젠가 실수로 실행된다.
COPY --from=build /app.jar /app/app.jar
USER hermes

# Cloud Run 이 PORT 를 준다. 기본 8080 은 로컬에서 그대로 쓰기 위한 것이다.
ENV PORT=8080
EXPOSE 8080

# 번들 적재 실패 시 BundleHealthIndicator 가 DOWN 을 내고, Cloud Run 의
# 스타트업 프로브가 그것을 본다 — 근거 없이 뜬 인스턴스로 트래픽이 가면 안 된다.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -Dserver.port=${PORT} -jar /app/app.jar"]
