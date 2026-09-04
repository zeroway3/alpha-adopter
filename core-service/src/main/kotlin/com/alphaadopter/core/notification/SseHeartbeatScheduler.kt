package com.alphaadopter.core.notification

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

// 프록시/로드밸런서의 유휴 타임아웃으로 SSE 커넥션이 조용히 끊기는 것을 막기 위한 주기적 핑
@Component
class SseHeartbeatScheduler(
    private val sseEmitterRegistry: SseEmitterRegistry,
) {

    @Scheduled(fixedDelayString = "\${app.notification.heartbeat-interval-ms:20000}")
    fun ping() {
        sseEmitterRegistry.allEmitters().forEach { emitter ->
            runCatching {
                emitter.send(SseEmitter.event().comment("ping"))
            }
        }
    }
}
