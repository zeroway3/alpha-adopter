package com.alphaadopter.core.admin

import com.alphaadopter.core.IntegrationTestBase
import com.alphaadopter.core.auth.AuthPrincipal
import com.alphaadopter.core.domain.user.UserRole
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// AuthPrincipal.role(JWT 클레임에서 복원)이 실제로 컨트롤러 접근 제어(requireAdmin)에
// 반영되는지 검증. SecurityConfig의 hasRole("ADMIN") 게이트웨이 규칙까지 함께 검증하려면
// 필터 체인을 타는 실제 HTTP 테스트가 필요한데, 그건 AdminRoleGateTest가 담당한다.
class AdminAccessControlTest : IntegrationTestBase() {

    @Autowired
    lateinit var adminStatsController: AdminStatsController

    @Test
    fun `일반 회원(USER role)은 관리자 통계에 접근할 수 없다`() {
        val nonAdmin = AuthPrincipal(userId = 1L, email = "not-admin@example.com", role = UserRole.USER)

        val ex = assertFailsWith<ResponseStatusException> { adminStatsController.stats(nonAdmin) }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)

        assertFailsWith<ResponseStatusException> { adminStatsController.users(nonAdmin) }
        assertFailsWith<ResponseStatusException> { adminStatsController.keywords(nonAdmin) }
        assertFailsWith<ResponseStatusException> { adminStatsController.dailyStats(nonAdmin) }
    }

    @Test
    fun `ADMIN role을 가진 사용자는 관리자 통계에 접근할 수 있다`() {
        val admin = AuthPrincipal(userId = 2L, email = "admin@example.com", role = UserRole.ADMIN)

        val stats = adminStatsController.stats(admin)
        assertEquals(true, stats.totalUsers >= 0)
    }
}
