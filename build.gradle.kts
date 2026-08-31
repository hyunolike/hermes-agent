plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.hermes"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
}

sourceSets {
    main {
        kotlin.srcDir("server/src/main/kotlin")
        resources.srcDir("server/src/main/resources")
    }
    test {
        kotlin.srcDir("server/src/test/kotlin")
        resources.srcDir("server/src/test/resources")
    }
    // 평가는 단위 테스트가 아니다 — 돈이 들고 비결정적이라 `./gradlew test` 에 섞이면 안 된다.
    create("harness") {
        kotlin.srcDir("harness/src/main/kotlin")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val harnessImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.implementation.get())
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("eval") {
    group = "verification"
    description = "금지 행동 6종을 센다. API 키가 필요하고 실제 비용이 발생한다."
    classpath = sourceSets["harness"].runtimeClasspath
    mainClass.set("com.hermes.harness.EvalMainKt")
}
