package com.alphaadopter.core.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

// Refresh token은 JWT가 아니라 고엔트로피 랜덤 값(opaque token)이다. JWT처럼 서명 검증만으로
// 유효성을 판단하는 대신 Redis에 저장해두고 매 refresh 요청마다 대조하므로, 로그아웃 시
// 즉시 무효화(revoke)할 수 있다 — RateLimitFilter/NewsDeduplicationService와 동일하게
// TTL이 있는 상태는 Redis에 두는 이 프로젝트의 컨벤션을 따른다.
// 값 자체는 탈취 시 그대로 재사용 가능하므로 평문이 아니라 SHA-256 해시로 저장한다
// (비밀번호처럼 느린 해시까지는 불필요 — 이미 256비트 랜덤이라 무차별 대입이 사실상 불가능).
@Component
class RefreshTokenService(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${app.jwt.refresh-validity-days:30}") private val validityDays: Long,
) {
    private val secureRandom = SecureRandom()

    fun issue(userId: Long): String {
        val token = generateToken()
        redisTemplate.opsForValue().set(key(token), userId.toString(), Duration.ofDays(validityDays))
        return token
    }

    // 유효하면 토큰을 즉시 폐기(1회용 rotation)하고 연결된 사용자 ID를 반환한다. 매 refresh마다
    // 새 토큰을 발급하고 이전 토큰은 재사용 불가능하게 만들어, 탈취된 토큰이 재사용될 경우
    // 정상 사용자의 다음 rotation이 실패하는 형태로 탈취 정황이 드러나게 한다.
    fun rotate(token: String): Long? {
        val userId = redisTemplate.opsForValue().get(key(token))?.toLongOrNull() ?: return null
        redisTemplate.delete(key(token))
        return userId
    }

    fun revoke(token: String) {
        redisTemplate.delete(key(token))
    }

    private fun key(token: String) = "auth:refresh:${sha256(token)}"

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
