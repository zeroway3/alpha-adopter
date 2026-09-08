package com.alphaadopter.core.domain.notification

import org.springframework.data.domain.Pageable
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

    // 알림 히스토리 커서 기반 페이지네이션. 유저당 알림이 무한히 쌓이는 구조라 전체 조회 대신
    // (createdAt, id) 키셋으로 최신순 한 페이지씩 끊어 읽는다. id는 같은 createdAt이 여러 건일 때의
    // 안정적 정렬/커서 tie-breaker. to-one 연관은 JOIN FETCH로 N+1(키워드/기사 제목 lazy 로딩)을 없앤다.
    @Query(
        "SELECT n FROM Notification n " +
            "JOIN FETCH n.subscription s " +
            "JOIN FETCH n.newsArticle " +
            "WHERE s.user.id = :userId " +
            "ORDER BY n.createdAt DESC, n.id DESC",
    )
    fun findHistoryFirstPage(userId: Long, pageable: Pageable): List<Notification>

    @Query(
        "SELECT n FROM Notification n " +
            "JOIN FETCH n.subscription s " +
            "JOIN FETCH n.newsArticle " +
            "WHERE s.user.id = :userId " +
            "AND (n.createdAt < :cursorCreatedAt " +
            "     OR (n.createdAt = :cursorCreatedAt AND n.id < :cursorId)) " +
            "ORDER BY n.createdAt DESC, n.id DESC",
    )
    fun findHistoryAfter(userId: Long, cursorCreatedAt: Instant, cursorId: Long, pageable: Pageable): List<Notification>

    fun findFirstBySubscriptionKeywordAndNewsArticleLink(keyword: String, link: String): Notification?

    fun findTop20ByOrderByCreatedAtDesc(): List<Notification>

    fun countByStatus(status: NotificationStatus): Long

    fun countByReadAtIsNotNull(): Long

    fun countByClickedAtIsNotNull(): Long

    fun countByRelevanceScoreIsNotNull(): Long

    @Query("SELECT AVG(n.relevanceScore) FROM Notification n WHERE n.relevanceScore IS NOT NULL")
    fun averageRelevanceScore(): Double?

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
