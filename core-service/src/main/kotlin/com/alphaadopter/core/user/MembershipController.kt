package com.alphaadopter.core.user

import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class MembershipRequest(
    @field:NotBlank @field:Email
    val email: String,
)

data class MembershipResponse(
    val id: Long,
    val email: String,
    val isMember: Boolean,
    val isAdmin: Boolean,
)

// 결제/인증 없이 회원 전환만 가능한 단계 (docs/future-ideas.md 참고). 회원이 되면 SSE 실시간 알림,
// 아니면 DailyDigestScheduler의 일일 이메일 다이제스트로 알림을 받는다.
//
// 별도 로그인 시스템이 없어서, 프런트엔드는 이 엔드포인트를 "로그인"으로도 사용한다
// (이메일 입력 -> 사용자 생성/회원 전환 -> SSE 실시간 알림을 바로 받을 수 있는 상태로).
@RestController
@RequestMapping("/api/users/membership")
class MembershipController(
    private val userRepository: UserRepository,
    private val adminEmailChecker: AdminEmailChecker,
) {

    @PostMapping
    @Transactional
    fun activate(@Valid @RequestBody request: MembershipRequest): MembershipResponse {
        val user = userRepository.findByEmail(request.email) ?: userRepository.save(User(email = request.email))
        user.isMember = true
        userRepository.save(user)
        return MembershipResponse(
            id = user.id!!,
            email = user.email,
            isMember = user.isMember,
            isAdmin = adminEmailChecker.isAdmin(user.email),
        )
    }
}
