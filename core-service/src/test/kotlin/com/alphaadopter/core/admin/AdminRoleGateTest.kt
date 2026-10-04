package com.alphaadopter.core.admin

import com.alphaadopter.core.IntegrationTestBase
import com.alphaadopter.core.domain.user.UserRepository
import com.alphaadopter.core.domain.user.UserRole
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

// AdminAccessControlTest는 컨트롤러 빈을 직접 호출해 필터 체인(SecurityConfig의
// hasRole("ADMIN") 게이트)을 우회한다. 이 테스트는 실제 HTTP 요청으로 JWT의 role 클레임 →
// JwtAuthFilter의 GrantedAuthority → SecurityConfig의 hasRole 판정까지 전체 경로가
// 동작하는지 검증한다.
class AdminRoleGateTest : IntegrationTestBase() {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var userRepository: UserRepository

    private fun client(): HttpClient = HttpClient.newBuilder().cookieHandler(CookieManager()).build()

    private fun url(path: String) = URI.create("http://localhost:$port$path")

    @Test
    fun `USER role은 게이트웨이 단계에서 차단되고, ADMIN으로 승격 후 refresh하면 접근할 수 있다`() {
        val client = client()
        val email = "role-gate-${System.nanoTime()}@example.com"

        val signupRes = client.send(
            HttpRequest.newBuilder(url("/api/auth/signup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"email":"$email","password":"password123"}"""))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(201, signupRes.statusCode())

        val blockedRes = client.send(
            HttpRequest.newBuilder(url("/api/admin/stats")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(403, blockedRes.statusCode())

        val user = userRepository.findByEmail(email)!!
        user.role = UserRole.ADMIN
        userRepository.save(user)

        // 이미 발급된 access token엔 예전 role(USER)이 담겨있으므로, 승격된 role을 반영하려면
        // refresh로 새 토큰을 받아야 한다 — role 변경이 access token 유효기간(기본 60분) 안에서는
        // 즉시 반영되지 않을 수 있다는 트레이드오프를 보여준다.
        val refreshRes = client.send(
            HttpRequest.newBuilder(url("/api/auth/refresh")).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(200, refreshRes.statusCode())

        val allowedRes = client.send(
            HttpRequest.newBuilder(url("/api/admin/stats")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(200, allowedRes.statusCode())
    }
}
