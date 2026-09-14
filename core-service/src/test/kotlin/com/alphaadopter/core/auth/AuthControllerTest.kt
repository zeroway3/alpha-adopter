package com.alphaadopter.core.auth

import com.alphaadopter.core.IntegrationTestBase
import com.alphaadopter.core.domain.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthControllerTest : IntegrationTestBase() {

    @Autowired
    lateinit var authController: AuthController

    @Autowired
    lateinit var userRepository: UserRepository

    @Test
    @Transactional
    fun `이메일과 비밀번호로 가입하면 access-refresh 쿠키를 받는다`() {
        val email = "signup-${System.nanoTime()}@example.com"

        val signupHttpResponse = MockHttpServletResponse()
        val signupResponse = authController.signup(SignupRequest(email = email, password = "password123"), signupHttpResponse)
        assertEquals(201, signupResponse.statusCode.value())
        assertTrue(signupResponse.body!!.isMember)
        assertTrue(userRepository.findByEmail(email) != null)
        assertNotNull(signupHttpResponse.getCookie(ACCESS_TOKEN_COOKIE))
        assertNotNull(signupHttpResponse.getCookie(REFRESH_TOKEN_COOKIE))

        val loginHttpResponse = MockHttpServletResponse()
        val loginResponse = authController.login(LoginRequest(email = email, password = "password123"), loginHttpResponse)
        assertEquals(email, loginResponse.email)
        // 로그인마다 새 refresh token을 발급해야 한다(재사용 금지)
        assertNotEquals(
            signupHttpResponse.getCookie(REFRESH_TOKEN_COOKIE)!!.value,
            loginHttpResponse.getCookie(REFRESH_TOKEN_COOKIE)!!.value,
        )
    }

    @Test
    @Transactional
    fun `refresh 토큰으로 재발급받으면 이전 토큰은 재사용할 수 없다`() {
        val email = "refresh-${System.nanoTime()}@example.com"
        val signupHttpResponse = MockHttpServletResponse()
        authController.signup(SignupRequest(email = email, password = "password123"), signupHttpResponse)
        val originalRefreshToken = signupHttpResponse.getCookie(REFRESH_TOKEN_COOKIE)!!.value

        val refreshHttpResponse = MockHttpServletResponse()
        val refreshResponse = authController.refresh(originalRefreshToken, refreshHttpResponse)
        assertEquals(email, refreshResponse.email)
        assertNotNull(refreshHttpResponse.getCookie(ACCESS_TOKEN_COOKIE))

        val ex = assertFailsWith<ResponseStatusException> {
            authController.refresh(originalRefreshToken, MockHttpServletResponse())
        }
        assertEquals(401, ex.statusCode.value())
    }

    @Test
    @Transactional
    fun `이미 가입된 이메일이면 409를 던진다`() {
        val email = "dup-${System.nanoTime()}@example.com"
        authController.signup(SignupRequest(email = email, password = "password123"), MockHttpServletResponse())

        val ex = assertFailsWith<ResponseStatusException> {
            authController.signup(SignupRequest(email = email, password = "password456"), MockHttpServletResponse())
        }
        assertEquals(409, ex.statusCode.value())
    }

    @Test
    @Transactional
    fun `비밀번호가 틀리면 401을 던진다`() {
        val email = "wrongpw-${System.nanoTime()}@example.com"
        authController.signup(SignupRequest(email = email, password = "password123"), MockHttpServletResponse())

        val ex = assertFailsWith<ResponseStatusException> {
            authController.login(LoginRequest(email = email, password = "wrong-password"), MockHttpServletResponse())
        }
        assertEquals(401, ex.statusCode.value())
    }
}
