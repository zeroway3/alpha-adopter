package com.alphaadopter.core.config

import com.alphaadopter.core.admin.AdminStatsResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair
import tools.jackson.databind.ObjectMapper
import java.time.Duration

// /api/admin/stats는 호출마다 집계 쿼리를 8개 넘게 새로 돈다. 관리자 대시보드 새로고침 정도의
// 저빈도 트래픽이고 30초쯤 지난 수치는 문제되지 않으므로, 결과 전체를 Redis에 짧게 캐싱한다
// (AdminStatsService.snapshot). 캐시는 Pub/Sub(SSE)에 이미 쓰는 ElastiCache를 공유하고, HPA로
// 파드가 늘어도 파드마다 따로 계산하지 않도록(새로고침마다 수치가 튀지 않도록) 인메모리가 아닌
// Redis에 둔다.
@Configuration
@EnableCaching
class CacheConfig {

    // Spring Boot의 RedisCache 오토컨피그가 이 빈을 모든 캐시의 기본 설정으로 사용한다. 지금은
    // adminStats 캐시 하나뿐이라 "기본값 = adminStats 설정"으로 둔다. 다른 타입을 담는 캐시를
    // 추가한다면 캐시별 설정(RedisCacheManagerBuilderCustomizer 등)으로 분리해야 한다.
    @Bean
    fun redisCacheConfiguration(
        objectMapper: ObjectMapper,
        @Value("\${app.admin.stats-cache-ttl-seconds:30}") ttlSeconds: Long,
    ): RedisCacheConfiguration = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofSeconds(ttlSeconds))
        .disableCachingNullValues()
        // adminStats에는 AdminStatsResponse 한 종류만 담기므로 타입 힌트 없는(깔끔한 JSON) 타입
        // 고정 직렬화기를 쓴다. 앱이 설정한 ObjectMapper를 그대로 재사용해 Instant/LocalDate가
        // REST 응답과 동일하게 직렬화되게 한다.
        .serializeValuesWith(
            SerializationPair.fromSerializer(
                JacksonJsonRedisSerializer(objectMapper, AdminStatsResponse::class.java),
            ),
        )

    companion object {
        const val ADMIN_STATS_CACHE = "adminStats"
    }
}
