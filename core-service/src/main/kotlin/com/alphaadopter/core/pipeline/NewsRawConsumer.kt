package com.alphaadopter.core.pipeline

import com.alphaadopter.core.ai.RelevanceScorer
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

// news.raw 컨슈머: 원본 저장(MongoDB) -> 정규화(PostgreSQL) -> 구독 매칭(문자열 포함 + AI 관련도) -> news.matched 발행
@Component
class NewsRawConsumer(
    private val rawNewsMongoRepository: RawNewsMongoRepository,
    private val newsArticleRepository: NewsArticleRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val notificationRepository: NotificationRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val relevanceScorer: RelevanceScorer,
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

        val substringMatched = subscriptionRepository.findAll().filter { subscription ->
            message.title.contains(subscription.keyword, ignoreCase = true) ||
                message.description.contains(subscription.keyword, ignoreCase = true)
        }

        var relevantCount = 0
        substringMatched.forEach { subscription ->
            // 문자열 포함만으로는 "스치듯 언급된" 무관한 기사도 매칭되는 노이즈가 있어,
            // 그 위에 AI 관련도 판단을 2차 필터로 적용한다 (docs/phase6-ai-relevance-filtering.md)
            val relevance = relevanceScorer.evaluate(subscription.keyword, message.title, message.description)
            if (!relevance.relevant) {
                log.debug(
                    "AI 판단으로 노이즈 필터링: 키워드={}, 제목={}, 점수={}",
                    subscription.keyword,
                    message.title,
                    relevance.score,
                )
                return@forEach
            }
            relevantCount++

            val notification = notificationRepository.save(
                Notification(subscription = subscription, newsArticle = article).apply {
                    relevanceScore = relevance.score
                },
            )
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

        log.info(
            "뉴스 처리 완료: {} (문자열 매칭 {}건 중 AI 통과 {}건)",
            message.title,
            substringMatched.size,
            relevantCount,
        )
    }

    // NAVER API pubDate 포맷: "Fri, 04 Sep 2026 15:58:00 +0900" (RFC 1123)
    private fun parsePubDate(pubDate: String): Instant =
        runCatching { Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(pubDate)) }
            .getOrDefault(Instant.now())
}
