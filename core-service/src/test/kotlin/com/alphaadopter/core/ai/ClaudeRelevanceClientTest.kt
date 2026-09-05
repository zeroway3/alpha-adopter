package com.alphaadopter.core.ai

import com.alphaadopter.core.IntegrationTestBase
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertTrue

// 실제 Claude API를 호출해서 검증한다. ANTHROPIC_API_KEY가 없는 환경(CI 등)에서는
// 비용이 발생하지 않도록 자동으로 스킵한다 — 로컬에서 키를 export하고 수동 실행할 때만 돈다.
class ClaudeRelevanceClientTest : IntegrationTestBase() {

    @Autowired
    lateinit var claudeRelevanceClient: ClaudeRelevanceClient

    @Test
    fun `실제로 관련 있는 기사가 무관한 기사보다 높은 점수를 받는다`() {
        assumeTrue(claudeRelevanceClient.isConfigured, "ANTHROPIC_API_KEY가 없어 스킵합니다")

        val relevantScore = claudeRelevanceClient.scoreRelevance(
            keyword = "삼성전자",
            title = "삼성전자, 3분기 반도체 영업이익 급증",
            description = "삼성전자가 3분기 반도체 부문에서 시장 예상을 뛰어넘는 영업이익을 기록했다고 발표했다.",
        )

        val irrelevantScore = claudeRelevanceClient.scoreRelevance(
            keyword = "삼성전자",
            title = "동네 카페에서 삼성전자 다니는 친구를 만났다는 후기",
            description = "오늘 카페에서 우연히 대학 동창을 만났는데, 삼성전자에 다닌다고 근황을 전했다. 커피가 맛있었다.",
        )

        assertTrue(
            relevantScore > irrelevantScore,
            "관련 기사 점수($relevantScore)가 무관한 기사 점수($irrelevantScore)보다 높아야 합니다",
        )
    }
}
