package com.alphaadopter.core.config

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaTopicConfig {

    @Value("\${app.kafka.topic.news-raw}")
    lateinit var newsRawTopic: String

    @Value("\${app.kafka.topic.news-matched}")
    lateinit var newsMatchedTopic: String

    @Bean
    fun newsRawTopicBean(): NewTopic = TopicBuilder.name(newsRawTopic).partitions(3).replicas(1).build()

    @Bean
    fun newsMatchedTopicBean(): NewTopic = TopicBuilder.name(newsMatchedTopic).partitions(3).replicas(1).build()
}
