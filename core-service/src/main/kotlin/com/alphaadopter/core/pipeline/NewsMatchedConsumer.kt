package com.alphaadopter.core.pipeline

import com.alphaadopter.core.notification.NotificationPublisher
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

// news.matched 컨슈머: 매칭 로직은 NewsRawConsumer에서 이미 끝났으므로, 여기서는 알림 전달 계층(Redis)으로 릴레이만 한다.
//
// 릴레이 시점에 알림 지연을 두 구간으로 나눠 계측한다.
// - 기사 게시 → 전달: 사용자가 체감하는 지연. 수집 주기와 검색 API 반영 지연까지 포함한다.
// - 수집 → 전달: 우리 파이프라인(Kafka·매칭·AI 판단·저장) 자체의 처리 시간.
// 둘을 나눠야 "느리다"가 수집 주기 탓인지 파이프라인 탓인지 구분할 수 있다.
@Component
class NewsMatchedConsumer(
    private val notificationPublisher: NotificationPublisher,
    meterRegistry: MeterRegistry,
) {
    private val publishedToDelivery = Timer.builder("notification.latency")
        .tag("from", "published")
        .publishPercentileHistogram()
        .register(meterRegistry)
    private val collectedToDelivery = Timer.builder("notification.latency")
        .tag("from", "collected")
        .publishPercentileHistogram()
        .register(meterRegistry)

    @KafkaListener(topics = ["\${app.kafka.topic.news-matched}"], groupId = "core-service-notification-consumer")
    fun consume(message: NewsMatchedMessage) {
        notificationPublisher.publish(message)

        val now = Instant.now()
        message.publishedAt?.let { record(publishedToDelivery, it, now) }
        message.collectedAt?.let { record(collectedToDelivery, it, now) }
    }

    // 언론사 게시 시각은 분 단위라 수집 직후 기사는 음수가 될 수 있어 0으로 자른다
    private fun record(timer: Timer, from: Instant, now: Instant) {
        timer.record(Duration.between(from, now).coerceAtLeast(Duration.ZERO))
    }
}
