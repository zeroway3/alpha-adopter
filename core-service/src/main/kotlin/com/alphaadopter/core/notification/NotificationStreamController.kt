package com.alphaadopter.core.notification

import com.alphaadopter.core.auth.AuthPrincipal
import com.alphaadopter.core.domain.user.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

// EventSource는 커스텀 헤더를 못 보내서, 이 요청은 JwtAuthFilter가 ?token= 쿼리 파라미터로도
// 인증을 허용해준다 (다른 API는 Authorization 헤더만 허용).
@RestController
class NotificationStreamController(
    private val userRepository: UserRepository,
    private val sseEmitterRegistry: SseEmitterRegistry,
) {

    @GetMapping("/api/notifications/stream")
    fun stream(@AuthenticationPrincipal principal: AuthPrincipal): SseEmitter {
        val user = userRepository.findById(principal.userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다.") }

        if (!user.isMember) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "회원만 실시간 알림을 받을 수 있습니다. 비회원은 매일 아침 9시 다이제스트로 받아보실 수 있습니다.",
            )
        }

        val emitter = SseEmitter(0L)
        sseEmitterRegistry.register(principal.userId, emitter)
        return emitter
    }
}
