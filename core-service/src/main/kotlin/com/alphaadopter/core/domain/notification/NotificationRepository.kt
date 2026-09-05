package com.alphaadopter.core.domain.notification

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface NotificationRepository : JpaRepository<Notification, Long> {

    @Query(
        "SELECT n FROM Notification n " +
            "WHERE n.status = :status AND n.subscription.user.isMember = :isMember",
    )
    fun findAllByStatusAndSubscriptionUserIsMember(status: NotificationStatus, isMember: Boolean): List<Notification>

    fun findAllBySubscriptionUserEmailOrderByCreatedAtDesc(email: String): List<Notification>

    fun findTop20ByOrderByCreatedAtDesc(): List<Notification>

    fun countByStatus(status: NotificationStatus): Long

    fun countByReadAtIsNotNull(): Long

    fun countByClickedAtIsNotNull(): Long
}
