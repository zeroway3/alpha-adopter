package com.alphaadopter.core.admin

import com.alphaadopter.core.IntegrationTestBase
import com.alphaadopter.core.auth.AuthPrincipal
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// AdminEmailChecker의 화이트리스트 판단이 실제로 컨트롤러 접근 제어에 반영되는지 검증.
// DB 플래그가 아니라 배포 환경변수(app.admin.emails)로 관리자를 가른다는 설계의 핵심이라
// 회귀가 생기면 관리자 데이터가 그대로 노출될 수 있는 부분 — 반드시 테스트로 고정해둔다.
@TestPropertySource(properties = ["app.admin.emails=admin-fixed@example.com"])
class AdminAccessControlTest : IntegrationTestBase() {

    @Autowired
    lateinit var adminStatsController: AdminStatsController

    @Test
    fun `화이트리스트에 없는 사용자는 관리자 통계에 접근할 수 없다`() {
        val nonAdmin = AuthPrincipal(userId = 1L, email = "not-admin@example.com")

        val ex = assertFailsWith<ResponseStatusException> { adminStatsController.stats(nonAdmin) }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)

        assertFailsWith<ResponseStatusException> { adminStatsController.users(nonAdmin) }
        assertFailsWith<ResponseStatusException> { adminStatsController.keywords(nonAdmin) }
        assertFailsWith<ResponseStatusException> { adminStatsController.dailyStats(nonAdmin) }
    }

    @Test
    fun `화이트리스트에 등록된 이메일은 관리자 통계에 접근할 수 있다`() {
        val admin = AuthPrincipal(userId = 2L, email = "admin-fixed@example.com")

        val stats = adminStatsController.stats(admin)
        assertEquals(true, stats.totalUsers >= 0)
    }
}
