package com.alphaadopter.core.auth

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

// 회원가입/로그인/구독 생성은 인증 전이거나(회원가입·로그인) 무차별 호출 시 비용이 큰
// (구독 생성 → NAVER API HUB 폴링 대상 증가) 엔드포인트라, IP 기준 고정 윈도우 카운터로 막는다.
// NewsDeduplicationService와 동일하게 Redis INCR+EXPIRE로 직접 구현 — 이 규모에서는 별도
// rate limiting 라이브러리(bucket4j 등)를 새로 끌어올 필요가 없다.
// Redis 장애 시에는 요청을 막지 않는다(fail-open) — AI 필터/중복 제거와 동일한 원칙으로,
// 부가 기능의 장애가 핵심 가입/로그인 흐름을 막는 장애점이 되면 안 된다.
@Component
class RateLimitFilter(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    @Value("\${app.rate-limit.enabled:true}") private val enabled: Boolean,
    @Value("\${app.rate-limit.auth.limit:10}") private val authLimit: Long,
    @Value("\${app.rate-limit.auth.window-seconds:60}") private val authWindowSeconds: Long,
    @Value("\${app.rate-limit.subscription.limit:30}") private val subscriptionLimit: Long,
    @Value("\${app.rate-limit.subscription.window-seconds:60}") private val subscriptionWindowSeconds: Long,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class Rule(val bucket: String, val limit: Long, val windowSeconds: Long)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val rule = if (enabled) ruleFor(request) else null
        if (rule == null) {
            filterChain.doFilter(request, response)
            return
        }

        val key = "ratelimit:${rule.bucket}:${clientIp(request)}"
        val allowed = runCatching { tryAcquire(key, rule.limit, rule.windowSeconds) }
            .getOrElse { e ->
                log.warn("Redis 오류로 rate limit 확인을 건너뜁니다({}): {}", rule.bucket, e.message)
                true
            }

        if (allowed) {
            meterRegistry.counter("rate_limit.request", "endpoint", rule.bucket, "result", "allowed").increment()
            filterChain.doFilter(request, response)
        } else {
            meterRegistry.counter("rate_limit.request", "endpoint", rule.bucket, "result", "rejected").increment()
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.setHeader("Retry-After", rule.windowSeconds.toString())
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            response.writer.write("""{"message":"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."}""")
        }
    }

    private fun ruleFor(request: HttpServletRequest): Rule? = when {
        request.method != "POST" -> null
        request.requestURI == "/api/auth/signup" -> Rule("auth:signup", authLimit, authWindowSeconds)
        request.requestURI == "/api/auth/login" -> Rule("auth:login", authLimit, authWindowSeconds)
        request.requestURI == "/api/subscriptions" -> Rule("subscription:create", subscriptionLimit, subscriptionWindowSeconds)
        else -> null
    }

    private fun tryAcquire(key: String, limit: Long, windowSeconds: Long): Boolean {
        val count = redisTemplate.opsForValue().increment(key) ?: 1L
        if (count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(windowSeconds))
        }
        return count <= limit
    }

    // EKS 배포 환경에서는 ALB를 거치므로 실제 클라이언트 IP는 X-Forwarded-For 첫 번째 값이다.
    // 로컬/직결 환경(헤더 없음)에서는 remoteAddr을 그대로 사용한다.
    private fun clientIp(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) {
            return forwarded.split(",").first().trim()
        }
        return request.remoteAddr
    }
}
