package com.alphaadopter.core.domain.notification

import com.alphaadopter.core.domain.news.NewsArticle
import com.alphaadopter.core.domain.subscription.Subscription
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

enum class NotificationStatus {
    MATCHED,
    SENT,
    FAILED,
}

@Entity
@Table(name = "notifications")
class Notification(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    var subscription: Subscription,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "news_article_id", nullable = false)
    var newsArticle: NewsArticle,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: NotificationStatus = NotificationStatus.MATCHED,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    var sentAt: Instant? = null

    // 참여도 추적용 (docs/future-ideas.md 개인화 필터링 아이디어 참고). 스코어링/필터링 로직은 아직 없고 데이터만 쌓는다.
    var readAt: Instant? = null
    var clickedAt: Instant? = null
}
