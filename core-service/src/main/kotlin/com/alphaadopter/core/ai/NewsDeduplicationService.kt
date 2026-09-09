package com.alphaadopter.core.ai

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import kotlin.math.sqrt

// 3단계 "AI가 시스템의 한 축": 문자열 매칭을 통과한 기사가, 최근에 이미 알림으로 내보낸 다른
// 기사와 같은 사건을 다루는지 임베딩 코사인 유사도로 판별한다. 같은 사건이 여러 언론사에서
// 각자 다른 제목·문장으로 보도되면 지금까지는 전부 별도 알림으로 나갔는데, 이 서비스가
// "표현은 다른데 사실상 같은 기사"를 감지해 중복 알림과 불필요한 Claude 호출을 함께 줄인다.
//
// 벡터 저장소를 새로 두는 대신, 이미 SSE Pub/Sub·adminStats 캐시로 쓰고 있는 Redis에 최근
// N시간짜리 슬라이딩 윈도우(정렬집합 + 벡터 해시)를 두고 브루트포스로 비교한다 — 개인 프로젝트
// 규모(구독 키워드 최대 15개, 60초 폴링)에서는 윈도우 안 후보가 최대 수백 건 수준이라 별도
// 벡터 DB(pgvector 등) 없이 이 방식으로 충분하다.
@Component
class NewsDeduplicationService(
    private val embeddingClient: VoyageEmbeddingClient,
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    @Value("\${app.ai.dedup-similarity-threshold:0.90}") private val similarityThreshold: Double,
    @Value("\${app.ai.dedup-window-hours:48}") private val windowHours: Long,
    @Value("\${app.ai.dedup-max-candidates:200}") private val maxCandidates: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jsonMapper = JsonMapper.builder().build()

    sealed interface DedupResult {
        data class Duplicate(val duplicateOfLink: String, val similarity: Double) : DedupResult
        data object Unique : DedupResult
    }

    companion object {
        private const val WINDOW_KEY = "news:dedup:window"
        private const val VECTOR_KEY_PREFIX = "news:dedup:vector:"
    }

    // 이 기사가 최근 윈도우 안의 다른 기사와 중복인지 판단하고, 결과와 무관하게 자기 자신의
    // 벡터를 윈도우에 등록한다(이후 들어올 3번째, 4번째 보도가 이 기사와도 비교될 수 있도록).
    // Voyage가 설정되지 않았거나 호출이 실패하면 안전하게 Unique로 처리한다(fail-open) —
    // 임베딩 인프라 장애가 알림 파이프라인 전체를 막는 장애점이 되면 안 된다(RelevanceScorer와
    // 동일한 원칙).
    fun checkAndRegister(link: String, title: String, description: String): DedupResult {
        if (!embeddingClient.isConfigured) return DedupResult.Unique

        val vector = runCatching { embeddingClient.embed("$title\n$description") }
            .getOrElse { e ->
                log.warn("임베딩 생성 실패, 중복 검사를 건너뜁니다: {}", e.message)
                return DedupResult.Unique
            }

        val zsetOps = redisTemplate.opsForZSet()
        val valueOps = redisTemplate.opsForValue()

        val now = Instant.now()
        val cutoffMillis = now.minus(Duration.ofHours(windowHours)).toEpochMilli().toDouble()
        zsetOps.removeRangeByScore(WINDOW_KEY, Double.NEGATIVE_INFINITY, cutoffMillis)

        val candidates = zsetOps.reverseRangeByScore(WINDOW_KEY, cutoffMillis, now.toEpochMilli().toDouble(), 0, maxCandidates)
            .orEmpty()

        var bestMatchLink: String? = null
        var bestSimilarity = 0.0
        candidates.forEach { candidateLink ->
            val candidateVectorJson = valueOps.get(VECTOR_KEY_PREFIX + candidateLink) ?: return@forEach
            val candidateVector = jsonMapper.readValue(candidateVectorJson, DoubleArray::class.java)
            val similarity = cosineSimilarity(vector, candidateVector)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestMatchLink = candidateLink
            }
        }

        valueOps.set(VECTOR_KEY_PREFIX + link, jsonMapper.writeValueAsString(vector), Duration.ofHours(windowHours))
        zsetOps.add(WINDOW_KEY, link, now.toEpochMilli().toDouble())

        return if (bestMatchLink != null && bestSimilarity >= similarityThreshold) {
            meterRegistry.counter("news.dedup", "result", "duplicate").increment()
            DedupResult.Duplicate(bestMatchLink!!, bestSimilarity)
        } else {
            meterRegistry.counter("news.dedup", "result", "unique").increment()
            DedupResult.Unique
        }
    }

    private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
