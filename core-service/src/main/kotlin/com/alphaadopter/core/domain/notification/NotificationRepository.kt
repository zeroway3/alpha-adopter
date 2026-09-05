package com.alphaadopter.core.domain.notification

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface DailyNotificationCount {
    // 네이티브 쿼리 결과(java.sql.Timestamp)를 Spring Data 프로젝션이 LocalDate로 바로
    // 변환하지 못해서(UnsupportedOperationException) Instant로 받고, 날짜 변환은 호출부에서 처리한다.
    fun getDay(): Instant
    fun getTotal(): Long
}

interface NotificationRepository : JpaRepository<Notification, Long> {

    @Query(
        "SELECT n FROM Notification n " +
            "WHERE n.status = :status AND n.subscription.user.isMember = :isMember",
    )
    fun findAllByStatusAndSubscriptionUserIsMember(status: NotificationStatus, isMember: Boolean): List<Notification>

    fun findAllBySubscriptionUserIdOrderByCreatedAtDesc(userId: Long): List<Notification>

    fun findFirstBySubscriptionKeywordAndNewsArticleLink(keyword: String, link: String): Notification?

    fun findTop20ByOrderByCreatedAtDesc(): List<Notification>

    fun countByStatus(status: NotificationStatus): Long

    fun countByReadAtIsNotNull(): Long

    fun countByClickedAtIsNotNull(): Long

    // 관리자 화면 모니터링용 최근 N일 알림 발생 추이. DB에 종속적인 date_trunc를 쓰지만,
    // 이 프로젝트는 PostgreSQL(RDS)만 대상으로 하므로 문제 없음
    @Query(
        value = "SELECT date_trunc('day', n.created_at) AS day, COUNT(*) AS total " +
            "FROM notifications n WHERE n.created_at >= :since " +
            "GROUP BY date_trunc('day', n.created_at) ORDER BY day",
        nativeQuery = true,
    )
    fun dailyCountsSince(since: Instant): List<DailyNotificationCount>
}
