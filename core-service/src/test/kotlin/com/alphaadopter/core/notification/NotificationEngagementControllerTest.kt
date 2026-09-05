package com.alphaadopter.core.notification

import com.alphaadopter.core.IntegrationTestBase
import com.alphaadopter.core.domain.news.NewsArticle
import com.alphaadopter.core.domain.news.NewsArticleRepository
import com.alphaadopter.core.domain.notification.Notification
import com.alphaadopter.core.domain.notification.NotificationRepository
import com.alphaadopter.core.domain.subscription.Subscription
import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import com.alphaadopter.core.domain.subscription.SubscriptionType
import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NotificationEngagementControllerTest : IntegrationTestBase() {

    @Autowired
    lateinit var engagementController: NotificationEngagementController

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var subscriptionRepository: SubscriptionRepository

    @Autowired
    lateinit var newsArticleRepository: NewsArticleRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    private fun createNotification(link: String): Notification {
        val user = userRepository.save(User(email = "engagement-${System.nanoTime()}@example.com"))
        val subscription = subscriptionRepository.save(
            Subscription(user = user, keyword = "테스트-${System.nanoTime()}", type = SubscriptionType.KEYWORD),
        )
        val article = newsArticleRepository.save(
            NewsArticle(
                title = "테스트 기사",
                description = "설명",
                link = link,
                publishedAt = Instant.now(),
            ),
        )
        return notificationRepository.save(Notification(subscription = subscription, newsArticle = article))
    }

    @Test
    @Transactional
    fun `읽음 처리하면 readAt이 기록된다`() {
        val notification = createNotification("https://example.com/read-test")

        engagementController.markRead(notification.id!!)

        val updated = notificationRepository.findById(notification.id!!).orElseThrow()
        assertNotNull(updated.readAt)
        assertNull(updated.clickedAt)
    }

    @Test
    @Transactional
    fun `클릭하면 원문 링크로 리다이렉트되고 readAt과 clickedAt이 함께 기록된다`() {
        val notification = createNotification("https://example.com/click-test")

        val response = engagementController.trackClick(notification.id!!)

        assertEquals(HttpStatus.FOUND, response.statusCode)
        assertEquals("https://example.com/click-test", response.headers.location.toString())

        val updated = notificationRepository.findById(notification.id!!).orElseThrow()
        assertNotNull(updated.clickedAt)
        assertNotNull(updated.readAt)
    }
}
