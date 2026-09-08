package com.alphaadopter.core.notification

import com.alphaadopter.core.IntegrationTestBase
import com.alphaadopter.core.auth.AuthPrincipal
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
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationHistoryControllerTest : IntegrationTestBase() {

    @Autowired
    lateinit var historyController: NotificationHistoryController

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var subscriptionRepository: SubscriptionRepository

    @Autowired
    lateinit var newsArticleRepository: NewsArticleRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    private fun newUser(): User =
        userRepository.save(User(email = "history-${System.nanoTime()}@example.com"))

    private fun newSubscription(user: User): Subscription =
        subscriptionRepository.save(
            Subscription(user = user, keyword = "테스트-${System.nanoTime()}", type = SubscriptionType.KEYWORD),
        )

    // createdAt을 명시적으로 넣어 최신순 정렬을 결정론적으로 검증한다.
    private fun saveNotification(subscription: Subscription, createdAt: Instant): Notification {
        val article = newsArticleRepository.save(
            NewsArticle(
                title = "기사-${System.nanoTime()}",
                description = "설명",
                link = "https://example.com/${System.nanoTime()}",
                publishedAt = Instant.now(),
            ),
        )
        val notification = Notification(subscription = subscription, newsArticle = article)
        notification.createdAt = createdAt
        return notificationRepository.save(notification)
    }

    private fun principalOf(user: User) = AuthPrincipal(userId = user.id!!, email = user.email)

    @Test
    @Transactional
    fun `첫 페이지는 최신순으로 limit만큼 주고 남은 게 있으면 hasMore true`() {
        val user = newUser()
        val subscription = newSubscription(user)
        val base = Instant.parse("2026-01-01T00:00:00Z")
        val newest = saveNotification(subscription, base.plusSeconds(30))
        saveNotification(subscription, base.plusSeconds(20))
        saveNotification(subscription, base.plusSeconds(10))

        val page = historyController.history(principalOf(user), cursor = null, limit = 2)

        assertEquals(2, page.items.size)
        assertEquals(newest.id, page.items.first().id)
        assertTrue(page.hasMore)
        assertNotNull(page.nextCursor)
    }

    @Test
    @Transactional
    fun `nextCursor로 이어받으면 남은 알림을 중복 누락 없이 모두 순회한다`() {
        val user = newUser()
        val subscription = newSubscription(user)
        val base = Instant.parse("2026-01-01T00:00:00Z")
        val expectedDesc = (0 until 5)
            .map { saveNotification(subscription, base.plusSeconds((it * 10).toLong())) }
            .map { it.id!! }
            .sortedDescending()

        val collected = mutableListOf<Long>()
        var cursor: String? = null
        var guard = 0
        do {
            val page = historyController.history(principalOf(user), cursor = cursor, limit = 2)
            collected += page.items.map { it.id }
            cursor = page.nextCursor
            check(guard++ < 10) { "페이지네이션이 끝나지 않음 (무한 루프 방지)" }
        } while (cursor != null)

        assertEquals(expectedDesc, collected)
        // 마지막 페이지는 hasMore=false, nextCursor=null
        val lastPage = historyController.history(principalOf(user), cursor = null, limit = 5)
        assertNull(lastPage.nextCursor)
        assertEquals(false, lastPage.hasMore)
    }

    @Test
    @Transactional
    fun `createdAt이 같아도 id 기준으로 안정 정렬되고 커서가 건너뛰지 않는다`() {
        val user = newUser()
        val subscription = newSubscription(user)
        val sameInstant = Instant.parse("2026-02-02T12:00:00Z")
        val expectedDesc = (0 until 4)
            .map { saveNotification(subscription, sameInstant).id!! }
            .sortedDescending()

        val collected = mutableListOf<Long>()
        var cursor: String? = null
        do {
            val page = historyController.history(principalOf(user), cursor = cursor, limit = 2)
            collected += page.items.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)

        assertEquals(expectedDesc, collected)
    }

    @Test
    @Transactional
    fun `다른 유저의 알림은 히스토리에 포함되지 않는다`() {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        val me = newUser()
        val mine = saveNotification(newSubscription(me), base.plusSeconds(10))
        val other = newUser()
        saveNotification(newSubscription(other), base.plusSeconds(20))

        val page = historyController.history(principalOf(me), cursor = null, limit = 20)

        assertEquals(listOf(mine.id), page.items.map { it.id })
    }

    @Test
    @Transactional
    fun `잘못된 커서는 400을 반환한다`() {
        val user = newUser()

        val ex = assertFailsWith<ResponseStatusException> {
            historyController.history(principalOf(user), cursor = "not-a-real-cursor!!", limit = 20)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}
