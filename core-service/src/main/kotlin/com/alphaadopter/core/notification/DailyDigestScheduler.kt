package com.alphaadopter.core.notification

import com.alphaadopter.core.domain.news.NewsArticle
import com.alphaadopter.core.domain.notification.Notification
import com.alphaadopter.core.domain.notification.NotificationRepository
import com.alphaadopter.core.domain.notification.NotificationStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// 비회원은 실시간 SSE 대신 매일 아침 다이제스트 이메일로 알림을 받는다 (docs/future-ideas.md)
@Component
class DailyDigestScheduler(
    private val notificationRepository: NotificationRepository,
    private val mailSender: JavaMailSender,
    @Value("\${app.notification.digest-from-address}") private val fromAddress: String,
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

        // 메일 발송이 실패한 사용자의 알림은 SENT로 바꾸지 않고 다음 주기에 재시도한다
        val delivered = mutableListOf<Notification>()
        pending.groupBy { it.subscription.user }.forEach { (user, notifications) ->
            val articles = notifications.map { it.newsArticle }
            runCatching {
                mailSender.send(buildDigestMail(user.email, articles))
            }.onSuccess {
                log.info("[일일 다이제스트] {} 님에게 {}건 발송", user.email, articles.size)
                delivered += notifications
            }.onFailure { e ->
                log.warn("{} 님에게 다이제스트 메일 발송 실패, 다음 주기에 재시도합니다: {}", user.email, e.message)
            }
        }

        val now = Instant.now()
        delivered.forEach { notification ->
            notification.status = NotificationStatus.SENT
            notification.sentAt = now
        }
        notificationRepository.saveAll(delivered)
    }

    private fun buildDigestMail(to: String, articles: List<NewsArticle>): SimpleMailMessage =
        SimpleMailMessage().apply {
            setFrom(fromAddress)
            setTo(to)
            subject = "[AlphaAdopter] 오늘의 뉴스 다이제스트 (${articles.size}건)"
            text = articles.joinToString("\n\n") { "- ${it.title}\n  ${it.link}" }
        }
}
