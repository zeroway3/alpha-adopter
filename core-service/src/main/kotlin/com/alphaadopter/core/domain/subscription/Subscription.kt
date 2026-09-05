package com.alphaadopter.core.domain.subscription

import com.alphaadopter.core.domain.user.User
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

enum class SubscriptionType {
    KEYWORD,
    STOCK_CODE,
}

// keyword: news.raw 컨슈머가 뉴스 1건마다 문자열 매칭에 사용(SubscriptionCache 경유긴 하지만
// 캐시 자체를 채우는 findAllWithUser도 결국 이 테이블을 읽음). user_id: Postgres는 FK 컬럼을
// 자동으로 인덱싱하지 않으므로 findAllByUserId/countByUserId 등 조회를 위해 명시적으로 인덱싱.
@Entity
@Table(
    name = "subscriptions",
    indexes = [
        Index(name = "idx_subscriptions_keyword", columnList = "keyword"),
        Index(name = "idx_subscriptions_user_id", columnList = "user_id"),
    ],
)
class Subscription(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    @Column(nullable = false)
    var keyword: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: SubscriptionType,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
