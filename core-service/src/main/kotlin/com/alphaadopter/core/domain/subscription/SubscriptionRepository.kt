package com.alphaadopter.core.domain.subscription

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface KeywordSubscriberCount {
    fun getKeyword(): String
    fun getSubscriberCount(): Long
}

interface SubscriptionRepository : JpaRepository<Subscription, Long> {
    fun findAllByUserId(userId: Long): List<Subscription>
    fun existsByUserIdAndKeywordAndType(userId: Long, keyword: String, type: SubscriptionType): Boolean
    fun existsByKeyword(keyword: String): Boolean
    fun countByUserId(userId: Long): Long

    @Query("SELECT COUNT(DISTINCT s.keyword) FROM Subscription s")
    fun countDistinctKeywords(): Long

    @Query(
        "SELECT s.keyword AS keyword, COUNT(DISTINCT s.user.id) AS subscriberCount " +
            "FROM Subscription s GROUP BY s.keyword ORDER BY COUNT(DISTINCT s.user.id) DESC",
    )
    fun topKeywords(pageable: Pageable): List<KeywordSubscriberCount>
}
