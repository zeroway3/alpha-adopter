package com.alphaadopter.core.pipeline

import com.alphaadopter.core.ai.RelevanceResult
import com.alphaadopter.core.collector.NewsRawMessage
import com.alphaadopter.core.domain.news.NewsArticle
import com.alphaadopter.core.domain.news.NewsArticleRepository
import com.alphaadopter.core.domain.notification.Notification
import com.alphaadopter.core.domain.notification.NotificationRepository
import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import com.alphaadopter.core.subscription.CachedSubscription
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class EvaluatedMatch(val subscription: CachedSubscription, val relevance: RelevanceResult)

// NewsRawConsumer가 (DB 트랜잭션 없이) AI 관련도 판단까지 전부 끝낸 결과를 받아서, 순수 DB
// 쓰기 + Kafka 발행만 담당한다. 예전엔 이 로직이 AI 호출(Claude API, 느려질 수 있음)과 같은
// @Transactional 스코프 안에 있어서, API가 느려지거나 레이트리밋에 걸리면 그동안 DB 커넥션
// 풀 슬롯을 계속 붙잡고 있는 문제가 있었다 — 별도 빈으로 분리해야 이 메서드에만 독립적인
// @Transactional 프록시가 적용된다(같은 클래스 내부 self-invocation으로는 AOP가 안 걸림).
@Component
class NewsMatchPersister(
    private val newsArticleRepository: NewsArticleRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val notificationRepository: NotificationRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    @Value("\${app.kafka.topic.news-matched}") private val newsMatchedTopic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun persist(message: NewsRawMessage, publishedAt: Instant, evaluatedMatches: List<EvaluatedMatch>): Int {
        val article = newsArticleRepository.save(
            NewsArticle(
                title = message.title,
                description = message.description,
                link = message.link,
                originalLink = message.originalLink,
                publishedAt = publishedAt,
            ),
        )

        var relevantCount = 0
        evaluatedMatches.forEach { match ->
            if (!match.relevance.relevant) {
                log.debug(
                    "AI 판단으로 노이즈 필터링: 키워드={}, 제목={}, 점수={}",
                    match.subscription.keyword,
                    message.title,
                    match.relevance.score,
                )
                return@forEach
            }
            relevantCount++

            val notification = notificationRepository.save(
                Notification(
                    // 캐시에는 id만 있으므로, 실제 row를 다시 조회하지 않고 프록시 참조로 연관관계만 건다
                    subscription = subscriptionRepository.getReferenceById(match.subscription.id),
                    newsArticle = article,
                ).apply { relevanceScore = match.relevance.score },
            )
            kafkaTemplate.send(
                newsMatchedTopic,
                article.link,
                NewsMatchedMessage(
                    notificationId = notification.id!!,
                    userId = match.subscription.userId,
                    subscriptionKeyword = match.subscription.keyword,
                    title = article.title,
                    link = article.link,
                ),
            )
        }
        return relevantCount
    }
}
