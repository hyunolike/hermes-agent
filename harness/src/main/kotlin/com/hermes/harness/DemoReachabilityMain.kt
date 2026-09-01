package com.hermes.harness

import com.hermes.facts.FactsSource
import com.hermes.facts.HanjeokUnavailableException
import com.hermes.facts.RestHanjeokClient
import com.hermes.shared.config.DemoCourses
import org.springframework.web.client.RestClient
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * 고정 데모 코스가 한적에서 아직 조회되는지 본다.
 *
 * 데모는 한적 백엔드와 그 코스 레코드가 살아 있어야 동작한다. 코스가 삭제되면
 * 쇼케이스가 조용히 깨지므로, 조용하지 않게 만드는 것이 이 검사의 전부다.
 *
 *   HANJEOK_BASE_URL=... HERMES_DEMO_COURSES="uuid1|라벨, uuid2|라벨" \
 *     ./gradlew demoReachability
 */
fun main() {
    val baseUrl = System.getenv("HANJEOK_BASE_URL") ?: error("HANJEOK_BASE_URL is not set")
    val demos = DemoCourses.parse(System.getenv("HERMES_DEMO_COURSES") ?: "")

    if (demos.courses.isEmpty()) {
        println("HERMES_DEMO_COURSES is empty — nothing to check.")
        exitProcess(1)
    }

    val executor = Executors.newFixedThreadPool(4)
    val source = FactsSource(RestHanjeokClient(RestClient.builder().baseUrl(baseUrl).build()), 15, executor)

    var failed = 0
    demos.courses.forEach { demo ->
        try {
            val facts = source.fetch(demo.uuid)
            println("OK    ${demo.uuid}  (${demo.label})  facts ${facts.json.length} chars")
        } catch (e: HanjeokUnavailableException) {
            failed++
            println("BROKEN ${demo.uuid}  (${demo.label})  ${e.message}")
        }
    }

    executor.shutdown()
    println()
    println("demo courses: ${demos.courses.size}, broken: $failed")
    if (failed > 0) exitProcess(1)
}
