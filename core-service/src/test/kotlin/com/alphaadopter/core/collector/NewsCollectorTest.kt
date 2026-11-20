package com.alphaadopter.core.collector

import com.alphaadopter.core.domain.collector.CollectorCheckpoint
import com.alphaadopter.core.domain.collector.CollectorCheckpointRepository
import com.alphaadopter.core.domain.collector.CollectorIncident
import com.alphaadopter.core.domain.collector.CollectorIncidentRepository
import com.alphaadopter.core.domain.collector.CollectorIncidentType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 수집 지점까지 페이지를 따라가는 로직, 누락 감지, 실패 시 수집 지점을 전진시키지 않는 동작을
// 실제 API·Kafka·DB 없이 검증한다. 페이지 크기를 3으로 줄여 경계 조건을 작게 만든다.
class NewsCollectorTest {

    private val pageSize = 3
    private val maxPages = 3
    private val base = Instant.parse("2026-11-19T00:00:00Z")

    private val naverNewsClient: NaverNewsClient = Mockito.mock(NaverNewsClient::class.java)
    private val checkpointRepository: CollectorCheckpointRepository = Mockito.mock(CollectorCheckpointRepository::class.java)
    private val incidentRepository: CollectorIncidentRepository = Mockito.mock(CollectorIncidentRepository::class.java)

    @Suppress("UNCHECKED_CAST")
    private val kafkaTemplate: KafkaTemplate<String, NewsRawMessage> =
        Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, NewsRawMessage>

    private val published = mutableListOf<NewsRawMessage>()
    private val savedCheckpoints = mutableListOf<Pair<Instant, String>>()
    private val incidents = mutableListOf<CollectorIncident>()

    private val collector = NewsCollector(
        naverNewsClient,
        checkpointRepository,
        incidentRepository,
        kafkaTemplate,
        SimpleMeterRegistry(),
        newsRawTopic = "news.raw",
        pageSize = pageSize,
        maxPages = maxPages,
        publishTimeoutMs = 1000,
        initialBackfillMinutes = 60,
        // 기사 시각을 base 기준 "몇 분 전"으로 만들므로 시계도 base에 고정한다
        clock = java.time.Clock.fixed(base, java.time.ZoneOffset.UTC),
    )

    init {
        Mockito.`when`(kafkaTemplate.send(anyString(), anyString(), Mockito.any(NewsRawMessage::class.java))).thenAnswer { invocation ->
            published += invocation.getArgument<NewsRawMessage>(2)
            CompletableFuture.completedFuture(Mockito.mock(SendResult::class.java))
        }
        Mockito.`when`(checkpointRepository.save(Mockito.any(CollectorCheckpoint::class.java))).thenAnswer { invocation ->
            val checkpoint = invocation.getArgument<CollectorCheckpoint>(0)
            savedCheckpoints += checkpoint.lastPublishedAt to checkpoint.lastLink
            checkpoint
        }
        Mockito.`when`(incidentRepository.save(Mockito.any(CollectorIncident::class.java))).thenAnswer { invocation ->
            invocation.getArgument<CollectorIncident>(0).also { incidents += it }
        }
        Mockito.`when`(checkpointRepository.findById(anyString())).thenReturn(Optional.empty())
    }

    // minutesAgo가 작을수록 최신 기사. 응답은 API처럼 최신순으로 만든다.
    private fun item(minutesAgo: Long, title: String = "기사$minutesAgo") = NaverNewsItem(
        title = title,
        link = "https://n.news.naver.com/$minutesAgo",
        originallink = "https://origin/$minutesAgo",
        description = "요약",
        pubDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(base.minusSeconds(minutesAgo * 60).atOffset(ZoneOffset.ofHours(9))),
    )

    private fun page(vararg minutesAgo: Long) = NaverNewsResponse(items = minutesAgo.map { item(it) })

    private fun givenPage(start: Int, response: NaverNewsResponse) {
        Mockito.`when`(naverNewsClient.searchNews("삼성전자", pageSize, start)).thenReturn(response)
    }

    private fun givenCheckpointAt(minutesAgo: Long) {
        val checkpoint = CollectorCheckpoint(
            keyword = "삼성전자",
            lastPublishedAt = base.minusSeconds(minutesAgo * 60),
            lastLink = "https://n.news.naver.com/$minutesAgo",
        )
        Mockito.`when`(checkpointRepository.findById("삼성전자")).thenReturn(Optional.of(checkpoint))
    }

