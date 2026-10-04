package com.alphaadopter.core.auth

import com.alphaadopter.core.domain.user.UserRole
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtService(
    @Value("\${app.jwt.secret}") secret: String,
    // 예전엔 refresh token이 없어 access token 하나로 7일을 버텨야 했다. 지금은 만료돼도
    // RefreshTokenService로 재발급받으므로 탈취 시 노출 시간을 짧게 유지할 수 있다.
    @Value("\${app.jwt.access-validity-minutes:60}") accessValidityMinutes: Long,
) {
    // HS256은 최소 256비트(32바이트) 키가 필요 — 로컬 기본값도 그 길이를 맞춰둔다 (application.yml 참고)
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))
    private val validity: Duration = Duration.ofMinutes(accessValidityMinutes)

    fun generate(userId: Long, email: String, role: UserRole): String {
        val now = Date()
        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .claim("role", role.name)
            .issuedAt(now)
            .expiration(Date(now.time + validity.toMillis()))
            .signWith(key)
            .compact()
    }

    fun parse(token: String): AuthPrincipal? = runCatching {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        // role 클레임이 없는 토큰(이 필드 도입 이전에 발급된 것)은 안전하게 USER로 취급한다 —
        // 권한 상승 방향의 기본값이 아니라 최소 권한 방향의 기본값을 택한다.
        val role = (claims["role"] as? String)?.let { runCatching { UserRole.valueOf(it) }.getOrNull() } ?: UserRole.USER
        AuthPrincipal(userId = claims.subject.toLong(), email = claims["email"] as String, role = role)
    }.getOrNull()
}
