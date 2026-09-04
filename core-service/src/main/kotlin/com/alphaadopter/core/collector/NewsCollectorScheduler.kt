package com.alphaadopter.core.collector

import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 구독 중인 키워드들을 주기적으로 조회해 NAVER 뉴스 API로 수집하고, news.raw 토픽으로 발행
@Component
class NewsCollectorScheduler(
    private val subscriptionRepository: SubscriptionRepository,
    private val naverNewsClient: NaverNewsClient,
    private val kafkaTemplate: KafkaTemplate<String, NewsRawMessage>,
    @Value("\${app.kafka.topic.news-raw}") private val newsRawTopic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.collector.fixed-delay-ms:60000}")
    fun collect() {
        val keywords = subscriptionRepository.findAll().map { it.keyword }.distinct()
        if (keywords.isEmpty()) {
            log.info("수집할 구독 키워드가 없습니다.")
            return
        }

        keywords.forEach { keyword ->
            runCatching {
                val items = naverNewsClient.searchNews(keyword)
                items.forEach { item ->
                    val message = NewsRawMessage(
                        keyword = keyword,
                        title = stripHighlightTags(item.title),
                        description = stripHighlightTags(item.description),
                        link = item.link,
                        originalLink = item.originallink,
                        pubDate = item.pubDate,
                    )
                    kafkaTemplate.send(newsRawTopic, message.link, message)
                }
                log.info("'{}' 키워드 뉴스 {}건 수집", keyword, items.size)
            }.onFailure { e ->
                log.warn("'{}' 키워드 뉴스 수집 실패: {}", keyword, e.message)
            }
        }
    }
}
