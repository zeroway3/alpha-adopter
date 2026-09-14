package com.alphaadopter.core.auth

import com.alphaadopter.core.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

// SubscriptionKeywordCapTest 등 기존 테스트는 컨트롤러 빈을 직접 호출해 필터 체인을 우회하므로,
// RateLimitFilter가 실제로 동작하는지 검증하려면 진짜 HTTP 요청이 필요하다. 별도 HTTP 클라이언트
// 의존성을 추가하지 않기 위해 JDK 표준 java.net.http.HttpClient를 사용한다.
@TestPropertySource(properties = ["app.rate-limit.auth.limit=3", "app.rate-limit.auth.window-seconds=60"])
class RateLimitFilterTest : IntegrationTestBase() {

    @LocalServerPort
    var port: Int = 0

    private val client = HttpClient.newHttpClient()

    @Test
    fun `로그인 요청이 한도를 넘으면 429를 반환한다`() {
        val body = """{"email":"nobody-${System.nanoTime()}@example.com","password":"wrongpassword"}"""

        val statuses = (1..5).map { login(body) }

        // 앞 3건은 한도 이내라 정상적으로 컨트롤러까지 도달해 401(잘못된 로그인), 이후는 429
        assertEquals(listOf(401, 401, 401, 429, 429), statuses)
    }

    private fun login(body: String): Int {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }
}
