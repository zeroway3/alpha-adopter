package com.alphaadopter.core.pipeline

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

// 뉴스 소스마다 포맷이 달라질 수 있어, 정규화 전 원본을 스키마 유연한 MongoDB에 우선 저장 (landing zone)
@Document(collection = "raw_news")
class RawNewsDocument(
    val keyword: String,
    val title: String,
    val description: String,
    val link: String,
    val originalLink: String?,
    val pubDate: String,
) {
    @Id
    var id: String? = null

    var collectedAt: Instant = Instant.now()
}
