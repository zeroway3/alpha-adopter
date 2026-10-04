package com.alphaadopter.core.admin

import com.alphaadopter.core.IntegrationTestBase
import com.alphaadopter.core.auth.AuthPrincipal
import com.alphaadopter.core.domain.user.AdminAuditLog
import com.alphaadopter.core.domain.user.AdminAuditLogRepository
import com.alphaadopter.core.domain.user.UserRole
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// AdminBootstrapRunner가 실제로 남기는 감사 로그를 조회 API가 올바르게 돌려주는지 검증.
// 부트스트랩은 기동 시 한 번만 실행되므로, 여기서는 레코드를 직접 저장해 조회/접근 제어만 검증한다.
class AdminAuditLogControllerTest : IntegrationTestBase() {

    @Autowired
    lateinit var adminStatsController: AdminStatsController

    @Autowired
    lateinit var adminAuditLogRepository: AdminAuditLogRepository

    @Test
    @Transactional
    fun `감사 로그는 최신순으로 조회되고 비관리자는 접근할 수 없다`() {
        val email = "audit-${System.nanoTime()}@example.com"
        adminAuditLogRepository.save(
            AdminAuditLog(
                targetUserId = 1L,
                targetEmail = email,
                previousRole = "USER",
                newRole = "ADMIN",
                changedBy = "AdminBootstrapRunner",
                reason = "app.admin.emails 부트스트랩",
            ),
        )

        val admin = AuthPrincipal(userId = 99L, email = "admin@example.com", role = UserRole.ADMIN)
        val logs = adminStatsController.auditLogs(admin)
        assertTrue(logs.any { it.targetEmail == email && it.newRole == "ADMIN" })

        val nonAdmin = AuthPrincipal(userId = 1L, email = "not-admin@example.com", role = UserRole.USER)
        val ex = assertFailsWith<ResponseStatusException> { adminStatsController.auditLogs(nonAdmin) }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
