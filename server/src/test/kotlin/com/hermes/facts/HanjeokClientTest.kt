package com.hermes.facts

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException

class HanjeokClientTest {

    private val builder = RestClient.builder().baseUrl("http://hanjeok.test")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = RestHanjeokClient(builder.build())

    @Test
    fun `코스 응답의 봉투를 벗겨 data 를 돌려준다`() {
        server.expect(requestTo("http://hanjeok.test/api/v1/courses/abc"))
            .andRespond(
                withSuccess(
                    """{"success":true,"error":null,"data":{"targetDate":"2026-08-15","items":[]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThat(client.course("abc").at("/targetDate").asText()).isEqualTo("2026-08-15")
    }

    @Test
    fun `success 가 false 면 실패로 다룬다`() {
        // HTTP 200 에 success:false 가 오는 경로다. 상태 코드만 보면 통과한다.
        server.expect(requestTo("http://hanjeok.test/api/v1/courses/abc"))
            .andRespond(
                withSuccess(
                    """{"success":false,"error":"NOT_FOUND","data":null}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThatThrownBy { client.course("abc") }
            .isInstanceOf(HanjeokUnavailableException::class.java)
            .hasMessageContaining("NOT_FOUND")
    }

    @Test
    fun `5xx 는 예외로 바뀐다`() {
        server.expect(requestTo("http://hanjeok.test/api/v1/courses/abc")).andRespond(withServerError())

        assertThatThrownBy { client.course("abc") }
            .isInstanceOf(HanjeokUnavailableException::class.java)
    }

    @Test
    fun `혼잡도와 대안 URL 이 스펙대로 조립된다`() {
        server.expect(requestTo("http://hanjeok.test/api/v1/attractions/1001/congestion?date=2026-08-15"))
            .andRespond(withSuccess("""{"success":true,"error":null,"data":{"diagnosis":{}}}""", MediaType.APPLICATION_JSON))
        server.expect(
            requestTo("http://hanjeok.test/api/v1/attractions/1001/alternatives?date=2026-08-15&radius=15"),
        ).andRespond(withSuccess("""{"success":true,"error":null,"data":[]}""", MediaType.APPLICATION_JSON))

        client.congestion(1001, "2026-08-15")
        client.alternatives(1001, "2026-08-15", 15)

        server.verify()
    }

    @Test
    fun `success 가 true 여도 data 가 JSON null 이면 실패로 다룬다`() {
        // data 필드가 아예 없는 경우와 있지만 null 인 경우는 Jackson 에서 서로
        // 다른 값(Kotlin null vs NullNode)으로 온다. 이 값을 구분하지 않으면
        // "존재하지 않는 사실"이 빈 것처럼 생긴 JsonNode 로 조용히 통과한다.
        server.expect(requestTo("http://hanjeok.test/api/v1/courses/abc"))
            .andRespond(
                withSuccess("""{"success":true,"error":null,"data":null}""", MediaType.APPLICATION_JSON),
            )

        assertThatThrownBy { client.course("abc") }
            .isInstanceOf(HanjeokUnavailableException::class.java)
            .hasMessageContaining("no data")
    }

    @Test
    fun `연결 오류도 예외로 바뀐다`() {
        server.expect(requestTo("http://hanjeok.test/api/v1/courses/abc"))
            .andRespond(withException(IOException("connection refused")))

        assertThatThrownBy { client.course("abc") }
            .isInstanceOf(HanjeokUnavailableException::class.java)
    }
}
