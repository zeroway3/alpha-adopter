package com.alphaadopter.core.pipeline

import com.alphaadopter.core.IntegrationTestBase
import com.alphaadopter.core.collector.NewsRawMessage
import com.alphaadopter.core.domain.news.NewsArticleRepository
import com.alphaadopter.core.domain.notification.NotificationRepository
import com.alphaadopter.core.domain.notification.NotificationStatus
import com.alphaadopter.core.domain.subscription.Subscription
import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import com.alphaadopter.core.domain.subscription.SubscriptionType
import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.KafkaTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 로드맵 2단계(뉴스 수집 파이프라인)의 핵심 흐름 — news.raw 발행 -> MongoDB 원본 저장 ->
// 구독 매칭 -> Notification(MATCHED) 생성 -- 을 지금까지 유닛테스트가 하나도 커버하지
// 않고 있었다. 실제 Kafka/Postgres/MongoDB 컨테이너를 띄워 엔드투엔드로 검증한다.
class NewsMatchingPipelineIntegrationTest : IntegrationTestBase() {

    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, Any>

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var subscriptionRepository: SubscriptionRepository

    @Autowired
    lateinit var newsArticleRepository: NewsArticleRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @Autowired
    lateinit var rawNewsMongoRepository: RawNewsMongoRepository

    @Test
    fun `구독 키워드가 포함된 뉴스가 들어오면 원본 저장과 매칭 알림 생성까지 끝난다`() {
        val keyword = "테스트키워드${System.nanoTime()}"
        val user = userRepository.save(User(email = "pipeline-${System.nanoTime()}@example.com", isMember = true))
        subscriptionRepository.save(Subscription(user = user, keyword = keyword, type = SubscriptionType.KEYWORD))

        val link = "https://news.example.com/${System.nanoTime()}"
        val message = NewsRawMessage(
            keyword = keyword,
            title = "$keyword 관련 속보",
            description = "$keyword 에 대한 상세 내용입니다.",
            link = link,
            originalLink = link,
            pubDate = "Fri, 04 Sep 2026 15:58:00 +0900",
        )

        kafkaTemplate.send("news.raw", link, message)

        // NewsArticle/RawNewsDocument는 지연 로딩 연관관계가 아닌 순수 컬럼이라 세션 밖에서도
        // 안전하게 읽을 수 있지만, Notification.subscription/newsArticle은 지연 로딩이라
        // (세션이 닫힌 뒤 findAll()+필터링 방식으로 건드리면 LazyInitializationException) DB에서
        // 바로 조인 필터링하는 전용 쿼리로 찾는다.
        val article = awaitNotNull { newsArticleRepository.findAll().find { it.link == link } }
        assertEquals("$keyword 관련 속보", article.title)

        val rawSaved = awaitTrue { rawNewsMongoRepository.findAll().any { it.link == link } }
        assertTrue(rawSaved, "원본 뉴스가 MongoDB에 저장되어야 한다")

        val notification = awaitNotNull {
            notificationRepository.findFirstBySubscriptionKeywordAndNewsArticleLink(keyword, link)
        }
        assertEquals(NotificationStatus.MATCHED, notification.status)
    }

    @Test
    fun `키워드와 무관한 뉴스는 매칭 알림이 생기지 않는다`() {
        val keyword = "무관테스트${System.nanoTime()}"
        val user = userRepository.save(User(email = "nomatch-${System.nanoTime()}@example.com", isMember = true))
        subscriptionRepository.save(Subscription(user = user, keyword = keyword, type = SubscriptionType.KEYWORD))

        val link = "https://news.example.com/nomatch-${System.nanoTime()}"
        val message = NewsRawMessage(
            keyword = "전혀다른검색어",
            title = "전혀 상관없는 제목",
            description = "전혀 상관없는 본문",
            link = link,
            originalLink = link,
            pubDate = "Fri, 04 Sep 2026 15:58:00 +0900",
        )

        kafkaTemplate.send("news.raw", link, message)

        // 원본 저장까지는 항상 일어나므로, 그걸 기준으로 컨슈머가 처리를 끝냈다고 판단한다
        awaitTrue { rawNewsMongoRepository.findAll().any { it.link == link } }

        val matched = notificationRepository.findFirstBySubscriptionKeywordAndNewsArticleLink(keyword, link)
        assertTrue(matched == null, "무관한 키워드로는 알림이 생기면 안 된다")
    }

    private fun <T> awaitNotNull(timeoutMs: Long = 30_000, block: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            block()?.let { return it }
            Thread.sleep(200)
        }
        return assertNotNull(block(), "제한 시간 내에 조건을 만족하지 못했습니다")
    }

    private fun awaitTrue(timeoutMs: Long = 30_000, block: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (block()) return true
            Thread.sleep(200)
        }
        return block()
    }
}
