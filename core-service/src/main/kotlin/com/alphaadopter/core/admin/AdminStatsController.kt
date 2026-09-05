package com.alphaadopter.core.admin

import com.alphaadopter.core.domain.news.NewsArticleRepository
import com.alphaadopter.core.domain.notification.Notification
import com.alphaadopter.core.domain.notification.NotificationRepository
import com.alphaadopter.core.domain.notification.NotificationStatus
import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import com.alphaadopter.core.domain.user.UserRepository
import com.alphaadopter.core.user.AdminEmailChecker
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

data class AdminNotificationSummary(
    val id: Long,
    val userEmail: String,
    val keyword: String,
    val articleTitle: String,
    val status: String,
    val createdAt: Instant,
    val readAt: Instant?,
    val clickedAt: Instant?,
) {
    companion object {
        fun from(n: Notification) = AdminNotificationSummary(
            id = n.id!!,
            userEmail = n.subscription.user.email,
            keyword = n.subscription.keyword,
            articleTitle = n.newsArticle.title,
            status = n.status.name,
            createdAt = n.createdAt,
            readAt = n.readAt,
            clickedAt = n.clickedAt,
        )
    }
}

data class AdminStatsResponse(
    val totalUsers: Long,
    val totalSubscriptions: Long,
    val totalNewsArticles: Long,
    val notificationsMatched: Long,
    val notificationsSent: Long,
    val notificationsFailed: Long,
    val notificationsRead: Long,
    val notificationsClicked: Long,
    val recentNotifications: List<AdminNotificationSummary>,
)

// 별도 인증 시스템 없이(future-ideas.md 참고) app.admin.emails 화이트리스트로만 접근을 제한한다.
// 실시간 대시보드(Grafana)는 비용/보안 때문에 공개 노출하지 않고 kubectl port-forward로만 접근 —
// 이 엔드포인트는 그 대신 브라우저에서 바로 볼 수 있는 최소한의 운영 통계만 제공한다.
@RestController
@RequestMapping("/api/admin")
@Validated
class AdminStatsController(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val newsArticleRepository: NewsArticleRepository,
    private val notificationRepository: NotificationRepository,
    private val adminEmailChecker: AdminEmailChecker,
) {

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    fun stats(@RequestParam @NotBlank @Email email: String): AdminStatsResponse {
        requireAdmin(email)

        return AdminStatsResponse(
            totalUsers = userRepository.count(),
            totalSubscriptions = subscriptionRepository.count(),
            totalNewsArticles = newsArticleRepository.count(),
            notificationsMatched = notificationRepository.countByStatus(NotificationStatus.MATCHED),
            notificationsSent = notificationRepository.countByStatus(NotificationStatus.SENT),
            notificationsFailed = notificationRepository.countByStatus(NotificationStatus.FAILED),
            notificationsRead = notificationRepository.countByReadAtIsNotNull(),
            notificationsClicked = notificationRepository.countByClickedAtIsNotNull(),
            recentNotifications = notificationRepository.findTop20ByOrderByCreatedAtDesc()
                .map(AdminNotificationSummary::from),
        )
    }

    private fun requireAdmin(email: String) {
        if (!adminEmailChecker.isAdmin(email)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 접근할 수 있습니다.")
        }
    }
}
