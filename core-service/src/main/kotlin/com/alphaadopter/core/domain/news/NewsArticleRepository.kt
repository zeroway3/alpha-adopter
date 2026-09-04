package com.alphaadopter.core.domain.news

import org.springframework.data.jpa.repository.JpaRepository

interface NewsArticleRepository : JpaRepository<NewsArticle, Long> {
    fun existsByLink(link: String): Boolean
}
