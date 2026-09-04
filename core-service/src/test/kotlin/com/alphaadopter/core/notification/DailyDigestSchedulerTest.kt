package com.alphaadopter.core.notification

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
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
class DailyDigestSchedulerTest {

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
}
