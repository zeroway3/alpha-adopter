package com.alphaadopter.core.notification

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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DailyDigestSchedulerTest : IntegrationTestBase() {

    @Autowired
    lateinit var dailyDigestScheduler: DailyDigestScheduler

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var subscriptionRepository: SubscriptionRepository

    @Autowired
    lateinit var newsArticleRepository: NewsArticleRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @Test
    @Transactional
    fun `비회원의 MATCHED 알림은 다이제스트 발송 후 SENT로 바뀐다`() {
        val nonMember = userRepository.save(User(email = "non-member-${System.nanoTime()}@example.com", isMember = false))
        val subscription = subscriptionRepository.save(
            Subscription(user = nonMember, keyword = "삼성전자", type = SubscriptionType.KEYWORD),
        )
        val article = newsArticleRepository.save(
            NewsArticle(
                title = "삼성전자 실적 발표",
                description = "설명",
                link = "https://example.com/${System.nanoTime()}",
                publishedAt = Instant.now(),
            ),
        )
        val notification = notificationRepository.save(Notification(subscription = subscription, newsArticle = article))

        dailyDigestScheduler.sendDigest()

        val updated = notificationRepository.findById(notification.id!!).orElseThrow()
        assertEquals(NotificationStatus.SENT, updated.status)
        assertNotNull(updated.sentAt)
    }

    @Test
    @Transactional
    fun `회원의 MATCHED 알림은 다이제스트 대상이 아니다`() {
        val member = userRepository.save(User(email = "member-${System.nanoTime()}@example.com", isMember = true))
        val subscription = subscriptionRepository.save(
            Subscription(user = member, keyword = "카카오", type = SubscriptionType.KEYWORD),
        )
        val article = newsArticleRepository.save(
            NewsArticle(
                title = "카카오 신규 서비스 출시",
                description = "설명",
                link = "https://example.com/${System.nanoTime()}",
                publishedAt = Instant.now(),
            ),
        )
        val notification = notificationRepository.save(Notification(subscription = subscription, newsArticle = article))

        dailyDigestScheduler.sendDigest()

        val updated = notificationRepository.findById(notification.id!!).orElseThrow()
        assertEquals(NotificationStatus.MATCHED, updated.status)
    }

    @Test
    @Transactional
    fun `참여도 점수가 높은 알림이 다이제스트 상단에 먼저 온다`() {
        val nonMember = userRepository.save(User(email = "digest-sort-${System.nanoTime()}@example.com", isMember = false))
        val subscription = subscriptionRepository.save(
            Subscription(user = nonMember, keyword = "테스트키워드", type = SubscriptionType.KEYWORD),
        )
        val lowScoreArticle = newsArticleRepository.save(
            NewsArticle(
                title = "낮은참여도기사-${System.nanoTime()}",
                description = "설명",
                link = "https://example.com/${System.nanoTime()}",
                publishedAt = Instant.now(),
            ),
        )
        val highScoreArticle = newsArticleRepository.save(
            NewsArticle(
                title = "높은참여도기사-${System.nanoTime()}",
                description = "설명",
                link = "https://example.com/${System.nanoTime()}",
                publishedAt = Instant.now(),
            ),
        )
        notificationRepository.save(
            Notification(subscription = subscription, newsArticle = lowScoreArticle).apply { personalizationScore = 0.1 },
        )
        notificationRepository.save(
            Notification(subscription = subscription, newsArticle = highScoreArticle).apply { personalizationScore = 0.9 },
        )

        dailyDigestScheduler.sendDigest()

        val mailBody = latestMailBodyTo(nonMember.email)
        val highIndex = mailBody.indexOf(highScoreArticle.title)
        val lowIndex = mailBody.indexOf(lowScoreArticle.title)
        assertTrue(highIndex >= 0 && lowIndex >= 0, "발송된 메일에 두 기사 제목이 모두 있어야 한다")
        assertTrue(highIndex < lowIndex, "참여도가 높은 기사가 먼저 나와야 한다")
    }

    // Mailpit HTTP API로 실제 발송된 메일 원문을 가져온다 (SMTP 발송 여부뿐 아니라 본문 순서까지 검증하기 위함)
    private fun latestMailBodyTo(email: String): String {
        val host = mailpit.host
        val port = mailpit.getMappedPort(8025)
        val client = HttpClient.newHttpClient()
        val search = client.send(
            HttpRequest.newBuilder(URI.create("http://$host:$port/api/v1/search?query=to:$email")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val id = Regex("\"ID\"\\s*:\\s*\"([^\"]+)\"").find(search.body())?.groupValues?.get(1)
            ?: error("$email 앞으로 발송된 메일을 찾을 수 없습니다: ${search.body()}")
        val message = client.send(
            HttpRequest.newBuilder(URI.create("http://$host:$port/api/v1/message/$id")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return message.body()
    }
}
