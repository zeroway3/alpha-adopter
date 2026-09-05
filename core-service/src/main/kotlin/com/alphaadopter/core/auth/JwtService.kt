package com.alphaadopter.core.auth

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
) {
    // HS256은 최소 256비트(32바이트) 키가 필요 — 로컬 기본값도 그 길이를 맞춰둔다 (application.yml 참고)
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))
    private val validity: Duration = Duration.ofDays(7)

    fun generate(userId: Long, email: String): String {
        val now = Date()
        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .issuedAt(now)
            .expiration(Date(now.time + validity.toMillis()))
            .signWith(key)
            .compact()
    }

    fun parse(token: String): AuthPrincipal? = runCatching {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        AuthPrincipal(userId = claims.subject.toLong(), email = claims["email"] as String)
    }.getOrNull()
}
