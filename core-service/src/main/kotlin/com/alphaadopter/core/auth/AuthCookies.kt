package com.alphaadopter.core.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

const val ACCESS_TOKEN_COOKIE = "access_token"
const val REFRESH_TOKEN_COOKIE = "refresh_token"

// 로그인/회원가입/refresh 시 내려주는 access/refresh 쿠키를 한 곳에서 일관되게 구성한다.
// 둘 다 httpOnly라 JS에서 읽을 수 없고(XSS로 탈취 불가), SameSite=Strict + 프론트/백엔드가
// 같은 오리진에서 서빙되는 배포 구조(README 참고)라 별도 CSRF 토큰 없이도 크로스사이트 요청
// 위조를 막을 수 있다(SecurityConfig의 csrf disable 근거 참고).
@Component
class AuthCookies(
    @Value("\${app.jwt.access-validity-minutes:60}") private val accessValidityMinutes: Long,
    @Value("\${app.jwt.refresh-validity-days:30}") private val refreshValidityDays: Long,
    // 배포(HTTPS)에서는 true로 켜서 Secure 속성을 붙인다. 로컬(HTTP)에서는 false여야 브라우저가
    // 쿠키를 실제로 전송한다. 기본값은 false — 배포 환경이 실제로 TLS를 종단하는지 확인한 뒤
    // core-service-secrets(K8s Secret)에 JWT_COOKIE_SECURE=true로 켜야 한다.
    @Value("\${app.jwt.cookie-secure:false}") private val cookieSecure: Boolean,
) {
    fun access(token: String): ResponseCookie = build(ACCESS_TOKEN_COOKIE, token, "/", Duration.ofMinutes(accessValidityMinutes))

    // refresh 쿠키는 /api/auth 하위에서만 전송되도록 경로를 좁혀, 다른 모든 요청에 불필요하게
    // 실려 보내지는 노출 표면을 줄인다.
    fun refresh(token: String): ResponseCookie = build(REFRESH_TOKEN_COOKIE, token, "/api/auth", Duration.ofDays(refreshValidityDays))

    fun clearAccess(): ResponseCookie = build(ACCESS_TOKEN_COOKIE, "", "/", Duration.ZERO)

    fun clearRefresh(): ResponseCookie = build(REFRESH_TOKEN_COOKIE, "", "/api/auth", Duration.ZERO)

    private fun build(name: String, value: String, path: String, maxAge: Duration): ResponseCookie =
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Strict")
            .path(path)
            .maxAge(maxAge)
            .build()
}
