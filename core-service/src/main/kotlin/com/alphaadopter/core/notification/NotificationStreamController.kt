package com.alphaadopter.core.notification

import com.alphaadopter.core.domain.user.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

// 인증 시스템 도입 전 단계라 userId를 그대로 받는다 (docs/future-ideas.md 참고, 인증은 별도 범위)
@RestController
class NotificationStreamController(
    private val userRepository: UserRepository,
    private val sseEmitterRegistry: SseEmitterRegistry,
) {

    @GetMapping("/api/notifications/stream/{userId}")
    fun stream(@PathVariable userId: Long): SseEmitter {
        val user = userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다: $userId") }

        if (!user.isMember) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "회원만 실시간 알림을 받을 수 있습니다. 비회원은 매일 아침 9시 다이제스트로 받아보실 수 있습니다.",
            )
        }

        val emitter = SseEmitter(0L)
        sseEmitterRegistry.register(userId, emitter)
        return emitter
    }
}
