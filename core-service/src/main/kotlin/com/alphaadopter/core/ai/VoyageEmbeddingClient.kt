package com.alphaadopter.core.ai

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

data class VoyageEmbeddingRequest(val input: List<String>, val model: String, val input_type: String = "document")

data class VoyageEmbeddingData(val embedding: List<Double> = emptyList(), val index: Int = 0)

data class VoyageEmbeddingResponse(val data: List<VoyageEmbeddingData> = emptyList())

// 텍스트를 벡터로 바꿔주는 Voyage AI 임베딩 API 클라이언트. Claude 자체에는 임베딩 엔드포인트가
// 없어(Anthropic 공식 문서도 임베딩이 필요하면 Voyage AI를 권장한다) 별도 서비스를 쓴다.
// 이 벡터는 NewsDeduplicationService가 "표현은 다른데 같은 사건을 다룬 기사"를 코사인
// 유사도로 판별하는 데 쓰인다.
@Component
class VoyageEmbeddingClient(
    restClientBuilder: RestClient.Builder,
    @Value("\${app.ai.voyage-api-key:}") private val apiKey: String,
    @Value("\${app.ai.embedding-model:voyage-4-lite}") private val model: String,
    @Value("\${app.ai.embedding-max-retries:3}") private val maxRetries: Int,
    @Value("\${app.ai.embedding-retry-initial-delay-ms:500}") private val retryInitialDelayMs: Long,
) {
    val isConfigured: Boolean = apiKey.isNotBlank()

    private val restClient = restClientBuilder
        .baseUrl("https://api.voyageai.com")
        .build()

    // input_type="document"는 Voyage가 권장하는 비대칭 검색 모드 설정이다 — 나중에 "이 키워드와
    // 관련된 기사 찾기" 같은 검색 기능을 추가한다면 그때는 질의문 쪽에 "query"를 써야 하지만,
    // 지금은 저장/비교 대상인 기사 본문만 임베딩하므로 "document" 하나로 고정한다.
    fun embed(text: String): DoubleArray {
        check(isConfigured) { "VOYAGE_API_KEY가 설정되지 않았습니다." }

        var attempt = 0
        var delayMs = retryInitialDelayMs
        while (true) {
            try {
                val response = restClient.post()
                    .uri("/v1/embeddings")
                    .header("Authorization", "Bearer $apiKey")
                    .header("content-type", "application/json")
                    .body(VoyageEmbeddingRequest(input = listOf(text), model = model))
                    .retrieve()
                    .body(VoyageEmbeddingResponse::class.java)
                    ?: error("Voyage 응답 본문이 비어 있습니다.")

                val embedding = response.data.firstOrNull()?.embedding
                    ?: error("Voyage 응답에서 embedding을 찾을 수 없습니다.")
                return embedding.toDoubleArray()
            } catch (e: HttpClientErrorException) {
                attempt++
                if (e.statusCode.value() != 429 || attempt > maxRetries) throw e
                Thread.sleep(delayMs)
                delayMs *= 2
            }
        }
    }
}
