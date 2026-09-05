package com.alphaadopter.core.admin

import com.alphaadopter.core.auth.AuthPrincipal
import com.alphaadopter.core.domain.news.NewsArticleRepository
import com.alphaadopter.core.domain.notification.DailyNotificationCount
import com.alphaadopter.core.domain.notification.Notification
import com.alphaadopter.core.domain.notification.NotificationRepository
import com.alphaadopter.core.domain.notification.NotificationStatus
import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import com.alphaadopter.core.user.AdminEmailChecker
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

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

data class AdminUserSummary(
    val id: Long,
    val email: String,
    val isMember: Boolean,
    val isAdmin: Boolean,
    val subscriptionCount: Long,
    val createdAt: Instant,
) {
    companion object {
        fun from(user: User, isAdmin: Boolean, subscriptionCount: Long) = AdminUserSummary(
            id = user.id!!,
            email = user.email,
            isMember = user.isMember,
            isAdmin = isAdmin,
            subscriptionCount = subscriptionCount,
            createdAt = user.createdAt,
        )
    }
}

data class AdminKeywordSummary(val keyword: String, val subscriberCount: Long)

data class AdminDailyCount(val day: LocalDate, val total: Long) {
    companion object {
        fun from(row: DailyNotificationCount) =
            AdminDailyCount(day = row.getDay().atZone(ZoneOffset.UTC).toLocalDate(), total = row.getTotal())
    }
}

// 로그인은 JWT로 하지만, 관리자 판별은 별도 Role 테이블 없이 app.admin.emails 화이트리스트로만 한다.
// 실시간 대시보드(Grafana)는 비용/보안 때문에 공개 노출하지 않고 kubectl port-forward로만 접근 —
// 이 엔드포인트들은 그 대신 브라우저에서 바로 볼 수 있는 운영 통계/데이터 조회를 제공한다.
@RestController
@RequestMapping("/api/admin")
class AdminStatsController(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val newsArticleRepository: NewsArticleRepository,
    private val notificationRepository: NotificationRepository,
    private val adminEmailChecker: AdminEmailChecker,
) {

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    fun stats(@AuthenticationPrincipal principal: AuthPrincipal): AdminStatsResponse {
        requireAdmin(principal)

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

    @GetMapping("/users")
    fun users(@AuthenticationPrincipal principal: AuthPrincipal): List<AdminUserSummary> {
        requireAdmin(principal)
        return userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).map { user ->
            AdminUserSummary.from(
                user = user,
                isAdmin = adminEmailChecker.isAdmin(user.email),
                subscriptionCount = subscriptionRepository.countByUserId(user.id!!),
            )
        }
    }

    @GetMapping("/keywords")
    fun keywords(@AuthenticationPrincipal principal: AuthPrincipal): List<AdminKeywordSummary> {
        requireAdmin(principal)
        return subscriptionRepository.topKeywords(PageRequest.of(0, 10))
            .map { AdminKeywordSummary(it.getKeyword(), it.getSubscriberCount()) }
    }

    @GetMapping("/stats/daily")
    fun dailyStats(@AuthenticationPrincipal principal: AuthPrincipal): List<AdminDailyCount> {
        requireAdmin(principal)
        val since = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(6, ChronoUnit.DAYS)
        return notificationRepository.dailyCountsSince(since).map(AdminDailyCount::from)
    }

    private fun requireAdmin(principal: AuthPrincipal) {
        if (!adminEmailChecker.isAdmin(principal.email)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 접근할 수 있습니다.")
        }
    }
}
