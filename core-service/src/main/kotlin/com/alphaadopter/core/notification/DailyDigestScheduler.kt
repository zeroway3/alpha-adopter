package com.alphaadopter.core.notification

import com.alphaadopter.core.domain.notification.NotificationRepository
import com.alphaadopter.core.domain.notification.NotificationStatus
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// 비회원은 실시간 SSE 대신 매일 아침 다이제스트로 알림을 받는다 (docs/future-ideas.md)
@Component
class DailyDigestScheduler(
    private val notificationRepository: NotificationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${app.notification.digest-cron}")
    @Transactional
    fun sendDigest() {
        val pending = notificationRepository.findAllByStatusAndSubscriptionUserIsMember(NotificationStatus.MATCHED, false)
        if (pending.isEmpty()) {
            log.info("발송할 일일 다이제스트가 없습니다.")
            return
        }

        pending.groupBy { it.subscription.user }.forEach { (user, notifications) ->
            val articles = notifications.map { it.newsArticle }
            log.info(
                "[일일 다이제스트] {} 님에게 {}건 발송: {}",
                user.email,
                articles.size,
                articles.joinToString { "${it.title} (${it.link})" },
            )
        }

        val now = Instant.now()
        pending.forEach { notification ->
            notification.status = NotificationStatus.SENT
            notification.sentAt = now
        }
        notificationRepository.saveAll(pending)
    }
}
