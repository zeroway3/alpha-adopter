package com.alphaadopter.core.subscription

import com.alphaadopter.core.domain.subscription.Subscription
import com.alphaadopter.core.domain.subscription.SubscriptionType
import jakarta.validation.constraints.NotBlank
import java.time.Instant

data class SubscriptionCreateRequest(
    @field:NotBlank
    val keyword: String,
    val type: SubscriptionType,
)

data class SubscriptionResponse(
    val id: Long,
    val keyword: String,
    val type: SubscriptionType,
    val createdAt: Instant,
) {
    companion object {
        fun from(subscription: Subscription) = SubscriptionResponse(
            id = subscription.id!!,
            keyword = subscription.keyword,
            type = subscription.type,
            createdAt = subscription.createdAt,
        )
    }
}
