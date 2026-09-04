package com.alphaadopter.core.pipeline

import com.alphaadopter.core.collector.NewsRawMessage
import com.alphaadopter.core.domain.news.NewsArticle
import com.alphaadopter.core.domain.news.NewsArticleRepository
import com.alphaadopter.core.domain.notification.Notification
import com.alphaadopter.core.domain.notification.NotificationRepository
import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.format.DateTimeFormatter

// news.raw 컨슈머: 원본 저장(MongoDB) -> 정규화(PostgreSQL) -> 구독 매칭 -> news.matched 발행
@Component
class NewsRawConsumer(
    private val rawNewsMongoRepository: RawNewsMongoRepository,
    private val newsArticleRepository: NewsArticleRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val notificationRepository: NotificationRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    @Value("\${app.kafka.topic.news-matched}") private val newsMatchedTopic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${app.kafka.topic.news-raw}"], groupId = "core-service-raw-consumer")
    @Transactional
    fun consume(message: NewsRawMessage) {
        rawNewsMongoRepository.save(
            RawNewsDocument(
                keyword = message.keyword,
                title = message.title,
                description = message.description,
                link = message.link,
                originalLink = message.originalLink,
                pubDate = message.pubDate,
            ),
        )

        if (newsArticleRepository.existsByLink(message.link)) {
            log.debug("이미 처리된 뉴스: {}", message.link)
            return
        }

        val article = newsArticleRepository.save(
            NewsArticle(
                title = message.title,
                description = message.description,
                link = message.link,
                originalLink = message.originalLink,
                publishedAt = parsePubDate(message.pubDate),
            ),
        )

        val matchedSubscriptions = subscriptionRepository.findAll().filter { subscription ->
            message.title.contains(subscription.keyword, ignoreCase = true) ||
                message.description.contains(subscription.keyword, ignoreCase = true)
        }

        matchedSubscriptions.forEach { subscription ->
            val notification = notificationRepository.save(Notification(subscription = subscription, newsArticle = article))
            kafkaTemplate.send(
                newsMatchedTopic,
                article.link,
                NewsMatchedMessage(
                    notificationId = notification.id!!,
                    userId = subscription.user.id!!,
                    subscriptionKeyword = subscription.keyword,
                    title = article.title,
                    link = article.link,
                ),
            )
        }

        log.info("뉴스 처리 완료: {} (매칭 구독 {}건)", message.title, matchedSubscriptions.size)
    }

    // NAVER API pubDate 포맷: "Fri, 04 Sep 2026 15:58:00 +0900" (RFC 1123)
    private fun parsePubDate(pubDate: String): Instant =
        runCatching { Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(pubDate)) }
            .getOrDefault(Instant.now())
}
