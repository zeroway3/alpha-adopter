package com.alphaadopter.core.ai.agent

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// MockRestServiceServer로 실제 Claude API 없이 멀티턴 에이전트 루프(관찰→도구 호출→재관찰을
// 반복하다가 report_verdict로 끝나는 구조)를 검증한다. ClaudeRelevanceClientStubTest와 달리
// 응답을 여러 번 큐에 넣어, 한 번의 investigate() 호출 안에서 HTTP 요청이 실제로 여러 번
// 왕복하는지까지 확인한다.
class UserInvestigationAgentStubTest {

    private fun stubExecutor(): InvestigationToolExecutor {
        val executor = Mockito.mock(InvestigationToolExecutor::class.java)
        Mockito.`when`(executor.execute(anyString(), anyLong())).thenReturn("구독 수: 15, 최초~최근 구독 간격: 4초")
        return executor
    }

    private fun agentWithStubbedResponses(vararg bodies: String): UserInvestigationAgent {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        bodies.forEach { body ->
            server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
        }
        return UserInvestigationAgent(
            builder,
            toolExecutor = stubExecutor(),
            meterRegistry = SimpleMeterRegistry(),
            apiKey = "fake-key-for-test",
            model = "claude-haiku-4-5-20251001",
            maxIterations = 5,
            maxRetries = 3,
            retryInitialDelayMs = 1,
        )
    }

    @Test
    fun `도구를 한 번 호출한 뒤 report_verdict로 끝나면 2번 왕복하고 결론을 반환한다`() {
        val agent = agentWithStubbedResponses(
            // 1번째 왕복: 모델이 구독 활동을 조사하겠다고 판단
            """{"content":[{"type":"tool_use","id":"call_1","name":"get_subscription_activity","input":{}}],"usage":{"input_tokens":200,"output_tokens":20}}""",
            // 2번째 왕복: 조사 결과를 보고 최종 결론
            """{"content":[{"type":"tool_use","id":"call_2","name":"report_verdict","input":{"riskLevel":"HIGH","reasoning":"가입 4초 만에 15개 구독","recommendedAction":"계정 검토 권장"}}],"usage":{"input_tokens":220,"output_tokens":30}}""",
        )

        val verdict = agent.investigate(userId = 1L)

        assertEquals(RiskLevel.HIGH, verdict.riskLevel)
        assertTrue(verdict.reasoning.contains("15개"))
        assertEquals(2, verdict.toolCallsUsed)
    }

    @Test
    fun `report_verdict 없이 도구만 반복 호출하면 최대 횟수에서 멈추고 INCONCLUSIVE를 반환한다`() {
        // 매 왕복마다 다른 조회 도구만 계속 부르고 끝내지 않는 모델을 흉내낸다
        val neverEndingResponse =
            """{"content":[{"type":"tool_use","id":"call_x","name":"get_user_profile","input":{}}],"usage":{"input_tokens":1,"output_tokens":1}}"""
        val agent = agentWithStubbedResponses(*Array(5) { neverEndingResponse })

        val verdict = agent.investigate(userId = 1L)

        assertEquals(RiskLevel.INCONCLUSIVE, verdict.riskLevel)
        assertEquals(5, verdict.toolCallsUsed)
    }

    @Test
    fun `API 키가 없으면 호출 없이 바로 INCONCLUSIVE를 반환한다`() {
        val agent = UserInvestigationAgent(
            RestClient.builder(),
            toolExecutor = stubExecutor(),
            meterRegistry = SimpleMeterRegistry(),
            apiKey = "",
            model = "m",
            maxIterations = 5,
            maxRetries = 3,
            retryInitialDelayMs = 1,
        )

        val verdict = agent.investigate(userId = 1L)

        assertEquals(RiskLevel.INCONCLUSIVE, verdict.riskLevel)
        assertEquals(0, verdict.toolCallsUsed)
    }

    @Test
    fun `모델이 도구 호출 없이 텍스트만 반환하면 INCONCLUSIVE로 처리한다`() {
        val agent = agentWithStubbedResponses(
            """{"content":[{"type":"text","text":"잘 모르겠습니다"}],"usage":{"input_tokens":1,"output_tokens":1}}""",
        )

        val verdict = agent.investigate(userId = 1L)

        assertEquals(RiskLevel.INCONCLUSIVE, verdict.riskLevel)
    }
}
