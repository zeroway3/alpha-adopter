package com.alphaadopter.core.collector

data class NewsRawMessage(
    val keyword: String,
    val title: String,
    val description: String,
    val link: String,
    val originalLink: String?,
    val pubDate: String,
)
