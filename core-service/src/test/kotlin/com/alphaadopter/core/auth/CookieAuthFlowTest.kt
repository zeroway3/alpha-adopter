package com.alphaadopter.core.auth

import com.alphaadopter.core.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// AuthControllerTest는 컨트롤러 빈을 직접 호출해 필터 체인을 우회하므로, access_token/
// refresh_token httpOnly 쿠키가 실제로 왕복하는지(JwtAuthFilter의 쿠키 파싱, SecurityConfig의
// permitAll 라우팅 포함)는 진짜 HTTP 요청으로만 검증할 수 있다. java.net.http.HttpClient의
// CookieManager가 브라우저처럼 Set-Cookie를 저장했다가 다음 요청에 자동으로 실어준다.
class CookieAuthFlowTest : IntegrationTestBase() {

    @LocalServerPort
    var port: Int = 0

    private fun newClientWithCookies(): HttpClient =
        HttpClient.newBuilder().cookieHandler(CookieManager()).build()

    private fun baseUrl(path: String) = URI.create("http://localhost:$port$path")

    @Test
    fun `로그인 후 me로 세션을 확인하고, refresh로 재발급받고, logout하면 세션이 끊긴다`() {
        val client = newClientWithCookies()
        val email = "cookie-flow-${System.nanoTime()}@example.com"

        val signupBody = """{"email":"$email","password":"password123"}"""
        val signupRes = client.send(
            HttpRequest.newBuilder(baseUrl("/api/auth/signup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(signupBody))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(201, signupRes.statusCode())
        assertTrue(signupRes.body().contains(email))

        // 쿠키는 CookieManager가 자동으로 실어 보낸다 — 헤더를 직접 다루지 않는다
        val meRes = client.send(
            HttpRequest.newBuilder(baseUrl("/api/auth/me")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, meRes.statusCode())
        assertTrue(meRes.body().contains(email))

        val refreshRes = client.send(
            HttpRequest.newBuilder(baseUrl("/api/auth/refresh")).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, refreshRes.statusCode())
        assertTrue(refreshRes.body().contains(email))

        val logoutRes = client.send(
            HttpRequest.newBuilder(baseUrl("/api/auth/logout")).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(204, logoutRes.statusCode())

        // logout이 쿠키를 지웠으므로(Max-Age=0), 이후 /me는 인증되지 않은 요청. Spring
        // Security의 STATELESS+익명 인증 기본 설정상, 인증 자체가 없는 요청은 403(Forbidden)으로
        // 응답한다(401 AuthenticationEntryPoint가 아니라 AccessDeniedHandler 경로) — 이 프로젝트의
        // 다른 인증 필요 API도 동일하게 동작한다.
        val meAfterLogoutRes = client.send(
            HttpRequest.newBuilder(baseUrl("/api/auth/me")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(403, meAfterLogoutRes.statusCode())
    }

    @Test
    fun `쿠키 없이 me를 호출하면 403을 받는다`() {
        val client = newClientWithCookies()
        val res = client.send(
            HttpRequest.newBuilder(baseUrl("/api/auth/me")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(403, res.statusCode())
    }
}
