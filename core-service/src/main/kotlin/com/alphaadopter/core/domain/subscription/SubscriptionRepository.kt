package com.alphaadopter.core.domain.subscription

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface KeywordSubscriberCount {
    fun getKeyword(): String
    fun getSubscriberCount(): Long
}

interface UserSubscriptionCount {
    fun getUserId(): Long
    fun getCount(): Long
}

interface SubscriptionRepository : JpaRepository<Subscription, Long> {
    fun findAllByUserId(userId: Long): List<Subscription>
    fun existsByUserIdAndKeywordAndType(userId: Long, keyword: String, type: SubscriptionType): Boolean
    fun existsByKeyword(keyword: String): Boolean
    fun countByUserId(userId: Long): Long

    // SubscriptionCache 전용 — news.raw 컨슈머는 뉴스 1건마다 구독자의 userId까지 필요하므로,
    // user 지연 로딩 프록시가 세션 밖(캐시에 보관된 뒤)에서 터지지 않도록 JOIN FETCH로 함께 가져온다
    @Query("SELECT s FROM Subscription s JOIN FETCH s.user")
    fun findAllWithUser(): List<Subscription>

    @Query("SELECT COUNT(DISTINCT s.keyword) FROM Subscription s")
    fun countDistinctKeywords(): Long

    @Query(
        "SELECT s.keyword AS keyword, COUNT(DISTINCT s.user.id) AS subscriberCount " +
            "FROM Subscription s GROUP BY s.keyword ORDER BY COUNT(DISTINCT s.user.id) DESC",
    )
    fun topKeywords(pageable: Pageable): List<KeywordSubscriberCount>

    // AdminStatsController.users()가 유저마다 countByUserId를 따로 호출하던 N+1을 없애기 위한
    // 일괄 집계 쿼리 (topKeywords와 동일한 GROUP BY 패턴)
    @Query("SELECT s.user.id AS userId, COUNT(s) AS count FROM Subscription s GROUP BY s.user.id")
    fun countGroupedByUser(): List<UserSubscriptionCount>
}
