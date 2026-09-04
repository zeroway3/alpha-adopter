package com.alphaadopter.core.pipeline

import com.alphaadopter.core.notification.NotificationPublisher
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

// news.matched 컨슈머: 매칭 로직은 NewsRawConsumer에서 이미 끝났으므로, 여기서는 알림 전달 계층(Redis)으로 릴레이만 한다.
@Component
class NewsMatchedConsumer(
    private val notificationPublisher: NotificationPublisher,
) {

    @KafkaListener(topics = ["\${app.kafka.topic.news-matched}"], groupId = "core-service-notification-consumer")
    fun consume(message: NewsMatchedMessage) {
        notificationPublisher.publish(message)
    }
}
