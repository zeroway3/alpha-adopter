package com.alphaadopter.core.notification

import com.alphaadopter.core.auth.AuthPrincipal
import com.alphaadopter.core.domain.notification.Notification
import com.alphaadopter.core.domain.notification.NotificationRepository
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class NotificationHistoryItem(
    val id: Long,
    val keyword: String,
    val articleTitle: String,
    val articleLink: String,
    val status: String,
    val createdAt: Instant,
    val sentAt: Instant?,
    val readAt: Instant?,
    val clickedAt: Instant?,
) {
    companion object {
        fun from(n: Notification) = NotificationHistoryItem(
            id = n.id!!,
            keyword = n.subscription.keyword,
            articleTitle = n.newsArticle.title,
            articleLink = n.newsArticle.link,
            status = n.status.name,
            createdAt = n.createdAt,
            sentAt = n.sentAt,
            readAt = n.readAt,
            clickedAt = n.clickedAt,
        )
    }
}

// 실시간 SSE는 "접속해 있는 동안 새로 온 것"만 보여주므로, 프런트엔드가 과거 알림 목록을
// 볼 수 있도록 별도로 조회 API를 둔다.
@RestController
@RequestMapping("/api/notifications")
class NotificationHistoryController(
    private val notificationRepository: NotificationRepository,
) {

    @GetMapping
    @Transactional(readOnly = true)
    fun history(@AuthenticationPrincipal principal: AuthPrincipal): List<NotificationHistoryItem> =
        notificationRepository.findAllBySubscriptionUserIdOrderByCreatedAtDesc(principal.userId)
            .map(NotificationHistoryItem::from)
}
