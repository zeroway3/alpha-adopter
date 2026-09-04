package com.alphaadopter.core.collector

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

data class NaverNewsItem(
    val title: String = "",
    val originallink: String = "",
    val link: String = "",
    val description: String = "",
    val pubDate: String = "",
)

data class NaverNewsResponse(
    val items: List<NaverNewsItem> = emptyList(),
)

// docs/phase0-news-source-validation.md 에서 검증한 NAVER API HUB 뉴스 검색 연동
@Component
class NaverNewsClient(
    @Value("\${naver.client-id}") private val clientId: String,
    @Value("\${naver.client-secret}") private val clientSecret: String,
) {
    private val restClient = RestClient.builder()
        .baseUrl("https://naverapihub.apigw.ntruss.com")
        .build()

    fun searchNews(query: String, display: Int = 10): List<NaverNewsItem> {
        val response = restClient.get()
            .uri { builder ->
                builder.path("/search/v1/news")
                    .queryParam("query", query)
                    .queryParam("display", display)
                    .build()
            }
            .header("X-NCP-APIGW-API-KEY-ID", clientId)
            .header("X-NCP-APIGW-API-KEY", clientSecret)
            .retrieve()
            .body(NaverNewsResponse::class.java)
        return response?.items ?: emptyList()
    }
}

// 응답 title/description에 검색어 하이라이트용 <b> 태그가 포함돼 있어 저장 전 제거
fun stripHighlightTags(text: String): String = text.replace(Regex("</?b>"), "")
