package com.alphaadopter.core.notification

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseEmitterRegistryTest {

    @Test
    fun `등록한 emitter는 해당 userId로 조회된다`() {
        val registry = SseEmitterRegistry()
        val emitter = SseEmitter()

        registry.register(1L, emitter)

        assertEquals(listOf(emitter), registry.emittersFor(1L))
        assertTrue(registry.emittersFor(2L).isEmpty())
    }

    @Test
    fun `제거하면 더 이상 조회되지 않는다`() {
        val registry = SseEmitterRegistry()
        val emitter = SseEmitter()
        registry.register(1L, emitter)

        registry.remove(1L, emitter)

        assertTrue(registry.emittersFor(1L).isEmpty())
        assertTrue(registry.allEmitters().isEmpty())
    }

    @Test
    fun `한 사용자가 여러 커넥션을 가질 수 있다`() {
        val registry = SseEmitterRegistry()
        val first = SseEmitter()
        val second = SseEmitter()

        registry.register(1L, first)
        registry.register(1L, second)

        assertEquals(2, registry.emittersFor(1L).size)
        assertEquals(2, registry.allEmitters().size)
    }
}
