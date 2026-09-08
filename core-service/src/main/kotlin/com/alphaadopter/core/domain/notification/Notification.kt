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
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

enum class NotificationStatus {
    MATCHED,
    SENT,
    FAILED,
}

// status/created_at: countByStatus, findTop20ByOrderByCreatedAtDesc, 관리자 대시보드의 일별 집계
// 네이티브 쿼리가 필터링/정렬에 사용. news_article_id: Postgres는 FK 컬럼을 자동으로 인덱싱하지 않는다.
// (subscription_id, created_at, id): 알림 히스토리 커서 페이지네이션 전용 — 유저의 구독별로
// (createdAt, id) 정렬·범위 스캔을 인덱스만으로 처리하게 해준다. 단독 subscription_id 인덱스는
// 이 복합 인덱스의 선두 컬럼으로 커버되므로 별도로 두지 않는다.
@Entity
@Table(
    name = "notifications",
    indexes = [
        Index(name = "idx_notifications_status", columnList = "status"),
        Index(name = "idx_notifications_created_at", columnList = "created_at"),
        Index(name = "idx_notifications_sub_created_id", columnList = "subscription_id, created_at, id"),
        Index(name = "idx_notifications_news_article_id", columnList = "news_article_id"),
    ],
)
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

    // Claude가 판단한 키워드-기사 관련도(0~100). AI 비활성화(ANTHROPIC_API_KEY 없음)나
    // 판단 실패 시 null — 이 경우 키워드 문자열 매칭 결과를 그대로 신뢰한다 (fail-open)
    var relevanceScore: Int? = null
}
