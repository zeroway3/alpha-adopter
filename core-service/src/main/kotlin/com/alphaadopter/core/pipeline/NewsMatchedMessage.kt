package com.alphaadopter.core.pipeline

data class NewsMatchedMessage(
    val notificationId: Long,
    val userId: Long,
    val subscriptionKeyword: String,
    val title: String,
    val link: String,
    // 이 구독에 대한 사용자의 과거 참여도(0.0~1.0). 콜드스타트(전달 이력 부족) 시 null
    val personalizationScore: Double? = null,
)
