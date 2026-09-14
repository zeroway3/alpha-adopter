package com.alphaadopter.core.auth

import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import com.alphaadopter.core.user.AdminEmailChecker
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class SignupRequest(
    @field:NotBlank @field:Email
    val email: String,
    @field:NotBlank @field:Size(min = 8, max = 100, message = "비밀번호는 8자 이상이어야 합니다.")
    val password: String,
)

data class LoginRequest(
    @field:NotBlank @field:Email
    val email: String,
    @field:NotBlank
    val password: String,
)

// 토큰 자체는 더 이상 응답 본문에 담기지 않는다 — httpOnly 쿠키로만 전달되고 JS는 값을
// 읽을 수 없다. 이 DTO는 화면 표시에 필요한 사용자 정보만 담는다.
data class AuthResponse(
    val id: Long,
    val email: String,
    val isMember: Boolean,
    val isAdmin: Boolean,
)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val refreshTokenService: RefreshTokenService,
    private val authCookies: AuthCookies,
    private val adminEmailChecker: AdminEmailChecker,
) {

    @PostMapping("/signup")
    @Transactional
    fun signup(@Valid @RequestBody request: SignupRequest, response: HttpServletResponse): ResponseEntity<AuthResponse> {
        if (userRepository.findByEmail(request.email) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.")
        }

        val user = userRepository.save(
            User(email = request.email, isMember = true, passwordHash = passwordEncoder.encode(request.password)),
        )
        issueAuthCookies(response, user)
        return ResponseEntity.status(HttpStatus.CREATED).body(toAuthResponse(user))
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest, response: HttpServletResponse): AuthResponse {
        val user = userRepository.findByEmail(request.email)
        if (user?.passwordHash == null || !passwordEncoder.matches(request.password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")
        }
        issueAuthCookies(response, user)
        return toAuthResponse(user)
    }

    // access token이 만료된 뒤에도 호출 가능해야 하므로 access_token 쿠키가 아니라
    // refresh_token 쿠키만으로 인증한다(SecurityConfig에서 이 경로는 permitAll).
    @PostMapping("/refresh")
    fun refresh(
        @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) refreshToken: String?,
        response: HttpServletResponse,
    ): AuthResponse {
        val userId = refreshToken?.let { refreshTokenService.rotate(it) }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "세션이 만료되었습니다. 다시 로그인해주세요.")
        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "세션이 만료되었습니다. 다시 로그인해주세요.")
        }
        issueAuthCookies(response, user)
        return toAuthResponse(user)
    }

    @PostMapping("/logout")
    fun logout(
        @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) refreshToken: String?,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        refreshToken?.let { refreshTokenService.revoke(it) }
        response.addHeader(HttpHeaders.SET_COOKIE, authCookies.clearAccess().toString())
        response.addHeader(HttpHeaders.SET_COOKIE, authCookies.clearRefresh().toString())
        return ResponseEntity.noContent().build()
    }

    // 프론트가 페이지 로드 시 "이미 로그인돼 있는가"를 확인하는 용도. access_token 쿠키가
    // localStorage를 대체했기 때문에, 더 이상 클라이언트가 토큰 유효성을 스스로 판단할 수
    // 없어 이 엔드포인트가 필요해졌다.
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: AuthPrincipal): AuthResponse {
        val user = userRepository.findById(principal.userId)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED) }
        return toAuthResponse(user)
    }

    private fun issueAuthCookies(response: HttpServletResponse, user: User) {
        val accessToken = jwtService.generate(user.id!!, user.email)
        val refreshToken = refreshTokenService.issue(user.id!!)
        response.addHeader(HttpHeaders.SET_COOKIE, authCookies.access(accessToken).toString())
        response.addHeader(HttpHeaders.SET_COOKIE, authCookies.refresh(refreshToken).toString())
    }

    private fun toAuthResponse(user: User) = AuthResponse(
        id = user.id!!,
        email = user.email,
        isMember = user.isMember,
        isAdmin = adminEmailChecker.isAdmin(user.email),
    )
}
