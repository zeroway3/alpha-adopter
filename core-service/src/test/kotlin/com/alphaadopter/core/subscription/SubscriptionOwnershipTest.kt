package com.alphaadopter.core.subscription

import com.alphaadopter.core.IntegrationTestBase
import com.alphaadopter.core.auth.AuthPrincipal
import com.alphaadopter.core.domain.subscription.SubscriptionType
import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// JWT 전환 이후 SubscriptionController가 email 파라미터 대신 인증 주체(userId)로
// 소유권을 판단하게 됐는데, 그 소유권 검증(다른 사람 구독을 못 지운다) 자체를 검증하는
// 테스트가 없었다.
class SubscriptionOwnershipTest : IntegrationTestBase() {

    @Autowired
    lateinit var subscriptionController: SubscriptionController

    @Autowired
    lateinit var userRepository: UserRepository

    private fun principalFor(email: String): AuthPrincipal {
        val user = userRepository.save(User(email = email, isMember = true))
        return AuthPrincipal(userId = user.id!!, email = user.email)
    }

    @Test
    @Transactional
    fun `다른 사용자의 구독은 삭제할 수 없다`() {
        val owner = principalFor("owner-${System.nanoTime()}@example.com")
        val intruder = principalFor("intruder-${System.nanoTime()}@example.com")

        val created = subscriptionController.create(
            SubscriptionCreateRequest(keyword = "삼성전자${System.nanoTime()}", type = SubscriptionType.KEYWORD),
            owner,
        )
        val subscriptionId = created.body!!.id

        val ex = assertFailsWith<ResponseStatusException> {
            subscriptionController.delete(subscriptionId, intruder)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    @Transactional
    fun `내 구독 목록에는 다른 사용자의 구독이 섞이지 않는다`() {
        val userA = principalFor("usera-${System.nanoTime()}@example.com")
        val userB = principalFor("userb-${System.nanoTime()}@example.com")

        subscriptionController.create(SubscriptionCreateRequest(keyword = "A키워드${System.nanoTime()}", type = SubscriptionType.KEYWORD), userA)
        subscriptionController.create(SubscriptionCreateRequest(keyword = "B키워드${System.nanoTime()}", type = SubscriptionType.KEYWORD), userB)

        val listForA = subscriptionController.list(userA)
        assertTrue(listForA.isNotEmpty())
        assertTrue(listForA.all { it.keyword.startsWith("A키워드") })
    }

    @Test
    @Transactional
    fun `본인 구독은 정상적으로 삭제된다`() {
        val owner = principalFor("selfdelete-${System.nanoTime()}@example.com")
        val created = subscriptionController.create(
            SubscriptionCreateRequest(keyword = "삭제될키워드${System.nanoTime()}", type = SubscriptionType.KEYWORD),
            owner,
        )

        subscriptionController.delete(created.body!!.id, owner)

        assertTrue(subscriptionController.list(owner).none { it.id == created.body!!.id })
    }

    @Autowired
    lateinit var subscriptionRepository: com.alphaadopter.core.domain.subscription.SubscriptionRepository

    @Autowired
    lateinit var newsArticleRepository: com.alphaadopter.core.domain.news.NewsArticleRepository

    @Autowired
    lateinit var notificationRepository: com.alphaadopter.core.domain.notification.NotificationRepository

    @jakarta.persistence.PersistenceContext
    lateinit var entityManager: jakarta.persistence.EntityManager

    @Test
    @Transactional
    fun `알림을 받은 구독도 해지할 수 있고 그 구독의 알림은 함께 지워진다`() {
        // 예전엔 알림이 한 건이라도 있는 구독을 지우면 FK 위반으로 500이 났다(AWS 실측 중 발견)
        val owner = principalFor("cascade-${System.nanoTime()}@example.com")
        val created = subscriptionController.create(
            SubscriptionCreateRequest(keyword = "알림받은키워드${System.nanoTime()}", type = SubscriptionType.KEYWORD),
            owner,
        )
        val subscription = subscriptionRepository.findById(created.body!!.id).get()
        val article = newsArticleRepository.save(
            com.alphaadopter.core.domain.news.NewsArticle(
                title = "기사", description = "설명", link = "https://example.com/${System.nanoTime()}", publishedAt = java.time.Instant.now(),
            ),
        )
        val notification = notificationRepository.save(
            com.alphaadopter.core.domain.notification.Notification(subscription = subscription, newsArticle = article),
        )
        // 실제 해지 요청처럼 알림 엔티티를 들고 있지 않은 새 영속성 컨텍스트에서 지우도록 비운다
        entityManager.flush()
        entityManager.clear()

        subscriptionController.delete(subscription.id!!, owner)
        subscriptionRepository.flush() // 실제 DELETE SQL을 실행시켜 FK 동작까지 확인

        assertTrue(subscriptionController.list(owner).none { it.id == subscription.id })
        assertEquals(0L, notificationRepository.countByStatusAndSubscriptionId(notification.status, subscription.id!!))
    }
}
