package com.alphaadopter.core.collector

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient

data class NaverNewsItem(
    val title: String = "",
    val originallink: String = "",
    val link: String = "",
    val description: String = "",
    val pubDate: String = "",
)

data class NaverNewsResponse(
    val total: Long = 0,
    val start: Int = 1,
    val display: Int = 0,
    val items: List<NaverNewsItem> = emptyList(),
)

// docs/phase0-news-source-validation.md 에서 검증한 NAVER API HUB 뉴스 검색 연동
@Component
class NaverNewsClient(
    restClientBuilder: RestClient.Builder,
    @Value("\${naver.client-id}") private val clientId: String,
    @Value("\${naver.client-secret}") private val clientSecret: String,
    @Value("\${app.collector.max-retries:3}") private val maxRetries: Int,
    @Value("\${app.collector.retry-initial-delay-ms:500}") private val retryInitialDelayMs: Long,
) {
    // 인증정보가 없으면(기여자 로컬 등) 수집기가 아예 호출을 시도하지 않는다 — AI 키와 같은 원칙
    val isConfigured: Boolean = clientId.isNotBlank() && clientSecret.isNotBlank()

    // NAVER API HUB가 JSON 본문을 Content-Type: text/plain으로 내려줘서 기본 컨버터로는 파싱이 안 됨
    private val restClient = restClientBuilder
        .baseUrl("https://naverapihub.apigw.ntruss.com")
        .configureMessageConverters { converters ->
            converters.withJsonConverter(
                JacksonJsonHttpMessageConverter().apply {
                    supportedMediaTypes = supportedMediaTypes + MediaType.TEXT_PLAIN
                },
            )
        }
        .build()

    // sort=date를 명시하지 않으면 API 기본값인 정확도순(sim)으로 내려와, "가장 최근 기사"가 아니라
    // "검색어와 가장 비슷한 기사"를 받게 된다. 수집기는 마지막 수집 지점 이후의 새 기사를 순서대로
    // 따라가야 하므로 반드시 최신순으로 요청한다. display 최대 100, start 최대 1000(API 제약).
    fun searchNews(query: String, display: Int = 100, start: Int = 1): NaverNewsResponse {
        require(display in 1..100) { "display는 1~100 사이여야 합니다: $display" }
        require(start in 1..1000) { "start는 1~1000 사이여야 합니다: $start" }

        var attempt = 0
        var delayMs = retryInitialDelayMs
        while (true) {
            try {
                return restClient.get()
                    .uri { builder ->
                        builder.path("/search/v1/news")
                            .queryParam("query", query)
                            .queryParam("display", display)
                            .queryParam("start", start)
                            .queryParam("sort", "date")
                            .build()
                    }
                    .header("X-NCP-APIGW-API-KEY-ID", clientId)
                    .header("X-NCP-APIGW-API-KEY", clientSecret)
                    .retrieve()
                    .body(NaverNewsResponse::class.java)
                    ?: NaverNewsResponse()
            } catch (e: Exception) {
                attempt++
                if (!isRetryable(e) || attempt > maxRetries) throw e
                Thread.sleep(delayMs)
                delayMs *= 2
            }
        }
    }

    // 429(할당량·초당 호출 제한), 5xx(일시적 서버 오류), 네트워크 오류만 재시도한다. 401/403
    // (인증 정보 오류)나 400(잘못된 파라미터)은 몇 번을 다시 보내도 결과가 같으므로 바로 실패시킨다.
    private fun isRetryable(e: Exception): Boolean = when (e) {
        is HttpClientErrorException -> e.statusCode.value() == 429
        is HttpServerErrorException -> true
        is ResourceAccessException -> true
        else -> false
    }
}

// 응답 title/description에 검색어 하이라이트용 <b> 태그가 포함돼 있어 저장 전 제거
fun stripHighlightTags(text: String): String = text.replace(Regex("</?b>"), "")
