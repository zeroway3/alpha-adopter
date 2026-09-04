package com.alphaadopter.core.domain.subscription

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface SubscriptionRepository : JpaRepository<Subscription, Long> {
    fun findAllByUserId(userId: Long): List<Subscription>
    fun existsByUserIdAndKeywordAndType(userId: Long, keyword: String, type: SubscriptionType): Boolean
    fun existsByKeyword(keyword: String): Boolean

    @Query("SELECT COUNT(DISTINCT s.keyword) FROM Subscription s")
    fun countDistinctKeywords(): Long
}
