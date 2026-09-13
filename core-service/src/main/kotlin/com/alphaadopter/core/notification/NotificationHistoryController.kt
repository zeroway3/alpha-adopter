package com.alphaadopter.core.notification

import com.alphaadopter.core.auth.AuthPrincipal
import com.alphaadopter.core.domain.notification.Notification
import com.alphaadopter.core.domain.notification.NotificationRepository
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

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
    // Claude 관련도 점수(0~100). AI 필터 비활성화/실패 시 null
    val relevanceScore: Int?,
    // 이 구독에 대한 사용자의 과거 참여도(0.0~1.0). 콜드스타트(전달 이력 부족) 시 null
    val personalizationScore: Double?,
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
            relevanceScore = n.relevanceScore,
            personalizationScore = n.personalizationScore,
        )
    }
}

// nextCursor가 null이면 마지막 페이지. 다음 요청은 ?cursor=<nextCursor>로 이어 받는다.
data class NotificationHistoryPage(
    val items: List<NotificationHistoryItem>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

// 실시간 SSE는 "접속해 있는 동안 새로 온 것"만 보여주므로, 프런트엔드가 과거 알림 목록을
// 볼 수 있도록 별도로 조회 API를 둔다. 유저당 알림이 무한정 쌓이는 구조라 전체 목록을 한 번에
// 내려주지 않고 (createdAt, id) 커서 기반으로 최신순 한 페이지씩 끊어 준다.
@RestController
@RequestMapping("/api/notifications")
class NotificationHistoryController(
    private val notificationRepository: NotificationRepository,
) {

    @GetMapping
    @Transactional(readOnly = true)
    fun history(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(required = false) cursor: String?,
        // 기본 20건. defaultValue는 컴파일 상수만 받으므로 리터럴로 둔다 (MAX_LIMIT과 함께 관리).
        @RequestParam(defaultValue = "20") limit: Int,
    ): NotificationHistoryPage {
        val pageSize = limit.coerceIn(1, MAX_LIMIT)

        // hasMore 판정을 위해 한 건 더 읽고, 초과분은 응답에서 잘라낸다 (별도 count 쿼리 없이).
        val fetch = PageRequest.of(0, pageSize + 1)
        val rows = if (cursor == null) {
            notificationRepository.findHistoryFirstPage(principal.userId, fetch)
        } else {
            val (createdAt, id) = decodeCursor(cursor)
            notificationRepository.findHistoryAfter(principal.userId, createdAt, id, fetch)
        }

        val hasMore = rows.size > pageSize
        val page = if (hasMore) rows.subList(0, pageSize) else rows
        return NotificationHistoryPage(
            items = page.map(NotificationHistoryItem::from),
            nextCursor = if (hasMore) encodeCursor(page.last()) else null,
            hasMore = hasMore,
        )
    }

    private fun encodeCursor(n: Notification): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${n.createdAt}$CURSOR_SEP${n.id}".toByteArray())

    private fun decodeCursor(raw: String): Pair<Instant, Long> = try {
        val decoded = String(Base64.getUrlDecoder().decode(raw))
        val parts = decoded.split(CURSOR_SEP, limit = 2)
        require(parts.size == 2) { "커서 형식이 올바르지 않습니다" }
        Instant.parse(parts[0]) to parts[1].toLong()
    } catch (e: IllegalArgumentException) {
        // Base64 디코딩 실패, require 실패, id.toLong() NumberFormatException 포함
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 커서입니다", e)
    } catch (e: DateTimeParseException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 커서입니다", e)
    }

    private companion object {
        const val MAX_LIMIT = 100
        const val CURSOR_SEP = "|"
    }
}
