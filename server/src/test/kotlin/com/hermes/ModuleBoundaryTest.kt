package com.hermes

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 스펙 §2.2 — presentation 밖의 어떤 패키지도 인바운드 HTTP 를 몰라야 한다.
 * 그래야 harness 가 서버를 띄우지 않고 application 층을 직접 구동할 수 있다.
 *
 * 소스를 직접 읽는다. 이 규율은 컴파일러가 강제하지 않으므로 테스트가 유일한
 * 방어선이고, 테스트를 지우면 경계도 사라진다 — 지워도 되는 테스트가 아니다.
 *
 * 아웃바운드 클라이언트는 대상이 아니다. facts 가 한적을 부르고 llm 이
 * 프로바이더를 부르는 것은 인바운드 HTTP 를 아는 것과 범주가 다르다. 이후
 * 태스크의 Spring 설정이 org.springframework.web.client.RestClient 와
 * org.springframework.http.client.SimpleClientHttpRequestFactory 를 쓰는 것은
 * 정당한 아웃바운드 사용이므로 이 두 접두사만 예외로 둔다.
 */
class ModuleBoundaryTest {

    private val sourceRoot = File("server/src/main/kotlin/com/hermes")

    private fun kotlinFiles(): List<File> =
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `소스 루트를 실제로 찾았다`() {
        // 경로가 틀리면 아래 검사들이 빈 목록을 훑고 조용히 통과한다.
        assertThat(kotlinFiles()).hasSizeGreaterThan(5)
    }

    @Test
    fun `presentation 밖에서는 인바운드 웹 타입을 쓰지 않는다`() {
        val exemptPrefixes = listOf(
            "import org.springframework.web.client.",
            "import org.springframework.http.client.",
        )

        val offenders = kotlinFiles()
            .filterNot { it.path.contains("/explain/presentation/") }
            .filter { file ->
                file.readLines().any { line ->
                    (line.startsWith("import org.springframework.web.") ||
                        line.startsWith("import org.springframework.http.")) &&
                        exemptPrefixes.none { line.startsWith(it) }
                }
            }
            .map { it.relativeTo(sourceRoot).path }

        assertThat(offenders)
            .describedAs("presentation 밖에서 인바운드 웹 타입을 import 한 파일")
            .isEmpty()
    }

    @Test
    fun `presentation 은 llm 프로바이더 구현을 직접 부르지 않는다`() {
        // 컨트롤러가 프로바이더를 직접 잡으면 인용 검증을 건너뛸 수 있다.
        val presentation = kotlinFiles().filter { it.path.contains("/explain/presentation/") }
        assertThat(presentation).isNotEmpty()

        val offenders = presentation.filter { file ->
            file.readLines().any { it.startsWith("import com.hermes.llm.Anthropic") || it.startsWith("import com.hermes.llm.OpenRouter") }
        }.map { it.name }

        assertThat(offenders).isEmpty()
    }
}
