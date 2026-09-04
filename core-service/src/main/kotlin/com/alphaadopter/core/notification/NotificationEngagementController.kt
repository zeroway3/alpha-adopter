package com.alphaadopter.core.notification

import com.alphaadopter.core.domain.notification.NotificationRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

// 참여도(읽음/클릭) 추적용 (docs/future-ideas.md 개인화 필터링 아이디어 참고). 실제 스코어링/필터링은 아직 없고 데이터만 쌓는다.
@RestController
@RequestMapping("/api/notifications/{id}")
class NotificationEngagementController(
    private val notificationRepository: NotificationRepository,
) {

    @PostMapping("/read")
    @Transactional
    fun markRead(@PathVariable id: Long): ResponseEntity<Void> {
        val notification = notificationRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 알림입니다: $id") }

        if (notification.readAt == null) {
            notification.readAt = Instant.now()
            notificationRepository.save(notification)
        }
        return ResponseEntity.noContent().build()
    }

    // 다이제스트 이메일의 기사 링크가 이 엔드포인트를 거쳐가도록 해서, 클릭 시 실제 기사로 리다이렉트하며 클릭 이벤트를 기록한다
    @GetMapping("/click")
    @Transactional
    fun trackClick(@PathVariable id: Long): ResponseEntity<Void> {
        val notification = notificationRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 알림입니다: $id") }

        val now = Instant.now()
        if (notification.clickedAt == null) {
            notification.clickedAt = now
        }
        if (notification.readAt == null) {
            notification.readAt = now
        }
        notificationRepository.save(notification)

        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(notification.newsArticle.link))
            .build()
    }
}
