package com.alphaadopter.core.admin

import com.alphaadopter.core.ai.agent.RiskLevel
import com.alphaadopter.core.ai.agent.UserInvestigationAgent
import com.alphaadopter.core.auth.AuthPrincipal
import com.alphaadopter.core.domain.user.UserRole
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class InvestigationResponse(
    val riskLevel: RiskLevel,
    val reasoning: String,
    val recommendedAction: String,
    val toolCallsUsed: Int,
)

// 관리자가 특정 사용자를 지목해 "이 계정 활동이 정상인가"를 멀티스텝 에이전트(
// UserInvestigationAgent)에게 조사시키는 엔드포인트. 에이전트는 읽기 전용 도구만 쓰고 어떤
// 계정 조치도 직접 실행하지 않는다 — 결과는 항상 관리자 판단을 위한 참고 자료일 뿐이다.
@RestController
@RequestMapping("/api/admin/users")
class AdminInvestigationController(
    private val userInvestigationAgent: UserInvestigationAgent,
) {

    @PostMapping("/{userId}/investigate")
    fun investigate(
        @PathVariable userId: Long,
        @AuthenticationPrincipal principal: AuthPrincipal,
    ): InvestigationResponse {
        if (principal.role != UserRole.ADMIN) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 접근할 수 있습니다.")
        }
        val verdict = userInvestigationAgent.investigate(userId)
        return InvestigationResponse(
            riskLevel = verdict.riskLevel,
            reasoning = verdict.reasoning,
            recommendedAction = verdict.recommendedAction,
            toolCallsUsed = verdict.toolCallsUsed,
        )
    }
}
