package com.alphaadopter.core.admin

import com.alphaadopter.core.IntegrationTestBase
import com.alphaadopter.core.ai.agent.RiskLevel
import com.alphaadopter.core.auth.AuthPrincipal
import com.alphaadopter.core.domain.user.UserRole
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// ANTHROPIC_API_KEY가 로컬에 export돼 있어도 테스트 결과가 달라지면 안 되므로
// (NewsMatchingPipelineIntegrationTest와 동일한 이유) AI를 명시적으로 비활성화한다 — 이
// 테스트는 에이전트의 판단 품질이 아니라 접근 제어만 검증한다.
@TestPropertySource(properties = ["app.ai.anthropic-api-key="])
class AdminInvestigationControllerTest : IntegrationTestBase() {

    @Autowired
    lateinit var adminInvestigationController: AdminInvestigationController

    @Test
    fun `비관리자는 조사를 요청할 수 없다`() {
        val nonAdmin = AuthPrincipal(userId = 1L, email = "not-admin@example.com", role = UserRole.USER)

        val ex = assertFailsWith<ResponseStatusException> {
            adminInvestigationController.investigate(userId = 1L, principal = nonAdmin)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `관리자는 조사를 요청할 수 있고, AI가 비활성화돼 있으면 INCONCLUSIVE를 받는다`() {
        val admin = AuthPrincipal(userId = 2L, email = "admin@example.com", role = UserRole.ADMIN)

        val response = adminInvestigationController.investigate(userId = 1L, principal = admin)

        assertEquals(RiskLevel.INCONCLUSIVE, response.riskLevel)
        assertEquals(0, response.toolCallsUsed)
    }
}
