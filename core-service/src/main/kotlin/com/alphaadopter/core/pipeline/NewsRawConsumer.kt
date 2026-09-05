package com.alphaadopter.core.pipeline

import com.alphaadopter.core.ai.RelevanceScorer
import com.alphaadopter.core.collector.NewsRawMessage
import com.alphaadopter.core.domain.news.NewsArticleRepository
import com.alphaadopter.core.subscription.SubscriptionCache
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.format.DateTimeFormatter

// news.raw 컨슈머: 원본 저장(MongoDB) -> 정규화(PostgreSQL) -> 구독 매칭(문자열 포함 + AI 관련도) -> news.matched 발행
//
// DB 쓰기(트랜잭션)와 AI 관련도 판단(외부 Claude API 호출)을 의도적으로 분리했다 — AI 호출은
// 여기서 트랜잭션 없이 끝내고, 실제 저장/발행은 NewsMatchPersister의 짧은 트랜잭션 안에서만
// 일어난다. 예전엔 전체가 하나의 @Transactional이라 API가 느려지면 DB 커넥션을 계속 붙잡고
// 있었다. 구독 목록도 매 메시지마다 전체 테이블을 읽는 대신 SubscriptionCache를 사용한다.
@Component
class NewsRawConsumer(
    private val rawNewsMongoRepository: RawNewsMongoRepository,
    private val newsArticleRepository: NewsArticleRepository,
    private val subscriptionCache: SubscriptionCache,
    private val relevanceScorer: RelevanceScorer,
    private val newsMatchPersister: NewsMatchPersister,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${app.kafka.topic.news-raw}"], groupId = "core-service-raw-consumer")
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

        val substringMatched = subscriptionCache.all().filter { subscription ->
            message.title.contains(subscription.keyword, ignoreCase = true) ||
                message.description.contains(subscription.keyword, ignoreCase = true)
        }

        // 문자열 포함만으로는 "스치듯 언급된" 무관한 기사도 매칭되는 노이즈가 있어, 그 위에 AI
        // 관련도 판단을 2차 필터로 적용한다 (docs/phase6-ai-relevance-filtering.md). DB
        // 트랜잭션을 열기 전에 모든 AI 호출을 끝내둔다.
        val evaluatedMatches = substringMatched.map { subscription ->
            EvaluatedMatch(subscription, relevanceScorer.evaluate(subscription.keyword, message.title, message.description))
        }

        val relevantCount = newsMatchPersister.persist(message, parsePubDate(message.pubDate), evaluatedMatches)

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
