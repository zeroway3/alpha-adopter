package com.alphaadopter.core.ai

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import java.time.Duration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertIs

// NewsDeduplicationService의 유사도 판정·fail-open·윈도우 등록 로직을 실제 네트워크·Redis
// 없이 검증한다. Redis는 Mockito로, Voyage 임베딩 호출은 MockRestServiceServer로 대체한다.
class NewsDeduplicationServiceTest {

    private val jsonMapper = JsonMapper.builder().build()

    private data class MockRedis(
        val template: StringRedisTemplate,
        val valueOps: ValueOperations<String, String>,
        val zsetOps: ZSetOperations<String, String>,
    )

    private fun mockRedis(): MockRedis {
        val template = Mockito.mock(StringRedisTemplate::class.java)
        val valueOps = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        val zsetOps = Mockito.mock(ZSetOperations::class.java) as ZSetOperations<String, String>
        Mockito.`when`(template.opsForValue()).thenReturn(valueOps)
        Mockito.`when`(template.opsForZSet()).thenReturn(zsetOps)
        return MockRedis(template, valueOps, zsetOps)
    }

    private fun embeddingClientReturning(vararg vectors: DoubleArray): VoyageEmbeddingClient {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        vectors.forEach { vector ->
            val json = vector.joinToString(prefix = "[", postfix = "]")
            server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""{"data":[{"embedding":$json}]}""", MediaType.APPLICATION_JSON))
        }
        return VoyageEmbeddingClient(builder, apiKey = "fake-key", model = "voyage-4-lite", maxRetries = 3, retryInitialDelayMs = 1)
    }

    private fun notConfiguredEmbeddingClient() =
        VoyageEmbeddingClient(RestClient.builder(), apiKey = "", model = "m", maxRetries = 3, retryInitialDelayMs = 1)

    private fun service(
        embeddingClient: VoyageEmbeddingClient,
        redis: MockRedis,
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
        threshold: Double = 0.90,
    ) = NewsDeduplicationService(
        embeddingClient,
        redis.template,
        meterRegistry,
        similarityThreshold = threshold,
        windowHours = 48,
        maxCandidates = 200,
    )

    @Test
    fun `임베딩 서비스가 설정되지 않으면 항상 Unique를 반환하고 Redis를 건드리지 않는다`() {
        val redis = mockRedis()
        val result = service(notConfiguredEmbeddingClient(), redis).checkAndRegister("link1", "title", "desc")

        assertEquals(NewsDeduplicationService.DedupResult.Unique, result)
        Mockito.verifyNoInteractions(redis.valueOps)
        Mockito.verifyNoInteractions(redis.zsetOps)
    }

    @Test
    fun `임베딩 호출이 실패하면 안전하게 Unique로 처리한다`() {
        val redis = mockRedis()
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.voyageai.com/v1/embeddings")).andRespond(withServerError())
        val client = VoyageEmbeddingClient(builder, apiKey = "fake-key", model = "m", maxRetries = 0, retryInitialDelayMs = 1)

        val result = service(client, redis).checkAndRegister("link1", "title", "desc")

        assertEquals(NewsDeduplicationService.DedupResult.Unique, result)
    }

    @Test
    fun `윈도우에 후보가 없으면 Unique를 반환하고 자신을 등록한다`() {
        val redis = mockRedis()
        Mockito.`when`(redis.zsetOps.reverseRangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong())).thenReturn(emptySet())
        val client = embeddingClientReturning(doubleArrayOf(1.0, 0.0, 0.0))

        val result = service(client, redis).checkAndRegister("link1", "title", "desc")

        assertEquals(NewsDeduplicationService.DedupResult.Unique, result)
        Mockito.verify(redis.valueOps).set(eq("news:dedup:vector:link1"), anyString(), any(Duration::class.java))
        Mockito.verify(redis.zsetOps).add(eq("news:dedup:window"), eq("link1"), anyDouble())
    }

    @Test
    fun `유사도가 임계값 이상인 후보가 있으면 Duplicate를 반환한다`() {
        val redis = mockRedis()
        Mockito.`when`(redis.zsetOps.reverseRangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
            .thenReturn(linkedSetOf("existing-link"))
        Mockito.`when`(redis.valueOps.get("news:dedup:vector:existing-link"))
            .thenReturn(jsonMapper.writeValueAsString(doubleArrayOf(1.0, 0.0, 0.0)))
        // 방향이 거의 같은 벡터(코사인 유사도 ≈ 1.0)
        val client = embeddingClientReturning(doubleArrayOf(0.99, 0.01, 0.0))

        val result: NewsDeduplicationService.DedupResult = service(client, redis, threshold = 0.90)
            .checkAndRegister("new-link", "title", "desc")

        val duplicate = assertIs<NewsDeduplicationService.DedupResult.Duplicate>(result)
        assertEquals("existing-link", duplicate.duplicateOfLink)
    }

    @Test
    fun `유사도가 임계값 미만이면 Unique를 반환한다`() {
        val redis = mockRedis()
        Mockito.`when`(redis.zsetOps.reverseRangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
            .thenReturn(linkedSetOf("existing-link"))
        Mockito.`when`(redis.valueOps.get("news:dedup:vector:existing-link"))
            .thenReturn(jsonMapper.writeValueAsString(doubleArrayOf(1.0, 0.0, 0.0)))
        // 완전히 직교하는 벡터(코사인 유사도 = 0.0)
        val client = embeddingClientReturning(doubleArrayOf(0.0, 1.0, 0.0))

        val result = service(client, redis, threshold = 0.90).checkAndRegister("new-link", "title", "desc")

        assertEquals(NewsDeduplicationService.DedupResult.Unique, result)
    }

    @Test
    fun `벡터를 찾을 수 없는 후보는 비교 대상에서 조용히 제외된다`() {
        val redis = mockRedis()
        Mockito.`when`(redis.zsetOps.reverseRangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
            .thenReturn(linkedSetOf("stale-link"))
        // 벡터 키가 TTL로 이미 만료된 상황을 흉내낸다 (윈도우 정렬집합엔 남아있는데 벡터는 사라짐)
        Mockito.`when`(redis.valueOps.get("news:dedup:vector:stale-link")).thenReturn(null)
        val client = embeddingClientReturning(doubleArrayOf(1.0, 0.0))

        val result = service(client, redis).checkAndRegister("new-link", "title", "desc")

        assertEquals(NewsDeduplicationService.DedupResult.Unique, result)
    }
}