    @Test
    fun `첫 수집은 첫 페이지만 받고 오래된 기사부터 발행한 뒤 가장 최신 기사로 수집 지점을 만든다`() {
        givenPage(1, page(1, 2, 3))

        val result = collector.collect("삼성전자")

        assertEquals(listOf("기사3", "기사2", "기사1"), published.map { it.title })
        assertEquals("https://n.news.naver.com/1", savedCheckpoints.single().second)
        assertEquals(1, result.fetchedPages)
        assertFalse(result.gapDetected)
        Mockito.verify(naverNewsClient, Mockito.times(1)).searchNews(anyString(), anyInt(), anyInt())
    }

    @Test
    fun `첫 수집은 최근 1시간 안의 기사만 알림으로 보내고 수집 지점은 가장 최신 기사로 잡는다`() {
        // 구독 직후 몇 시간 전 기사가 한꺼번에 쏟아지지 않아야 한다(AWS 실측에서 약 1,300건이 몰린 문제)
        givenPage(1, page(10, 90, 300))

        collector.collect("삼성전자")

        assertEquals(listOf("기사10"), published.map { it.title })
        assertEquals("https://n.news.naver.com/10", savedCheckpoints.single().second)
    }

    @Test
    fun `수집 지점 이후의 새 기사만 발행한다`() {
        givenCheckpointAt(3)
        givenPage(1, page(1, 2, 3))

        val result = collector.collect("삼성전자")

        assertEquals(listOf("기사2", "기사1"), published.map { it.title })
        assertEquals(2, result.newArticles)
        assertTrue(incidents.isEmpty())
    }

    @Test
    fun `한 페이지 안에 수집 지점이 없으면 다음 페이지까지 따라가 빠짐없이 받는다`() {
        givenCheckpointAt(5)
        givenPage(1, page(1, 2, 3))
        givenPage(4, page(4, 5, 6))

        val result = collector.collect("삼성전자")

        assertEquals(listOf("기사4", "기사3", "기사2", "기사1"), published.map { it.title })
        assertEquals(2, result.fetchedPages)
        assertFalse(result.gapDetected)
    }

    @Test
    fun `페이지 상한까지 넘겨도 수집 지점에 닿지 못하면 누락 의심으로 기록한다`() {
        givenCheckpointAt(100)
        givenPage(1, page(1, 2, 3))
        givenPage(4, page(4, 5, 6))
        givenPage(7, page(7, 8, 9))

        val result = collector.collect("삼성전자")

        assertTrue(result.gapDetected)
        assertEquals(CollectorIncidentType.GAP, incidents.single().type)
        assertEquals(9, published.size)
        // 받은 구간은 정상 발행됐으므로 수집 지점은 전진한다(놓친 구간은 기록으로 남김)
        assertEquals("https://n.news.naver.com/1", savedCheckpoints.single().second)
    }

    @Test
    fun `검색 결과가 끝나서 수집 지점에 못 닿은 경우는 누락이 아니다`() {
        givenCheckpointAt(100)
        givenPage(1, page(1, 2))

        val result = collector.collect("삼성전자")

        assertFalse(result.gapDetected)
        assertTrue(incidents.isEmpty())
    }

    @Test
    fun `API 호출이 끝내 실패하면 실패를 기록하고 발행도 수집 지점 변경도 하지 않는다`() {
        givenCheckpointAt(3)
        Mockito.`when`(naverNewsClient.searchNews("삼성전자", pageSize, 1)).thenThrow(RuntimeException("503"))

        val result = collector.collect("삼성전자")

        assertTrue(result.failed)
        assertEquals(CollectorIncidentType.FETCH_FAILED, incidents.single().type)
        assertTrue(published.isEmpty())
        assertTrue(savedCheckpoints.isEmpty())
    }

    @Test
    fun `Kafka 발행이 실패하면 수집 지점을 전진시키지 않아 다음 주기에 다시 받는다`() {
        givenCheckpointAt(3)
        givenPage(1, page(1, 2, 3))
        Mockito.`when`(kafkaTemplate.send(anyString(), anyString(), Mockito.any(NewsRawMessage::class.java)))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("broker down")))

        val result = collector.collect("삼성전자")

        assertTrue(result.failed)
        assertEquals(CollectorIncidentType.PUBLISH_FAILED, incidents.single().type)
        assertTrue(savedCheckpoints.isEmpty())
    }

    @Test
    fun `제목의 하이라이트 태그와 HTML 엔티티를 정리해 발행한다`() {
        givenPage(1, NaverNewsResponse(items = listOf(item(1, title = "<b>삼성전자</b> &quot;HBM&quot; 공급"))))

        collector.collect("삼성전자")

        assertEquals("삼성전자 \"HBM\" 공급", published.single().title)
        assertEquals("삼성전자", published.single().keyword)
    }
}
