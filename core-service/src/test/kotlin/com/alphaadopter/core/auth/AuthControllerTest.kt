package com.alphaadopter.core.auth

import com.alphaadopter.core.domain.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
class AuthControllerTest {

    @Autowired
    lateinit var authController: AuthController

    @Autowired
    lateinit var userRepository: UserRepository

    @Test
    @Transactional
    fun `이메일과 비밀번호로 가입하면 로그인 가능한 토큰을 받는다`() {
        val email = "signup-${System.nanoTime()}@example.com"

        val signupResponse = authController.signup(SignupRequest(email = email, password = "password123"))
        assertEquals(201, signupResponse.statusCode.value())
        assertTrue(signupResponse.body!!.isMember)
        assertTrue(userRepository.findByEmail(email) != null)

        val loginResponse = authController.login(LoginRequest(email = email, password = "password123"))
        assertEquals(email, loginResponse.email)
    }

    @Test
    @Transactional
    fun `이미 가입된 이메일이면 409를 던진다`() {
        val email = "dup-${System.nanoTime()}@example.com"
        authController.signup(SignupRequest(email = email, password = "password123"))

        val ex = assertFailsWith<ResponseStatusException> {
            authController.signup(SignupRequest(email = email, password = "password456"))
        }
        assertEquals(409, ex.statusCode.value())
    }

    @Test
    @Transactional
    fun `비밀번호가 틀리면 401을 던진다`() {
        val email = "wrongpw-${System.nanoTime()}@example.com"
        authController.signup(SignupRequest(email = email, password = "password123"))

        val ex = assertFailsWith<ResponseStatusException> {
            authController.login(LoginRequest(email = email, password = "wrong-password"))
        }
        assertEquals(401, ex.statusCode.value())
    }
}
