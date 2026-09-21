package com.hermes.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * 요청 본문을 받아 두고 500 을 돌려주는 루프백 엔드포인트.
 *
 * 캐시가 맞느냐는 결국 "직렬화된 요청이 같으냐"의 문제라, SDK 파라미터 객체가
 * 아니라 실제로 나가는 바이트를 봐야 한다. 127.0.0.1 만 듣고 키를 요구하지
 * 않으므로 `./gradlew build` 가 여전히 네트워크에도 자격 증명에도 묶이지 않는다.
 *
 * 응답을 500 으로 주는 이유: 성공 응답을 흉내 내려면 프로바이더마다 다른 스키마를
 * 지어내야 하고, 그 가짜가 진짜와 어긋나는 순간 테스트가 거짓말을 시작한다.
 * 여기서 필요한 것은 요청뿐이다.
 *
 * 스트리밍 테스트(Task 4)는 진짜 SSE 를 돌려줘야 해서, 인자로 `CannedResponse` 를
 * 받을 수 있게 됐다. `null`(기본값)이면 지금까지와 바이트까지 같은 500 을 낸다 —
 * 기존 테스트가 전부 인자 없이 이 엔드포인트를 생성해 그 500 에 기대고 있다.
 */
data class CannedResponse(val status: Int, val contentType: String, val body: String)

class CapturingEndpoint(private val response: CannedResponse? = null) : AutoCloseable {

    private val mapper = ObjectMapper()
    private var body: ByteArray? = null
    private var path: String? = null

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                body = exchange.requestBody.readBytes()
                path = exchange.requestURI.path
                val canned = response
                    ?: CannedResponse(
                        500,
                        "application/json",
                        """{"type":"error","error":{"type":"api_error","message":"captured"}}""",
                    )
                val payload = canned.body.toByteArray()
                exchange.responseHeaders.add("Content-Type", canned.contentType)
                exchange.sendResponseHeaders(canned.status, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            start()
        }

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    fun capturedBody(): JsonNode =
        mapper.readTree(body ?: error("no request was captured — did the call reach $baseUrl?"))

    fun capturedPath(): String =
        path ?: error("no request was captured — did the call reach $baseUrl?")

    override fun close() = server.stop(0)
}
