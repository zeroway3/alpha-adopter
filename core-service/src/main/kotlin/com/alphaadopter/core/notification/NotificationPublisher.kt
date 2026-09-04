package com.alphaadopter.core.notification

import com.alphaadopter.core.pipeline.NewsMatchedMessage
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

// news.matched 컨슈머가 호출: 실제 SSE 전달은 이 인스턴스가 아니라 대상 사용자의 SSE 커넥션을 들고 있는 인스턴스가 담당할 수 있으므로 Redis로 릴레이만 한다.
@Component
class NotificationPublisher(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${app.notification.channel}") private val channel: String,
) {
    fun publish(message: NewsMatchedMessage) {
        redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(message))
    }
}
