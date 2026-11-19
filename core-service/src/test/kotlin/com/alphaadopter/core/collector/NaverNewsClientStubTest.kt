package com.alphaadopter.core.collector

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

// MockRestServiceServer로 실제 네트워크 없이 요청 파라미터(최신순 정렬·페이지)와 재시도 정책을 검증한다.
class NaverNewsClientStubTest {

    private val body = """{"total":1,"start":1,"display":1,"items":[{"title":"<b>삼성전자</b> 실적","originallink":"o","link":"l","description":"d","pubDate":"Fri, 04 Sep 2026 15:58:00 +0900"}]}"""

    private fun client(builder: RestClient.Builder, maxRetries: Int = 3) =
        NaverNewsClient(builder, clientId = "id", clientSecret = "secret", maxRetries = maxRetries, retryInitialDelayMs = 1)

    private fun searchUrl() = org.hamcrest.Matchers.startsWith("https://naverapihub.apigw.ntruss.com/search/v1/news")

    @Test
    fun `최신순 정렬과 페이지 파라미터를 붙여 요청한다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(searchUrl()))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("sort", "date"))
            .andExpect(queryParam("display", "100"))
            .andExpect(queryParam("start", "101"))
            // NAVER API HUB는 JSON을 text/plain으로 내려준다
            .andRespond(withSuccess(body, MediaType.TEXT_PLAIN))

        val response = client(builder).searchNews("삼성전자", display = 100, start = 101)

        assertEquals(1, response.items.size)
        server.verify()
    }

    @Test
    fun `429와 5xx는 재시도 후 성공한다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(searchUrl())).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        server.expect(requestTo(searchUrl())).andRespond(withStatus(HttpStatus.BAD_GATEWAY))
        server.expect(requestTo(searchUrl())).andRespond(withSuccess(body, MediaType.TEXT_PLAIN))

        val response = client(builder).searchNews("삼성전자")

        assertEquals(1, response.items.size)
        server.verify()
    }

    @Test
    fun `인증 오류는 재시도하지 않고 바로 실패한다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.once(), requestTo(searchUrl())).andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        assertFailsWith<HttpClientErrorException> { client(builder).searchNews("삼성전자") }
        server.verify()
    }

    @Test
    fun `재시도 횟수를 넘기면 실패한다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.times(3), requestTo(searchUrl())).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

        assertFailsWith<HttpClientErrorException> { client(builder, maxRetries = 2).searchNews("삼성전자") }
        server.verify()
    }

    @Test
    fun `인증정보가 없으면 미설정 상태로 본다`() {
        val client = NaverNewsClient(RestClient.builder(), clientId = "", clientSecret = "", maxRetries = 3, retryInitialDelayMs = 1)

        assertFalse(client.isConfigured)
    }
}
