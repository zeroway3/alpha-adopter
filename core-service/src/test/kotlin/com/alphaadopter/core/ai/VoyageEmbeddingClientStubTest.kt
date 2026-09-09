package com.alphaadopter.core.ai

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

// MockRestServiceServer로 실제 네트워크 호출 없이 임베딩 응답 파싱·재시도를 검증한다.
class VoyageEmbeddingClientStubTest {

    private fun clientWithStubbedResponse(body: String, maxRetries: Int = 3): VoyageEmbeddingClient {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
        return VoyageEmbeddingClient(builder, apiKey = "fake-key", model = "voyage-4-lite", maxRetries = maxRetries, retryInitialDelayMs = 1)
    }

    @Test
    fun `임베딩 응답을 DoubleArray로 정상 파싱한다`() {
        val client = clientWithStubbedResponse("""{"data":[{"embedding":[0.1,0.2,0.3],"index":0}]}""")

        val vector = client.embed("삼성전자 반도체 실적")

        assertContentEquals(doubleArrayOf(0.1, 0.2, 0.3), vector)
    }

    @Test
    fun `data가 비어있으면 예외를 던진다`() {
        val client = clientWithStubbedResponse("""{"data":[]}""")

        assertFailsWith<IllegalStateException> { client.embed("text") }
    }

    @Test
    fun `API 키가 없으면 호출 전에 예외를 던진다`() {
        val client = VoyageEmbeddingClient(RestClient.builder(), apiKey = "", model = "m", maxRetries = 3, retryInitialDelayMs = 1)

        assertFailsWith<IllegalStateException> { client.embed("text") }
    }

    @Test
    fun `429 응답이면 재시도 후 성공한다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"data":[{"embedding":[1.0,2.0]}]}""", MediaType.APPLICATION_JSON))

        val client = VoyageEmbeddingClient(builder, apiKey = "fake-key", model = "voyage-4-lite", maxRetries = 3, retryInitialDelayMs = 1)

        val vector = client.embed("text")

        assertContentEquals(doubleArrayOf(1.0, 2.0), vector)
        server.verify()
    }

    @Test
    fun `429가 최대 재시도 횟수를 넘으면 예외를 던진다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        repeat(2) {
            server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        }

        val client = VoyageEmbeddingClient(builder, apiKey = "fake-key", model = "m", maxRetries = 1, retryInitialDelayMs = 1)

        assertFailsWith<HttpClientErrorException> { client.embed("text") }
        server.verify()
    }
}
