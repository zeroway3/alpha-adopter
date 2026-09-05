package com.alphaadopter.core.auth

import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import com.alphaadopter.core.user.AdminEmailChecker
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
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

data class AuthResponse(
    val token: String,
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
    private val adminEmailChecker: AdminEmailChecker,
) {

    @PostMapping("/signup")
    @Transactional
    fun signup(@Valid @RequestBody request: SignupRequest): ResponseEntity<AuthResponse> {
        if (userRepository.findByEmail(request.email) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.")
        }

        val user = userRepository.save(
            User(email = request.email, isMember = true, passwordHash = passwordEncoder.encode(request.password)),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(toAuthResponse(user))
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): AuthResponse {
        val user = userRepository.findByEmail(request.email)
        if (user?.passwordHash == null || !passwordEncoder.matches(request.password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")
        }
        return toAuthResponse(user)
    }

    private fun toAuthResponse(user: User) = AuthResponse(
        token = jwtService.generate(user.id!!, user.email),
        id = user.id!!,
        email = user.email,
        isMember = user.isMember,
        isAdmin = adminEmailChecker.isAdmin(user.email),
    )
}
