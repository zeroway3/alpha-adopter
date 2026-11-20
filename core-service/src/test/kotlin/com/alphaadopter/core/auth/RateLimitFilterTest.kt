package com.alphaadopter.core.auth

import com.alphaadopter.core.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.data.redis.core.StringRedisTemplate
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

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    private val client = HttpClient.newHttpClient()

    // 모든 요청이 같은 연결 주소(localhost)라 테스트끼리 한도 카운터를 공유한다 — 테스트마다 비운다
    @BeforeEach
    fun resetLoginCounter() {
        redisTemplate.keys("ratelimit:auth:login:*").forEach { redisTemplate.delete(it) }
    }

    @Test
    fun `로그인 요청이 한도를 넘으면 429를 반환한다`() {
        val body = """{"email":"nobody-${System.nanoTime()}@example.com","password":"wrongpassword"}"""

        val statuses = (1..5).map { login(body) }

        // 앞 3건은 한도 이내라 정상적으로 컨트롤러까지 도달해 401(잘못된 로그인), 이후는 429
        assertEquals(listOf(401, 401, 401, 429, 429), statuses)
    }

    @Test
    fun `X-Forwarded-For를 요청마다 바꿔 보내도 한도를 우회할 수 없다`() {
        val body = """{"email":"spoof-${System.nanoTime()}@example.com","password":"wrongpassword"}"""

        // 공격자가 매 요청 다른 IP를 X-Forwarded-For로 주장하는 상황. 신뢰할 프록시가 설정돼 있지 않으면
        // 헤더를 무시하고 실제 연결 주소로 세므로 똑같이 막혀야 한다.
        val statuses = (1..5).map { i -> login(body, forwardedFor = "203.0.113.$i") }

        assertEquals(listOf(401, 401, 401, 429, 429), statuses)
    }

    @Test
    fun `신뢰할 프록시 단계 수만큼 오른쪽에서 센 값을 클라이언트 IP로 본다`() {
        // 프록시 없음: 헤더 무시
        assertEquals("10.0.0.5", RateLimitFilter.resolveClientIp("10.0.0.5", "1.1.1.1, 2.2.2.2", 0))
        // ALB 한 단계: 클라이언트가 위조한 앞쪽 값이 아니라 ALB가 덧붙인 맨 오른쪽 값
        assertEquals("2.2.2.2", RateLimitFilter.resolveClientIp("10.0.0.5", "1.1.1.1, 2.2.2.2", 1))
        // 헤더가 기대보다 짧으면 연결 주소로 대체
        assertEquals("10.0.0.5", RateLimitFilter.resolveClientIp("10.0.0.5", "2.2.2.2", 2))
    }

    private fun login(body: String, forwardedFor: String? = null): Int {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/auth/login"))
            .header("Content-Type", "application/json")
            .apply { forwardedFor?.let { header("X-Forwarded-For", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }
}
