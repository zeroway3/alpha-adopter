package com.alphaadopter.core.notification

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer

@Configuration
class RedisConfig {

    @Bean
    fun notificationTopic(@Value("\${app.notification.channel}") channel: String): ChannelTopic = ChannelTopic(channel)

    @Bean
    fun redisMessageListenerContainer(
        connectionFactory: RedisConnectionFactory,
        subscriber: NotificationSubscriber,
        notificationTopic: ChannelTopic,
    ): RedisMessageListenerContainer =
        RedisMessageListenerContainer().apply {
            setConnectionFactory(connectionFactory)
            addMessageListener(subscriber, notificationTopic)
        }
}
