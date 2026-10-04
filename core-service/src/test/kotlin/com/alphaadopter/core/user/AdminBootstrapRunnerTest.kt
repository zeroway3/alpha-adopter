package com.alphaadopter.core.user

import com.alphaadopter.core.domain.user.AdminAuditLog
import com.alphaadopter.core.domain.user.AdminAuditLogRepository
import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import com.alphaadopter.core.domain.user.UserRole
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.boot.DefaultApplicationArguments
import kotlin.test.assertEquals

// ApplicationRunner는 Spring 컨텍스트 기동 시점에 딱 한 번 실행되기 때문에, 무거운
// IntegrationTestBase(Testcontainers) 없이 run()을 직접 호출해 로직만 검증한다.
class AdminBootstrapRunnerTest {

    @Test
    fun `app_admin_emails에 있는 이메일은 대소문자가 달라도 ADMIN으로 승격되고 감사 로그가 남는다`() {
        val userRepository = Mockito.mock(UserRepository::class.java)
        val auditLogRepository = Mockito.mock(AdminAuditLogRepository::class.java)
        val user = User(email = "Admin@Example.com")
        user.id = 42L
        Mockito.`when`(userRepository.findByEmailIgnoreCase("admin@example.com")).thenReturn(user)

        AdminBootstrapRunner(userRepository, auditLogRepository, " admin@example.com ,").run(DefaultApplicationArguments())

        assertEquals(UserRole.ADMIN, user.role)
        Mockito.verify(userRepository).save(user)

        val captor = ArgumentCaptor.forClass(AdminAuditLog::class.java)
        Mockito.verify(auditLogRepository).save(captor.capture())
        val logged = captor.value
        assertEquals(42L, logged.targetUserId)
        assertEquals("Admin@Example.com", logged.targetEmail)
        assertEquals("USER", logged.previousRole)
        assertEquals("ADMIN", logged.newRole)
        assertEquals("AdminBootstrapRunner", logged.changedBy)
    }

    @Test
    fun `이미 ADMIN인 사용자는 다시 저장하지 않고 감사 로그도 남기지 않는다`() {
        val userRepository = Mockito.mock(UserRepository::class.java)
        val auditLogRepository = Mockito.mock(AdminAuditLogRepository::class.java)
        val user = User(email = "admin@example.com", role = UserRole.ADMIN)
        Mockito.`when`(userRepository.findByEmailIgnoreCase("admin@example.com")).thenReturn(user)

        AdminBootstrapRunner(userRepository, auditLogRepository, "admin@example.com").run(DefaultApplicationArguments())

        Mockito.verify(userRepository, Mockito.never()).save(any())
        Mockito.verify(auditLogRepository, Mockito.never()).save(any())
    }

    @Test
    fun `목록에 없거나 가입 안 된 이메일은 조용히 건너뛴다`() {
        val userRepository = Mockito.mock(UserRepository::class.java)
        val auditLogRepository = Mockito.mock(AdminAuditLogRepository::class.java)
        Mockito.`when`(userRepository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(null)

        AdminBootstrapRunner(userRepository, auditLogRepository, "nobody@example.com").run(DefaultApplicationArguments())

        Mockito.verify(userRepository, Mockito.never()).save(any())
        Mockito.verify(auditLogRepository, Mockito.never()).save(any())
    }
}
