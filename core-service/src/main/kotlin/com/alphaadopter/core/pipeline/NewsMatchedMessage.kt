package com.alphaadopter.core.pipeline

data class NewsMatchedMessage(
    val notificationId: Long,
    val userId: Long,
    val subscriptionKeyword: String,
    val title: String,
    val link: String,
)
