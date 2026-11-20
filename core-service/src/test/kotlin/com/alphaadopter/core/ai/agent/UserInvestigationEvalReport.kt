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

    private companion object {
        // 시나리오의 구독 키워드는 실제 종목명으로 둔다. 처음엔 "자동0~9", "급증0~13" 같은 이름을 썼더니
        // 에이전트가 시간 패턴이 아니라 "더미 키워드 이름"을 근거로 판단해, 실제 서비스에서는 없는 힌트로
        // 점수가 부풀려졌다. 0번(삼성전자)은 기존 구독으로 쓰는 시나리오가 있어 맨 앞에 둔다.
        val REAL_KEYWORDS = listOf(
            "삼성전자", "SK하이닉스", "현대차", "LG에너지솔루션", "셀트리온", "카카오", "네이버", "기아",
            "POSCO홀딩스", "한화에어로스페이스", "KB금융", "삼성바이오로직스", "LG화학", "삼성SDI", "HD현대중공업",
        )
    }

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
        repeat(15) { i -> subscribe(user, REAL_KEYWORDS[i], now.plusSeconds(i.toLong())) }
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

    // ── 아래부터는 숫자 하나만 보고 판단하면 틀리는 경계 사례들 ──

    // 오래된 헤비 유저: 구독은 12개로 많지만 석 달에 걸쳐 천천히 늘었고 참여도도 높다 — "구독 수가
    // 많다"만으로 의심하면 안 되는 케이스
    private fun seedLongTimeHeavyUser(): Scenario {
        val now = Instant.now()
        val user = newUser(now.minus(Duration.ofDays(90)))
        repeat(12) { i -> subscribe(user, REAL_KEYWORDS[i], now.minus(Duration.ofDays(90L - i * 7))) }
        val sub = subscriptionRepository.findAllByUserId(user.id!!).first()
        repeat(15) { i -> sendNotification(sub, read = i < 9, clicked = i < 5) }
        return Scenario("오래된 헤비 유저(구독 12개를 석 달에 걸쳐, 참여 60%)", expectedSuspicious = false, userId = user.id!!)
    }

    // 표본이 적은 저참여: 가입 7일, 알림 3건을 아직 안 읽음 — 0%지만 판단하기엔 표본이 너무 적다
    private fun seedLowSampleUser(): Scenario {
        val now = Instant.now()
        val user = newUser(now.minus(Duration.ofDays(7)))
        subscribe(user, "금리", now.minus(Duration.ofDays(7)))
        val sub = subscriptionRepository.findAllByUserId(user.id!!).first()
        repeat(3) { sendNotification(sub, read = false, clicked = false) }
        return Scenario("표본 적은 저참여(가입 7일, 알림 3건 미열람)", expectedSuspicious = false, userId = user.id!!)
    }

    // 빠르지만 적은 구독: 가입 직후 1분 안에 3개 — 속도만 보면 빠르지만 개수가 평범하다
    private fun seedQuickButFewUser(): Scenario {
        val now = Instant.now()
        val user = newUser(now)
        listOf("삼성전자", "SK하이닉스", "현대차").forEachIndexed { i, k -> subscribe(user, k, now.plusSeconds(i * 20L)) }
        return Scenario("가입 직후 1분 내 구독 3개(빠르지만 적음)", expectedSuspicious = false, userId = user.id!!)
    }

    // 신규 폭주(한도보다 적음): 가입 직후 10초 안에 10개 — 한도(15) 미만이어도 자동화 패턴
    private fun seedFastTenSubscriber(): Scenario {
        val now = Instant.now()
        val user = newUser(now)
        repeat(10) { i -> subscribe(user, REAL_KEYWORDS[i], now.plusSeconds(i.toLong())) }
        return Scenario("가입 직후 10초 내 구독 10개", expectedSuspicious = true, userId = user.id!!)
    }

    // 장기 비실사용: 알림 50건 중 1건만 읽음(2%) — 참여 0%가 아니어도 사실상 비실사용
    private fun seedNearlyDormantUser(): Scenario {
        val now = Instant.now()
        val user = newUser(now.minus(Duration.ofDays(45)))
        subscribe(user, "테슬라", now.minus(Duration.ofDays(45)))
        val sub = subscriptionRepository.findAllByUserId(user.id!!).first()
        repeat(50) { i -> sendNotification(sub, read = i == 0, clicked = false) }
        return Scenario("장기 저참여(알림 50건 중 1건 열람)", expectedSuspicious = true, userId = user.id!!)
    }

    // 오래된 계정의 갑작스러운 폭주: 30일 전 가입한 계정이 오늘 몇 초 안에 14개를 구독 — 가입일만
    // 보고 "오래된 계정이니 정상"으로 판단하면 놓치는 패턴(계정 탈취·자동화 의심)
    private fun seedDormantThenBurstUser(): Scenario {
        val now = Instant.now()
        val user = newUser(now.minus(Duration.ofDays(30)))
        subscribe(user, "삼성전자", now.minus(Duration.ofDays(30)))
        repeat(14) { i -> subscribe(user, REAL_KEYWORDS[i + 1], now.plusSeconds(i.toLong())) }
        return Scenario("30일 된 계정이 오늘 수 초 내 14개 구독", expectedSuspicious = true, userId = user.id!!)
    }

    // ── 검증용(hold-out) 시나리오 ──
    // 위 10개(튜닝용)는 실제 평가 결과를 보고 도구를 고치는 데 썼으므로, 그 시나리오로 다시 잰 점수는
    // 실제보다 좋게 나온다. 아래 10개는 도구 수정 전에, 결과를 보지 않고 정답을 먼저 정해 둔 뒤
    // 성능 보고에만 쓴다. 이 시나리오를 보고 도구나 프롬프트를 고치면 검증용 의미가 사라진다.
    private fun seed(
        name: String,
        expectedSuspicious: Boolean,
        joinedDaysAgo: Long,
        subscriptionTimes: List<Instant>,
        sent: Int = 0,
        read: Int = 0,
    ): Scenario {
        val user = newUser(Instant.now().minus(Duration.ofDays(joinedDaysAgo)))
        subscriptionTimes.forEachIndexed { i, at -> subscribe(user, REAL_KEYWORDS[i], at) }
        if (sent > 0) {
            val sub = subscriptionRepository.findAllByUserId(user.id!!).first()
            repeat(sent) { i -> sendNotification(sub, read = i < read, clicked = false) }
        }
        return Scenario(name, expectedSuspicious, user.id!!)
    }

    private fun holdOutScenarios(): List<Scenario> {
        val now = Instant.now()
        fun daysAgo(d: Long) = now.minus(Duration.ofDays(d))
        return listOf(
            seed("60일 계정, 2주 간격 구독 5개, 알림 30건 중 12건 열람", false, 60, (0..4).map { daysAgo(60L - it * 14) }, 30, 12),
            seed("가입 3일, 하루 간격 구독 4개, 알림 2건 미열람", false, 3, (0..3).map { daysAgo(3L - it).plusSeconds(60) }, 2, 0),
            seed("1년 계정, 구독 8개, 알림 100건 중 25건 열람", false, 365, (0..7).map { daysAgo(365L - it * 40) }, 100, 25),
            seed("오늘 가입, 5분에 걸쳐 1분 간격으로 구독 5개", false, 0, (0..4).map { now.plusSeconds(it * 70L) }),
            seed("오래된 계정이 오늘 몇 시간 간격으로 구독 3개 추가", false, 120, listOf(daysAgo(120)) + (1..3).map { now.minus(Duration.ofHours(it * 3L)) }),
            seed("가입 직후 30초 안에 구독 8개", true, 0, (0..7).map { now.plusSeconds(it * 4L) }),
            seed("90일 계정, 알림 80건 모두 미열람", true, 90, listOf(daysAgo(90)), 80, 0),
            seed("60일 계정이 오늘 1분 안에 구독 12개 추가", true, 60, listOf(daysAgo(60)) + (1..12).map { now.plusSeconds(it * 4L) }),
            seed("가입 2일, 2분 안에 구독 15개", true, 2, (0..14).map { daysAgo(2).plusSeconds(it * 8L) }),
            seed("하루 안에 구독 13개 + 알림 40건 모두 미열람", true, 1, (0..12).map { daysAgo(1).plusSeconds(it * 600L) }, 40, 0),
        )
    }

    private data class SetResult(val markdown: String, val correct: Int, val total: Int)

    private fun evaluate(title: String, scenarios: List<Scenario>, runs: Int): SetResult {
        val sb = StringBuilder("### $title (시나리오 ${scenarios.size}개 × ${runs}회)\n\n")
        sb.append("| 시나리오 | 기대 | 판정(회차별) | 일치 |\n|---|---|---|---|\n")
        var correct = 0
        var majorityCorrect = 0
        var consistent = 0
        var falsePositives = 0
        var falseNegatives = 0
        var inconclusive = 0
        val toolCalls = mutableListOf<Int>()
        val latenciesMs = mutableListOf<Long>()
        val tokensBefore = tokenCount("input") to tokenCount("output")

        scenarios.forEach { scenario ->
            val verdicts = (1..runs).map {
                val started = System.nanoTime()
                agent.investigate(scenario.userId).also { latenciesMs += (System.nanoTime() - started) / 1_000_000 }
            }
            // LOW/INCONCLUSIVE면 "의심 안 함", MEDIUM/HIGH면 "의심함"으로 이분화해 기대값과 비교한다
            val predictions = verdicts.map { it.riskLevel == RiskLevel.MEDIUM || it.riskLevel == RiskLevel.HIGH }
            val hits = predictions.count { it == scenario.expectedSuspicious }
            correct += hits
            if (hits * 2 > runs) majorityCorrect++
            if (predictions.distinct().size == 1) consistent++
            inconclusive += verdicts.count { it.riskLevel == RiskLevel.INCONCLUSIVE }
            if (scenario.expectedSuspicious) falseNegatives += runs - hits else falsePositives += runs - hits
            toolCalls += verdicts.map { it.toolCallsUsed }
            sb.append(
                "| ${scenario.name} | ${if (scenario.expectedSuspicious) "의심" else "정상"} | " +
                    "${verdicts.joinToString(", ") { it.riskLevel.name }} | $hits/$runs |\n",
            )
        }

        val total = scenarios.size * runs
        val (low, high) = wilson(correct, total)
        val inputTokens = tokenCount("input") - tokensBefore.first
        val outputTokens = tokenCount("output") - tokensBefore.second
        // claude-haiku-4-5 단가: 입력 $1, 출력 $5 / 100만 토큰
        val costPerInvestigation = (inputTokens * 1.0 + outputTokens * 5.0) / 1_000_000 / total
        latenciesMs.sort()
        sb.append(
            "\n- 정확도(전체 판정): $correct/$total = ${pct(correct, total)} (95% 신뢰구간 ${"%.0f".format(low * 100)}~${"%.0f".format(high * 100)}%)\n" +
                "- 다수결 기준 정확도(시나리오별 ${runs}회 중 과반): $majorityCorrect/${scenarios.size}\n" +
                "- 정상을 의심(오탐): ${falsePositives}건, 의심을 놓침(미탐): ${falseNegatives}건, 판단 불가: ${inconclusive}건\n" +
                "- 회차 간 판정이 모두 같았던 시나리오: $consistent/${scenarios.size}\n" +
                "- 조사 1건당: 도구 호출 평균 ${"%.1f".format(toolCalls.average())}회, " +
                "소요 시간 p50 ${latenciesMs[latenciesMs.size / 2]}ms / p95 ${latenciesMs[(latenciesMs.size * 95 / 100).coerceAtMost(latenciesMs.size - 1)]}ms, " +
                "토큰 입력 ${inputTokens / total}·출력 ${outputTokens / total}, 비용 약 \$${"%.4f".format(costPerInvestigation)}\n\n",
        )
        return SetResult(sb.toString(), correct, total)
    }

    private fun tokenCount(type: String): Long =
        meterRegistry.find("agent.investigation.tokens").tag("type", type).counter()?.count()?.toLong() ?: 0L

    private fun pct(n: Int, d: Int) = "${"%.0f".format(n * 100.0 / d)}%"

    // 표본이 작을 때 정규근사보다 안정적인 Wilson 점수 구간(95%)
    private fun wilson(successes: Int, n: Int): Pair<Double, Double> {
        val z = 1.96
        val p = successes.toDouble() / n
        val denom = 1 + z * z / n
        val center = (p + z * z / (2 * n)) / denom
        val margin = z * kotlin.math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom
        return (center - margin).coerceAtLeast(0.0) to (center + margin).coerceAtMost(1.0)
    }

    @Autowired
    lateinit var meterRegistry: io.micrometer.core.instrument.MeterRegistry

    @Test
    @Transactional
    fun `시나리오별 조사 결과 리포트를 출력한다`() {
        val apiKey = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        assumeTrue(apiKey.isNotBlank(), "ANTHROPIC_API_KEY가 없어 스킵합니다")
        // LLM 판단은 같은 입력에도 흔들릴 수 있어 시나리오마다 여러 번 돌려 일관성까지 본다
        val runs = System.getenv("AGENT_EVAL_RUNS")?.toIntOrNull() ?: 5

        val tuning = listOf(
            seedNormalUser(), seedNewButNormalUser(), seedLongTimeHeavyUser(), seedLowSampleUser(), seedQuickButFewUser(),
            seedBurstSubscriber(), seedDormantUser(), seedFastTenSubscriber(), seedNearlyDormantUser(), seedDormantThenBurstUser(),
        )
        val holdOut = holdOutScenarios()

        val tuningResult = evaluate("튜닝용 (도구 개선에 사용한 시나리오)", tuning, runs)
        val holdOutResult = evaluate("검증용 (hold-out, 개선에 쓰지 않음)", holdOut, runs)

        val report = "## 사용자 조사 에이전트 평가\n\n" +
            "모델 판정이 MEDIUM/HIGH면 '의심', LOW/INCONCLUSIVE면 '정상'으로 보고 시나리오의 기대값과 비교했다. " +
            "**성능 보고 수치는 검증용 결과**이고, 튜닝용은 개선 과정 추적용이다.\n\n" +
            holdOutResult.markdown + tuningResult.markdown

        println(report)
        java.io.File("build/reports/agent-eval.md").apply { parentFile.mkdirs() }.writeText(report)
    }
}
