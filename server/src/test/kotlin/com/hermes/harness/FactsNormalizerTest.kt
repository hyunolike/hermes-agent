package com.hermes.harness

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * EvalMain 은 harness 소스셋에 있어 단위 테스트가 닿지 않는다 — 그래서
 * FactsNormalizer 를 server 의 main 소스셋으로 옮겼다. 이 테스트가 실제
 * 픽스처로 정규화를 돌려, 판정기(ForbiddenBehaviours)가 기대하는 모양대로
 * `/items`, `/alternatives` 가 채워지는지 확인한다.
 */
class FactsNormalizerTest {

    @Test
    fun `실제 픽스처를 정규화하면 items 와 alternatives 가 이름과 visitOrder 를 담는다`() {
        val fixture = ObjectMapper().readTree(File("harness/fixtures/course-explanation-request.json"))

        val facts = FactsNormalizer.normalize(fixture)

        val items = facts.at("/items")
        assertThat(items.map { it.at("/name").asText() })
            .containsExactly("경복궁", "북촌 한옥마을", "서촌 골목길")
        assertThat(items.map { it.at("/visitOrder").asInt() }).containsExactly(1, 2, 3)

        val alternatives = facts.at("/alternatives")
        assertThat(alternatives.map { it.at("/name").asText() })
            .containsExactly("북촌 한옥마을", "서촌 골목길")

        assertThat(facts.at("/targetDate").asText()).isEqualTo("2026-08-15")
        assertThat(facts.at("/congestion/grade").asText()).isEqualTo("VERY_CROWDED")
        assertThat(facts.at("/congestion/betterDates")).hasSize(2)

        // GET /api/v1/attractions/1001 은 뺀다 — area 는 아무도 읽지 않는다.
        assertThat(facts.has("area")).isFalse()
    }
}
