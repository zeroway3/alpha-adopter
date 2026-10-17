package com.alphaadopter.core.ai.agent

import com.alphaadopter.core.IntegrationTestBase
import com.alphaadopter.core.domain.news.NewsArticle
import com.alphaadopter.core.domain.news.NewsArticleRepository
import com.alphaadopter.core.domain.notification.Notification
import com.alphaadopter.core.domain.notification.NotificationRepository
import com.alphaadopter.core.domain.notification.NotificationStatus
import com.alphaadopter.core.domain.subscription.Subscription
import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import com.alphaadopter.core.domain.subscription.SubscriptionType
import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

// RelevanceEvalReport와 동일한 사상(측정된 AI 필터)을, DB 상태를 조사하는 멀티스텝 에이전트로
// 확장한 리포트다. relevance-eval-set.json처럼 정적 JSON으로 라벨링할 수 없는 이유: 이
// 에이전트는 Claude에게 "기사 텍스트"를 주는 게 아니라 "조사 대상 userId"만 주고, 나머지는
// 도구 호출로 DB를 스스로 조사하게 시킨다 — 그래서 시나리오 자체를 실제 DB 행(가입일·구독
// 생성 시각·알림 참여도)으로 심어야 한다.
//
// RelevanceEvalReport처럼 pass/fail 어서션을 걸지 않고 리포트만 출력한다 — LLM 판단은
// 4~5건 샘플로 정확도를 "통과/실패"로 단정하기엔 샘플이 너무 작고, 사람이 reasoning까지
// 읽고 판단하는 게 지금 단계에선 더 정확하다(RelevanceEvalReport 주석 참고).
//
// 실제 API를 호출하므로(비용 발생, 시나리오당 최대 5회 Claude 왕복) ANTHROPIC_API_KEY가 없는
// 환경(CI 등)에서는 자동 스킵되고, 로컬에서 키를 export한 뒤 수동으로 실행할 때만 돈다.
class UserInvestigationEvalReport : IntegrationTestBase() {

    @Autowired
    lateinit var agent: UserInvestigationAgent

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var subscriptionRepository: SubscriptionRepository

    @Autowired
    lateinit var newsArticleRepository: NewsArticleRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    data class Scenario(val name: String, val expectedSuspicious: Boolean, val userId: Long)

    private fun newUser(createdAt: Instant): User {
        val user = User(email = "eval-${System.nanoTime()}@example.com", isMember = true)
        user.createdAt = createdAt
        return userRepository.save(user)
    }

    private fun subscribe(user: User, keyword: String, createdAt: Instant) {
        val sub = Subscription(user = user, keyword = keyword, type = SubscriptionType.KEYWORD)
        sub.createdAt = createdAt
        subscriptionRepository.save(sub)
    }

    private fun sendNotification(subscription: Subscription, read: Boolean, clicked: Boolean) {
        val article = newsArticleRepository.save(
            NewsArticle(
                title = "기사-${System.nanoTime()}",
                description = "설명",
                link = "https://example.com/${System.nanoTime()}",
                publishedAt = Instant.now(),
            ),
        )
        val notification = Notification(subscription = subscription, newsArticle = article, status = NotificationStatus.SENT)
        if (read) notification.readAt = Instant.now()
        if (clicked) notification.clickedAt = Instant.now()
        notificationRepository.save(notification)
    }

    // 정상 사용자: 30일 전 가입, 구독이 시간을 두고 늘어났고, 참여도도 정상적(70% 읽음)
    private fun seedNormalUser(): Scenario {
        val now = Instant.now()
        val user = newUser(now.minus(Duration.ofDays(30)))
        val sub = run {
            subscribe(user, "삼성전자", now.minus(Duration.ofDays(30)))
            subscribe(user, "LG전자", now.minus(Duration.ofDays(20)))
            subscriptionRepository.findAllByUserId(user.id!!).first()
        }
        repeat(10) { i -> sendNotification(sub, read = i < 7, clicked = i < 4) }
        return Scenario("정상 사용자(30일 전 가입, 참여도 70%)", expectedSuspicious = false, userId = user.id!!)
    }

    // 가입 직후 구독 폭주: 방금 가입해서 15개(한도) 키워드를 몇 초 안에 전부 구독 — NAVER API
    // 할당량을 위협하는 패턴(README의 max-distinct-keywords=15 근거와 동일한 맥락)
    private fun seedBurstSubscriber(): Scenario {
        val now = Instant.now()
        val user = newUser(now)
        repeat(15) { i -> subscribe(user, "키워드$i-${System.nanoTime()}", now.plusSeconds(i.toLong())) }
        return Scenario("가입 직후 15개 키워드 폭주 구독(수 초 내)", expectedSuspicious = true, userId = user.id!!)
    }

    // 비실사용 계정: 가입은 오래됐고 구독도 있지만, 알림을 한 번도 안 읽음(20건 전달, 0% 참여)
    private fun seedDormantUser(): Scenario {
        val now = Instant.now()
        val user = newUser(now.minus(Duration.ofDays(10)))
        subscribe(user, "비트코인", now.minus(Duration.ofDays(10)))
        val sub = subscriptionRepository.findAllByUserId(user.id!!).first()
        repeat(20) { sendNotification(sub, read = false, clicked = false) }
        return Scenario("비실사용 계정(알림 20건, 참여 0%)", expectedSuspicious = true, userId = user.id!!)
    }

    // 평범한 신규 사용자: 방금 가입했지만 구독을 몇 분 간격으로 2개만 만듦(폭주 아님),
    // 아직 알림이 없는 콜드스타트 — "신규"라는 것만으로 의심해서는 안 되는 케이스
    private fun seedNewButNormalUser(): Scenario {
        val now = Instant.now()
        val user = newUser(now)
        subscribe(user, "카카오", now)
        subscribe(user, "네이버", now.plus(Duration.ofMinutes(3)))
        return Scenario("평범한 신규 가입(구독 2개, 콜드스타트)", expectedSuspicious = false, userId = user.id!!)
    }

    @Test
    @Transactional
    fun `시나리오별 조사 결과 리포트를 출력한다`() {
        val apiKey = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        assumeTrue(apiKey.isNotBlank(), "ANTHROPIC_API_KEY가 없어 스킵합니다")

        val scenarios = listOf(seedNormalUser(), seedBurstSubscriber(), seedDormantUser(), seedNewButNormalUser())

        println("=== 사용자 조사 에이전트 평가 리포트 ===")
        var matches = 0
        scenarios.forEach { scenario ->
            val verdict = agent.investigate(scenario.userId)
            // LOW/INCONCLUSIVE면 "의심 안 함", MEDIUM/HIGH면 "의심함"으로 이분화해 기대값과 비교한다
            val predictedSuspicious = verdict.riskLevel == RiskLevel.MEDIUM || verdict.riskLevel == RiskLevel.HIGH
            val match = predictedSuspicious == scenario.expectedSuspicious
            if (match) matches++

            println(
                "[${if (match) "O" else "X"}] ${scenario.name}\n" +
                    "    기대=${if (scenario.expectedSuspicious) "의심" else "정상"} " +
                    "실제=${verdict.riskLevel}(도구 ${verdict.toolCallsUsed}회) " +
                    "권장조치=\"${verdict.recommendedAction}\"\n" +
                    "    근거: ${verdict.reasoning}",
            )
        }
        println("\n=== 일치율: $matches/${scenarios.size} ===")
    }
}
