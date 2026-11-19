package com.alphaadopter.core.collector

import com.alphaadopter.core.domain.collector.CollectorCheckpoint
import com.alphaadopter.core.domain.collector.CollectorCheckpointRepository
import com.alphaadopter.core.domain.collector.CollectorIncident
import com.alphaadopter.core.domain.collector.CollectorIncidentRepository
import com.alphaadopter.core.domain.collector.CollectorIncidentType
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class KeywordCollectResult(
    val keyword: String,
    val fetchedPages: Int,
    val newArticles: Int,
    val published: Int,
    val gapDetected: Boolean,
    val failed: Boolean,
)

// 키워드 하나를 "마지막 수집 지점 이후의 새 기사 전부"까지 따라가 news.raw로 발행한다.
//
// 예전 수집기는 1분마다 키워드당 기본 정렬(정확도순) 10건만 받아 그대로 발행했다. 그래서 (1) 최신
// 기사가 아니라 검색어와 비슷한 기사를 받았고, (2) 1분 사이 10건 넘게 나오면 나머지는 조용히 빠졌으며,
// (3) 빠졌는지조차 알 방법이 없었다. 지금은 최신순으로 받으면서 키워드별 수집 지점(CollectorCheckpoint)에
// 닿을 때까지 페이지를 넘기고, 페이지 상한 안에 닿지 못하면 누락 가능 구간으로 기록한다.
//
// API 할당량: 평소에는 한 번(최대 100건)이면 수집 지점에 닿으므로 키워드당 주기마다 1회 호출로
// 예전과 같다. 기사가 몰릴 때만 maxPages까지 추가 호출한다.
@Component
class NewsCollector(
    private val naverNewsClient: NaverNewsClient,
    private val checkpointRepository: CollectorCheckpointRepository,
    private val incidentRepository: CollectorIncidentRepository,
    private val kafkaTemplate: KafkaTemplate<String, NewsRawMessage>,
    private val meterRegistry: MeterRegistry,
    @Value("\${app.kafka.topic.news-raw}") private val newsRawTopic: String,
    @Value("\${app.collector.page-size:100}") private val pageSize: Int,
    @Value("\${app.collector.max-pages:3}") private val maxPages: Int,
    @Value("\${app.collector.publish-timeout-ms:10000}") private val publishTimeoutMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun collect(keyword: String): KeywordCollectResult {
        val checkpoint = checkpointRepository.findById(keyword).orElse(null)

        // 최신순으로 받은 기사 중 수집 지점보다 새로운 것만 모은다(최신 → 과거 순서)
        val fresh = mutableListOf<NaverNewsItem>()
        // 수집 지점이 없는 첫 수집은 첫 페이지만 받고 끝낸다 — 과거 기사를 끝없이 거슬러 올라갈 이유가 없다
        var reachedCheckpoint = checkpoint == null
        var exhausted = false
        var pages = 0

        try {
            while (pages < maxPages) {
                val start = pages * pageSize + 1
                if (start > MAX_START) break
                val response = naverNewsClient.searchNews(keyword, display = pageSize, start = start)
                pages++
                meterRegistry.counter("news.collector.api_calls").increment()

                for (item in response.items) {
                    if (checkpoint != null && isAtOrBefore(item, checkpoint)) {
                        reachedCheckpoint = true
                        break
                    }
                    fresh += item
                }
                if (reachedCheckpoint) break
                if (response.items.size < pageSize) {
                    // 검색 결과 자체가 끝났다 — 놓친 기사 없이 전부 받은 것
                    exhausted = true
                    break
                }
            }
        } catch (e: Exception) {
            log.warn("'{}' 키워드 수집 실패(재시도 소진): {}", keyword, e.message)
            recordIncident(keyword, CollectorIncidentType.FETCH_FAILED, "뉴스 검색 API 호출 실패: ${e.message}")
            return KeywordCollectResult(keyword, pages, 0, 0, gapDetected = false, failed = true)
        }

        val gapDetected = checkpoint != null && !reachedCheckpoint && !exhausted
        if (gapDetected) {
            recordIncident(
                keyword,
                CollectorIncidentType.GAP,
                "최신순 ${pages * pageSize}건을 넘겨도 마지막 수집 지점(${checkpoint!!.lastPublishedAt})에 닿지 못해 " +
                    "그 사이 기사 일부가 누락됐을 수 있음",
            )
        }

        // 오래된 기사부터 발행해, 하류에서도 실제 발생 순서에 가깝게 처리되도록 한다
        val collectedAt = Instant.now()
        val messages = fresh.asReversed().map { toMessage(keyword, it, collectedAt) }
        val futures = messages.map { kafkaTemplate.send(newsRawTopic, it.link, it) }
        val failedPublishes = futures.count { future ->
            runCatching { future.get(publishTimeoutMs, TimeUnit.MILLISECONDS) }.isFailure
        }
        val published = messages.size - failedPublishes
        meterRegistry.counter("news.collector.articles", "result", "published").increment(published.toDouble())

        if (failedPublishes > 0) {
            // 수집 지점을 전진시키지 않는다 — 다음 주기에 같은 구간을 다시 받고, 이미 발행된 기사는
            // 하류(링크 유니크 저장)에서 걸러진다. 유실보다 중복이 낫다.
            recordIncident(
                keyword,
                CollectorIncidentType.PUBLISH_FAILED,
                "새 기사 ${messages.size}건 중 ${failedPublishes}건 Kafka 발행 실패, 다음 주기에 같은 구간 재수집",
            )
        } else {
            advanceCheckpoint(keyword, checkpoint, fresh)
        }

        return KeywordCollectResult(keyword, pages, fresh.size, published, gapDetected, failed = failedPublishes > 0)
    }

    private fun isAtOrBefore(item: NaverNewsItem, checkpoint: CollectorCheckpoint): Boolean {
        if (item.link == checkpoint.lastLink) return true
        val publishedAt = parsePubDate(item.pubDate) ?: return false
        return publishedAt.isBefore(checkpoint.lastPublishedAt)
    }

    // fresh는 최신순이므로, 날짜를 해석할 수 있는 첫 기사가 새 수집 지점이 된다
    private fun advanceCheckpoint(keyword: String, current: CollectorCheckpoint?, fresh: List<NaverNewsItem>) {
        val newest = fresh.firstNotNullOfOrNull { item -> parsePubDate(item.pubDate)?.let { item to it } } ?: return
        val (item, publishedAt) = newest
        val checkpoint = current ?: CollectorCheckpoint(keyword = keyword, lastPublishedAt = publishedAt, lastLink = item.link)
        checkpoint.lastPublishedAt = publishedAt
        checkpoint.lastLink = item.link
        checkpoint.updatedAt = Instant.now()
        checkpointRepository.save(checkpoint)
    }

    private fun recordIncident(keyword: String, type: CollectorIncidentType, detail: String) {
        meterRegistry.counter("news.collector.incidents", "type", type.name).increment()
        runCatching { incidentRepository.save(CollectorIncident(keyword, type, detail.take(1000))) }
            .onFailure { e -> log.warn("수집 이상 기록 저장 실패({}, {}): {}", keyword, type, e.message) }
    }

    // 응답 title/description에는 하이라이트용 <b> 태그와 HTML 엔티티(&quot; 등)가 섞여 있다
    private fun toMessage(keyword: String, item: NaverNewsItem, collectedAt: Instant) = NewsRawMessage(
        keyword = keyword,
        title = HtmlUtils.htmlUnescape(stripHighlightTags(item.title)),
        description = HtmlUtils.htmlUnescape(stripHighlightTags(item.description)),
        link = item.link,
        originalLink = item.originallink,
        pubDate = item.pubDate,
        collectedAt = collectedAt,
    )

    companion object {
        // NAVER 뉴스 검색 API의 start 파라미터 상한
        private const val MAX_START = 1000

        // NAVER API pubDate 포맷: "Fri, 04 Sep 2026 15:58:00 +0900" (RFC 1123)
        fun parsePubDate(pubDate: String): Instant? =
            runCatching { Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(pubDate)) }.getOrNull()
    }
}
