package com.hermes.llm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class CapturingEndpointTest {

    @Test
    fun `받은 POST 본문을 그대로 돌려준다`() {
        CapturingEndpoint().use { endpoint ->
            val request = HttpRequest.newBuilder(URI.create("${endpoint.baseUrl}/v1/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"hello":"world"}"""))
                .build()

            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

            assertThat(endpoint.capturedBody()["hello"].asText()).isEqualTo("world")
        }
    }

    @Test
    fun `받은 요청의 경로를 그대로 돌려준다`() {
        CapturingEndpoint().use { endpoint ->
            val request = HttpRequest.newBuilder(URI.create("${endpoint.baseUrl}/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"hello":"world"}"""))
                .build()

            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

            assertThat(endpoint.capturedPath()).isEqualTo("/v1/chat/completions")
        }
    }
}
