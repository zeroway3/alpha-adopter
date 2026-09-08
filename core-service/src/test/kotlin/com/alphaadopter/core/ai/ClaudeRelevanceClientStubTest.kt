package com.alphaadopter.core.ai

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// MockRestServiceServer로 실제 네트워크 호출 없이 tool_use 구조화 응답 파싱을 검증한다.
// API 키·Spring 컨텍스트가 필요 없어 CI에서도 항상, 빠르게 돈다 (ClaudeRelevanceClientTest는
// 실제 API를 호출하는 별도 테스트이며 키가 없으면 스킵된다). Redis는 항상 캐시 미스로
// 동작하는 목으로 대체해 순수 파싱 로직만 검증한다 — 캐싱 자체는 ClaudeRelevanceClientCachingTest에서 다룬다.
class ClaudeRelevanceClientStubTest {

    private fun alwaysMissRedis(): StringRedisTemplate {
        val template = Mockito.mock(StringRedisTemplate::class.java)
        val ops = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        Mockito.`when`(template.opsForValue()).thenReturn(ops)
        Mockito.`when`(ops.get(any())).thenReturn(null)
        return template
    }

    private fun clientWithStubbedResponse(body: String): ClaudeRelevanceClient {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
        return ClaudeRelevanceClient(
            builder,
            redisTemplate = alwaysMissRedis(),
            meterRegistry = SimpleMeterRegistry(),
            apiKey = "fake-key-for-test",
            model = "claude-haiku-4-5-20251001",
            cacheTtlHours = 24,
            maxRetries = 3,
            retryInitialDelayMs = 1,
        )
    }

    @Test
    fun `tool_use 응답에서 score를 정상적으로 파싱한다`() {
        val client = clientWithStubbedResponse(
            body = """{"content":[{"type":"tool_use","name":"score_relevance","input":{"score":87}}],"usage":{"input_tokens":120,"output_tokens":8}}""",
        )

        val score = client.scoreRelevance(keyword = "삼성전자", title = "제목", description = "본문")

        assertEquals(87, score)
    }

    @Test
    fun `점수가 범위를 벗어나면 0~100으로 clamp한다`() {
        val client = clientWithStubbedResponse(
            body = """{"content":[{"type":"tool_use","input":{"score":150}}]}""",
        )

        val score = client.scoreRelevance(keyword = "k", title = "t", description = "d")

        assertEquals(100, score)
    }

    @Test
    fun `tool_use 블록이 없으면 예외를 던진다`() {
        val client = clientWithStubbedResponse(
            body = """{"content":[{"type":"text","text":"모르겠습니다"}]}""",
        )

        assertFailsWith<IllegalStateException> {
            client.scoreRelevance(keyword = "k", title = "t", description = "d")
        }
    }

    @Test
    fun `API 키가 없으면 호출 전에 예외를 던진다`() {
        val client = ClaudeRelevanceClient(
            RestClient.builder(),
            redisTemplate = alwaysMissRedis(),
            meterRegistry = SimpleMeterRegistry(),
            apiKey = "",
            model = "m",
            cacheTtlHours = 24,
            maxRetries = 3,
            retryInitialDelayMs = 1,
        )

        assertFailsWith<IllegalStateException> {
            client.scoreRelevance(keyword = "k", title = "t", description = "d")
        }
    }
}
