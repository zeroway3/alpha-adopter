package com.alphaadopter.core.pipeline

import java.time.Instant

data class NewsMatchedMessage(
    val notificationId: Long,
    val userId: Long,
    val subscriptionKeyword: String,
    val title: String,
    val link: String,
    // 이 구독에 대한 사용자의 과거 참여도(0.0~1.0). 콜드스타트(전달 이력 부족) 시 null
    val personalizationScore: Double? = null,
    // 알림 지연 측정용: 기사 게시 시각(언론사 기준)과 수집 시각. 이전 형식 메시지와 호환되도록 nullable
    val publishedAt: Instant? = null,
    val collectedAt: Instant? = null,
)
