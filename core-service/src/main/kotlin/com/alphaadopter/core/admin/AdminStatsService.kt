package com.alphaadopter.core.admin

import com.alphaadopter.core.ai.ClaudeRelevanceClient
import com.alphaadopter.core.config.CacheConfig
import com.alphaadopter.core.domain.news.NewsArticleRepository
import com.alphaadopter.core.domain.notification.NotificationRepository
import com.alphaadopter.core.domain.notification.NotificationStatus
import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import com.alphaadopter.core.domain.user.UserRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

@Service
class AdminStatsService(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val newsArticleRepository: NewsArticleRepository,
    private val notificationRepository: NotificationRepository,
    private val claudeRelevanceClient: ClaudeRelevanceClient,
) {

    // 호출마다 count/avg 집계 쿼리를 8개 넘게 도는 구간. 관리자 대시보드 새로고침 수준의 저빈도
    // 호출이고 30초 지연은 허용되므로 결과 전체를 Redis에 TTL 캐싱한다 (CacheConfig 참고).
    // 관리자 접근 제어는 컨트롤러(requireAdmin)에서 캐시보다 먼저 걸리므로 여기서는 신경 쓰지 않는다.
    @Cacheable(cacheNames = [CacheConfig.ADMIN_STATS_CACHE], key = "'snapshot'")
    fun snapshot(): AdminStatsResponse = AdminStatsResponse(
        totalUsers = userRepository.count(),
        totalSubscriptions = subscriptionRepository.count(),
        totalNewsArticles = newsArticleRepository.count(),
        notificationsMatched = notificationRepository.countByStatus(NotificationStatus.MATCHED),
        notificationsSent = notificationRepository.countByStatus(NotificationStatus.SENT),
        notificationsFailed = notificationRepository.countByStatus(NotificationStatus.FAILED),
        notificationsRead = notificationRepository.countByReadAtIsNotNull(),
        notificationsClicked = notificationRepository.countByClickedAtIsNotNull(),
        aiFilterEnabled = claudeRelevanceClient.isConfigured,
        notificationsAiScored = notificationRepository.countByRelevanceScoreIsNotNull(),
        averageRelevanceScore = notificationRepository.averageRelevanceScore(),
        recentNotifications = notificationRepository.findRecentWithDetails(PageRequest.of(0, RECENT_LIMIT))
            .map(AdminNotificationSummary::from),
    )

    private companion object {
        const val RECENT_LIMIT = 20
    }
}
