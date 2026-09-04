package com.alphaadopter.core.subscription

import com.alphaadopter.core.domain.subscription.Subscription
import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import com.alphaadopter.core.domain.subscription.SubscriptionType
import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// 신규 키워드 구독 한도를 0으로 낮춰서, 기존 dev 데이터 상태와 무관하게 결정론적으로 검증한다.
@SpringBootTest
@TestPropertySource(properties = ["app.subscription.max-distinct-keywords=0"])
class SubscriptionKeywordCapTest {

    @Autowired
    lateinit var subscriptionController: SubscriptionController

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var subscriptionRepository: SubscriptionRepository

    @Test
    @Transactional
    fun `한도에 도달해도 이미 존재하는 키워드에는 합류할 수 있다`() {
        val existingKeyword = "기존키워드-${System.nanoTime()}"
        val seedUser = userRepository.save(User(email = "seed-${System.nanoTime()}@example.com"))
        subscriptionRepository.save(Subscription(user = seedUser, keyword = existingKeyword, type = SubscriptionType.KEYWORD))

        val response = subscriptionController.create(
            SubscriptionCreateRequest(email = "joiner-${System.nanoTime()}@example.com", keyword = existingKeyword, type = SubscriptionType.KEYWORD),
        )

        assertEquals(HttpStatus.CREATED, response.statusCode)
    }

    @Test
    @Transactional
    fun `한도에 도달하면 완전히 새로운 키워드는 거부된다`() {
        val brandNewKeyword = "신규키워드-${System.nanoTime()}"

        val exception = assertFailsWith<ResponseStatusException> {
            subscriptionController.create(
                SubscriptionCreateRequest(email = "blocked-${System.nanoTime()}@example.com", keyword = brandNewKeyword, type = SubscriptionType.KEYWORD),
            )
        }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.statusCode)
    }
}
