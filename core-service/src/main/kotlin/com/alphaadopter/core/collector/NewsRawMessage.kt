package com.alphaadopter.core.collector

import java.time.Instant

data class NewsRawMessage(
    val keyword: String,
    val title: String,
    val description: String,
    val link: String,
    val originalLink: String?,
    val pubDate: String,
    // 수집기가 API 응답을 받은 시각. 수집 → 알림 전달 구간의 지연 측정에 쓴다
    val collectedAt: Instant? = null,
)
