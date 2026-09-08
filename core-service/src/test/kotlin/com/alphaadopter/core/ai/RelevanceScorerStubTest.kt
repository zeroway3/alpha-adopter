package com.alphaadopter.core.ai

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertNull

// RelevanceScorer의 임계값 판정·fail-open 로직을 실제 네트워크 호출 없이 검증한다.
class RelevanceScorerStubTest {

    private fun scorerRespondingWith(threshold: Int = 50, respond: (org.springframework.test.web.client.ResponseActions) -> Unit): RelevanceScorer {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val expectation = server.expect(requestTo("https://api.anthropic.com/v1/messages"))
            .andExpect(method(HttpMethod.POST))
        respond(expectation)
        val client = ClaudeRelevanceClient(builder, apiKey = "fake-key-for-test", model = "claude-haiku-4-5-20251001")
        return RelevanceScorer(client, threshold = threshold)
    }

    @Test
    fun `API 키가 없으면 무조건 통과시킨다`() {
        val client = ClaudeRelevanceClient(RestClient.builder(), apiKey = "", model = "m")
        val scorer = RelevanceScorer(client, threshold = 50)

        val result = scorer.evaluate("keyword", "title", "desc")

        assertNull(result.score)
        assertEquals(true, result.relevant)
    }

    @Test
    fun `AI 호출이 실패해도 fail-open으로 통과시킨다`() {
        val scorer = scorerRespondingWith { it.andRespond(withServerError()) }

        val result = scorer.evaluate("keyword", "title", "desc")

        assertNull(result.score)
        assertEquals(true, result.relevant)
    }

    @Test
    fun `점수가 임계값 이상이면 통과시킨다`() {
        val scorer = scorerRespondingWith(threshold = 50) {
            it.andRespond(withSuccess("""{"content":[{"type":"tool_use","input":{"score":80}}]}""", MediaType.APPLICATION_JSON))
        }

        val result = scorer.evaluate("keyword", "title", "desc")

        assertEquals(80, result.score)
        assertEquals(true, result.relevant)
    }

    @Test
    fun `점수가 임계값 미만이면 차단한다`() {
        val scorer = scorerRespondingWith(threshold = 50) {
            it.andRespond(withSuccess("""{"content":[{"type":"tool_use","input":{"score":20}}]}""", MediaType.APPLICATION_JSON))
        }

        val result = scorer.evaluate("keyword", "title", "desc")

        assertEquals(20, result.score)
        assertEquals(false, result.relevant)
    }
}
