package com.alphaadopter.core.domain.subscription

import org.springframework.data.jpa.repository.JpaRepository

interface SubscriptionRepository : JpaRepository<Subscription, Long> {
    fun findAllByUserId(userId: Long): List<Subscription>
    fun existsByUserIdAndKeywordAndType(userId: Long, keyword: String, type: SubscriptionType): Boolean
}
