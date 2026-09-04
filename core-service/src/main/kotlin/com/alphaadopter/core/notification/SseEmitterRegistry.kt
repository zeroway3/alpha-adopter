package com.alphaadopter.core.notification

import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// 이 인스턴스가 들고 있는 사용자별 SSE 커넥션 목록. 인스턴스 로컬 상태이며, 여러 인스턴스 간 공유는 Redis Pub/Sub이 담당한다.
@Component
class SseEmitterRegistry {

    private val emittersByUserId = ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>()

    fun register(userId: Long, emitter: SseEmitter) {
        val emitters = emittersByUserId.computeIfAbsent(userId) { CopyOnWriteArrayList() }
        emitters.add(emitter)

        val remove = { remove(userId, emitter) }
        emitter.onCompletion(remove)
        emitter.onTimeout(remove)
        emitter.onError { remove() }
    }

    fun remove(userId: Long, emitter: SseEmitter) {
        emittersByUserId[userId]?.let { emitters ->
            emitters.remove(emitter)
            if (emitters.isEmpty()) {
                emittersByUserId.remove(userId, emitters)
            }
        }
    }

    fun emittersFor(userId: Long): List<SseEmitter> = emittersByUserId[userId] ?: emptyList()

    fun allEmitters(): List<SseEmitter> = emittersByUserId.values.flatten()
}
