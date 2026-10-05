package com.alphaadopter.core.ai.agent

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

enum class RiskLevel { LOW, MEDIUM, HIGH, INCONCLUSIVE }

data class InvestigationVerdict(
    val riskLevel: RiskLevel,
    val reasoning: String,
    val recommendedAction: String,
    val toolCallsUsed: Int,
)

// 관리자가 "이 사용자 좀 살펴봐 달라"고 지목하면, 모델이 스스로 어떤 도구를 몇 번 부를지
// 판단해가며 조사하는 멀티스텝 에이전트 루프. ClaudeRelevanceClient(단발성 tool_choice 강제
// 호출)와 달리, tool_choice를 지정하지 않아 모델이 "관찰→판단→도구 호출"을 반복하다가
// report_verdict를 부르는 순간 끝난다 — ReAct 패턴.
//
// 읽기 전용 조사 도구만 제공하고 어떤 액션도 자동 실행하지 않는다(계정 정지/경고 등은 전부
// 관리자 판단 영역). 그래서 실패 모드도 단순하다 — API 키가 없거나, 모델이 끝없이 도구만
// 부르고 결론을 안 내거나, 호출이 실패하면 전부 "조사 불가(INCONCLUSIVE)"로 떨어뜨리고
// 사람이 직접 보게 한다(fail-open과 같은 원칙: 부가 기능 장애가 더 심한 조치로 이어지면 안 됨).
@Component
class UserInvestigationAgent(
    restClientBuilder: RestClient.Builder,
    private val toolExecutor: InvestigationToolExecutor,
    private val meterRegistry: MeterRegistry,
    @Value("\${app.ai.anthropic-api-key:}") private val apiKey: String,
    @Value("\${app.ai.model:claude-haiku-4-5-20251001}") private val model: String,
    @Value("\${app.ai.agent-max-iterations:5}") private val maxIterations: Int,
    @Value("\${app.ai.max-retries:3}") private val maxRetries: Int,
    @Value("\${app.ai.retry-initial-delay-ms:500}") private val retryInitialDelayMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val isConfigured: Boolean = apiKey.isNotBlank()

    private val restClient = restClientBuilder.baseUrl("https://api.anthropic.com").build()

    fun investigate(userId: Long): InvestigationVerdict {
        if (!isConfigured) {
            return inconclusive(0, "ANTHROPIC_API_KEY가 설정되지 않아 조사를 실행할 수 없습니다.")
        }

        var messages = listOf(AgentMessage("user", listOf(textBlock(initialPrompt(userId)))))

        return meterRegistry.timer("agent.investigation.duration").recordCallable {
            runCatching {
                repeat(maxIterations) { iteration ->
                    val response = callClaudeWithRetry(messages)
                    val toolUseBlocks = response.content.filter { it.type == "tool_use" }

                    if (toolUseBlocks.isEmpty()) {
                        return@recordCallable inconclusive(iteration + 1, "모델이 도구를 호출하지 않고 대화를 끝냈습니다.")
                    }

                    toolUseBlocks.firstOrNull { it.name == "report_verdict" }?.let { verdictCall ->
                        return@recordCallable parseVerdict(verdictCall, iteration + 1)
                    }

                    messages = messages + AgentMessage("assistant", response.content)
                    val toolResults = toolUseBlocks.map { toolUse ->
                        val result = toolExecutor.execute(toolUse.name ?: "", userId)
                        toolResultBlock(toolUse.id, result)
                    }
                    messages = messages + AgentMessage("user", toolResults)
                }
                inconclusive(maxIterations, "최대 반복 횟수($maxIterations)를 넘겨도 결론을 내지 못했습니다.")
            }.getOrElse { e ->
                log.warn("사용자 조사 에이전트 실행 실패(userId={}): {}", userId, e.message)
                inconclusive(0, "조사 중 오류가 발생했습니다: ${e.message}")
            }
        }!!
    }

    private fun parseVerdict(verdictCall: AgentContentBlock, toolCallsUsed: Int): InvestigationVerdict {
        val input = verdictCall.input
        val riskLevel = runCatching { RiskLevel.valueOf(input?.get("riskLevel")?.asString() ?: "") }
            .getOrDefault(RiskLevel.INCONCLUSIVE)
        meterRegistry.counter("agent.investigation.result", "riskLevel", riskLevel.name).increment()
        return InvestigationVerdict(
            riskLevel = riskLevel,
            reasoning = input?.get("reasoning")?.asString() ?: "",
            recommendedAction = input?.get("recommendedAction")?.asString() ?: "",
            toolCallsUsed = toolCallsUsed,
        )
    }

    private fun inconclusive(toolCallsUsed: Int, reason: String): InvestigationVerdict {
        meterRegistry.counter("agent.investigation.result", "riskLevel", RiskLevel.INCONCLUSIVE.name).increment()
        return InvestigationVerdict(RiskLevel.INCONCLUSIVE, reason, "관리자가 직접 확인", toolCallsUsed)
    }

    private fun callClaudeWithRetry(messages: List<AgentMessage>): AgentResponse {
        val request = AgentRequest(
            model = model,
            max_tokens = 1024,
            system = SYSTEM_PROMPT,
            messages = messages,
            tools = INVESTIGATION_TOOLS,
        )

        var attempt = 0
        var delayMs = retryInitialDelayMs
        while (true) {
            try {
                val response = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(AgentResponse::class.java)
                    ?: error("Claude 응답 본문이 비어 있습니다.")

                meterRegistry.counter("agent.investigation.tokens", "type", "input").increment(response.usage.input_tokens.toDouble())
                meterRegistry.counter("agent.investigation.tokens", "type", "output").increment(response.usage.output_tokens.toDouble())
                return response
            } catch (e: HttpClientErrorException) {
                attempt++
                if (e.statusCode.value() != 429 || attempt > maxRetries) throw e
                meterRegistry.counter("agent.investigation.retries").increment()
                log.warn("Claude API 429(rate limit), ${delayMs}ms 후 재시도 ($attempt/$maxRetries)")
                Thread.sleep(delayMs)
                delayMs *= 2
            }
        }
    }

    private fun initialPrompt(userId: Long) = """
        userId=$userId 사용자의 활동 패턴이 정상적인지 조사해주세요.
        특히 다음을 확인하세요: 가입 직후 비정상적으로 빠르게 다수 키워드를 구독해
        NAVER API 할당량을 위협하는 패턴인지, 알림을 받고도 전혀 읽지 않는 비실사용(봇으로
        의심되는) 패턴인지.

        필요한 도구를 순서대로 호출해 조사하고, 충분히 확인했다고 판단되면 report_verdict로
        결론을 보고하세요. 근거 없이 추측하지 말고, 조사한 데이터에 기반해서만 판단하세요.
    """.trimIndent()

    companion object {
        private const val SYSTEM_PROMPT =
            "당신은 뉴스 알림 서비스의 어뷰징 여부를 조사하는 보조 조사관입니다. " +
                "제공된 도구로 데이터를 조회해 판단 근거를 모으기만 하고, 계정 정지나 제재 같은 " +
                "실제 조치는 절대 직접 수행하지 않습니다 — 최종 판단과 조치는 항상 사람(관리자)이 합니다."
    }
}
