package com.alphaadopter.core.domain.news

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// 저작권 이슈 방지를 위해 본문 전체는 저장하지 않고 제목·요약·링크만 보관 (docs/phase0-news-source-validation.md 참고)
@Entity
@Table(name = "news_articles")
class NewsArticle(
    @Column(nullable = false, length = 500)
    var title: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var description: String,

    // 네이버 API가 안정적인 고유 ID를 제공하지 않아, link를 중복 수집 방지 키로 사용
    @Column(nullable = false, unique = true, length = 1000)
    var link: String,

    @Column(length = 1000)
    var originalLink: String? = null,

    @Column(nullable = false)
    var publishedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var collectedAt: Instant = Instant.now()
}
