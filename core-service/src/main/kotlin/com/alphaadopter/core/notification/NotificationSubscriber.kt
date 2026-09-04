package com.alphaadopter.core.notification

import com.alphaadopter.core.domain.notification.NotificationRepository
import com.alphaadopter.core.domain.notification.NotificationStatus
import com.alphaadopter.core.pipeline.NewsMatchedMessage
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.ObjectMapper
import java.time.Instant

// 모든 인스턴스가 이 채널을 구독한다. 대상 사용자의 SSE 커넥션을 로컬에 들고 있는 인스턴스만 실제로 전달한다.
@Component
class NotificationSubscriber(
    private val sseEmitterRegistry: SseEmitterRegistry,
    private val notificationRepository: NotificationRepository,
    private val objectMapper: ObjectMapper,
) : MessageListener {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onMessage(message: Message, pattern: ByteArray?) {
        val matched = objectMapper.readValue(String(message.body), NewsMatchedMessage::class.java)
        val emitters = sseEmitterRegistry.emittersFor(matched.userId)
        if (emitters.isEmpty()) {
            return
        }

        var delivered = false
        emitters.forEach { emitter ->
            runCatching {
                emitter.send(SseEmitter.event().name("news-matched").data(matched))
                delivered = true
            }.onFailure {
                sseEmitterRegistry.remove(matched.userId, emitter)
            }
        }

        if (delivered) {
            markSent(matched.notificationId)
        }
    }

    @Transactional
    fun markSent(notificationId: Long) {
        notificationRepository.findById(notificationId).ifPresentOrElse(
            { notification ->
                notification.status = NotificationStatus.SENT
                notification.sentAt = Instant.now()
                notificationRepository.save(notification)
            },
            { log.warn("전달 처리할 알림을 찾을 수 없습니다: {}", notificationId) },
        )
    }
}
