package com.hermes.facts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FactsSourceTest {

    private val mapper = ObjectMapper()

    private val courseJson = """
        {"targetDate":"2026-08-15","title":"제목","congestionReductionRate":34,"summary":"요약",
         "recommendedDate":null,
         "items":[{"attractionId":1001,"name":"경복궁","visitOrder":1,"timeLabel":"오전 10:00",
                   "grade":"VERY_CROWDED","reason":"첫 방문지","travelMinutesFromPrev":null},
                  {"attractionId":1003,"name":"북촌 한옥마을","visitOrder":2,"timeLabel":"오전 11:38",
                   "grade":"NORMAL","reason":"한산","travelMinutesFromPrev":8}]}
    """.trimIndent()

    private val congestionJson =
        """{"diagnosis":{"concentration":87.3,"percentile":92,"grade":"VERY_CROWDED","message":"붐빈다"},
            "betterDates":[]}"""

    private val alternativesJson =
        """[{"attractionId":1003,"name":"북촌 한옥마을","grade":"NORMAL","concentration":62.0,
             "distanceKm":0.6,"relationScore":0.9,"score":0.704,"recommendReason":"여유","travelMinutes":8}]"""

    // calls 는 congestion/alternatives 두 호출이 서로 다른 스레드에서 동시에
    // 채워질 수 있으므로 스레드 안전한 리스트를 쓴다. 평범한 mutableListOf 는
    // 동시 add 아래 항목을 유실할 수 있어 hasSize(3) 단언이 가끔 실패하는
    // 재현하기 어려운 깜빡임(flake)을 만든다.
    private open inner class FakeClient : HanjeokClient {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun course(courseUuid: String): JsonNode {
            calls += "course:$courseUuid"; return mapper.readTree(courseJson)
        }
        override fun congestion(attractionId: Long, date: String): JsonNode {
            calls += "congestion:$attractionId:$date"; return mapper.readTree(congestionJson)
        }
        override fun alternatives(attractionId: Long, date: String, radiusKm: Int): JsonNode {
            calls += "alternatives:$attractionId:$date:$radiusKm"; return mapper.readTree(alternativesJson)
        }
    }

    private val executor = Executors.newFixedThreadPool(2)

    @Test
    fun `호출은 3회이고 대상과 날짜를 코스 응답에서 파생한다`() {
        // 목적지는 언제나 visitOrder 1 이다 — CourseRoutePolicy.bestOrder 가
        // listOf(originId) + best 를 반환하므로 첫 항목이 목적지다.
        val client = FakeClient()

        FactsSource(client, radiusKm = 15, executor = executor).fetch("abc")

        assertThat(client.calls).hasSize(3)
        assertThat(client.calls).contains("course:abc", "congestion:1001:2026-08-15", "alternatives:1001:2026-08-15:15")
        // attractions/{id} 는 부르지 않는다 — 스펙 §3 이 잘라냈다.
        assertThat(client.calls).noneMatch { it.startsWith("attraction:") }
    }

    @Test
    fun `조립된 facts 가 검사기와 프롬프트가 읽는 모양이다`() {
        val facts = FactsSource(FakeClient(), 15, executor).fetch("abc")
        val parsed = mapper.readTree(facts.json)

        assertThat(facts.courseUuid).isEqualTo("abc")
        assertThat(parsed.at("/items/0/name").asText()).isEqualTo("경복궁")
        assertThat(parsed.at("/alternatives/0/score").asDouble()).isEqualTo(0.704)
        assertThat(parsed.at("/congestion/percentile").asInt()).isEqualTo(92)
    }

    @Test
    fun `병렬 호출 중 대안이 실패하면 전체가 실패한다`() {
        // 반쪽짜리 facts 로 설명을 만들면 없는 근거를 지어내라고 시키는 것과 같다.
        val client = object : FakeClient() {
            override fun alternatives(attractionId: Long, date: String, radiusKm: Int): JsonNode =
                throw HanjeokUnavailableException("boom")
        }

        assertThatThrownBy { FactsSource(client, 15, executor).fetch("abc") }
            .isInstanceOf(HanjeokUnavailableException::class.java)
    }

    @Test
    fun `병렬 호출 중 혼잡도가 실패해도 전체가 실패한다`() {
        // 대안만 실패 경로를 갖고 혼잡도는 갖지 않는 구현을 잡아낸다 — 두
        // Future 모두 같은 방식으로 실패를 전파해야 한다.
        val client = object : FakeClient() {
            override fun congestion(attractionId: Long, date: String): JsonNode =
                throw HanjeokUnavailableException("boom")
        }

        assertThatThrownBy { FactsSource(client, 15, executor).fetch("abc") }
            .isInstanceOf(HanjeokUnavailableException::class.java)
    }

    @Test
    fun `코스에 항목이 없으면 실패한다`() {
        val client = object : FakeClient() {
            override fun course(courseUuid: String): JsonNode =
                mapper.readTree("""{"targetDate":"2026-08-15","items":[]}""")
        }

        assertThatThrownBy { FactsSource(client, 15, executor).fetch("abc") }
            .isInstanceOf(HanjeokUnavailableException::class.java)
    }

    @Test
    fun `한적 응답에 기대한 필드가 없으면 FactsProjection의 실패가 HanjeokUnavailableException으로 바뀐다`() {
        // FactsProjection.assemble 은 필드가 빠지면 error(...) 를 던져
        // IllegalStateException 이 나온다. 그 예외가 그대로 새어나가면 웹
        // 계층에 처리되지 않은 500 이 되므로, FactsSource 가 잡아서 같은
        // 타입의 실패로 바꿔야 한다.
        val client = object : FakeClient() {
            override fun congestion(attractionId: Long, date: String): JsonNode =
                // diagnosis 자체가 없는 기형 응답.
                mapper.readTree("""{"betterDates":[]}""")
        }

        assertThatThrownBy { FactsSource(client, 15, executor).fetch("abc") }
            .isInstanceOf(HanjeokUnavailableException::class.java)
            .hasCauseInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `코스에 items 필드 자체가 없어도 실패하고 예외가 그대로 새지 않는다`() {
        // items 가 비어있는 배열인 경우와 달리, 필드 자체가 없으면
        // course.get("items") 는 (Jackson 이 널 안전성 애노테이션이 없는
        // 플랫폼 타입을 돌려주므로) 실제 null 이 된다. 방어 체크가 없으면
        // items.minByOrNull { ... } 에서 NullPointerException 이 그대로 새어나가
        // "예외는 늘 HanjeokUnavailableException 이어야 한다"는 계약을 깬다.
        val client = object : FakeClient() {
            override fun course(courseUuid: String): JsonNode =
                mapper.readTree("""{"targetDate":"2026-08-15"}""")
        }
        assertThatThrownBy { FactsSource(client, 15, executor).fetch("abc") }
            .isInstanceOf(HanjeokUnavailableException::class.java)
    }

    @Test
    fun `혼잡도와 대안 호출은 실제로 동시에 실행된다`() {
        // 순서대로(첫 Future 를 join 한 다음에야 두번째를 시작) 호출하는
        // 구현이라면 이 배리어는 절대 풀리지 않고 2초 뒤 타임아웃으로 실패한다.
        // 병렬 실행이라면 두 스레드가 배리어에 동시에 도달해 바로 풀린다.
        val barrier = CyclicBarrier(2)
        val client = object : FakeClient() {
            override fun congestion(attractionId: Long, date: String): JsonNode {
                barrier.await(2, TimeUnit.SECONDS)
                return super.congestion(attractionId, date)
            }
            override fun alternatives(attractionId: Long, date: String, radiusKm: Int): JsonNode {
                barrier.await(2, TimeUnit.SECONDS)
                return super.alternatives(attractionId, date, radiusKm)
            }
        }

        val facts = FactsSource(client, 15, executor).fetch("abc")

        assertThat(facts.courseUuid).isEqualTo("abc")
    }
}
