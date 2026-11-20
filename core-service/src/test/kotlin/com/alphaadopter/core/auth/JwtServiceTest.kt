package com.alphaadopter.core.auth

import com.alphaadopter.core.domain.user.UserRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

// 서명 키가 빠졌거나 약할 때 조용히 동작하지 않고 기동 시점에 실패하는지, 다른 키로 서명된 토큰을
// 거부하는지 확인한다.
class JwtServiceTest {

    private val secret = "a".repeat(32)

    @Test
    fun `서명 키가 없거나 32바이트보다 짧으면 생성 시점에 실패한다`() {
        assertFailsWith<IllegalArgumentException> { JwtService("", 60) }
        assertFailsWith<IllegalArgumentException> { JwtService("short-secret", 60) }
    }

    @Test
    fun `발급한 토큰을 같은 키로 해석하면 사용자 정보와 역할이 복원된다`() {
        val service = JwtService(secret, 60)

        val principal = service.parse(service.generate(7L, "user@example.com", UserRole.ADMIN))

        assertEquals(AuthPrincipal(userId = 7L, email = "user@example.com", role = UserRole.ADMIN), principal)
    }

    @Test
    fun `다른 키로 서명된 토큰은 거부한다`() {
        val forged = JwtService("b".repeat(32), 60).generate(1L, "attacker@example.com", UserRole.ADMIN)

        assertNull(JwtService(secret, 60).parse(forged))
    }
}
