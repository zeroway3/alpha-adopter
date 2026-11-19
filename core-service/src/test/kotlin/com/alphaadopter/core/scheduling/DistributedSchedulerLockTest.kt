package com.alphaadopter.core.scheduling

import com.alphaadopter.core.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 실제 Redis(Testcontainers)로, 여러 인스턴스가 같은 스케줄 작업을 동시에 시작해도 한 곳에서만
// 실행되는지와 lockAtLeastFor·토큰 기반 해제가 의도대로 동작하는지 확인한다.
class DistributedSchedulerLockTest : IntegrationTestBase() {

    @Autowired
    lateinit var lock: DistributedSchedulerLock

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    private fun uniqueName() = "test-${UUID.randomUUID()}"

    @Test
    fun `여러 인스턴스가 동시에 시작해도 작업은 한 번만 실행된다`() {
        val name = uniqueName()
        val executions = AtomicInteger()
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(5)

        repeat(5) {
            pool.submit {
                ready.await()
                lock.runExclusively(name, Duration.ofSeconds(30), Duration.ofSeconds(5), LockFailurePolicy.SKIP) {
                    executions.incrementAndGet()
                    Thread.sleep(200)
                }
            }
        }
        ready.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        assertEquals(1, executions.get())
    }

    @Test
    fun `작업이 빨리 끝나도 lockAtLeastFor 동안은 다음 실행을 막는다`() {
        val name = uniqueName()

        assertTrue(lock.runExclusively(name, Duration.ofSeconds(30), Duration.ofSeconds(5), LockFailurePolicy.SKIP) {})
        assertFalse(lock.runExclusively(name, Duration.ofSeconds(30), Duration.ofSeconds(5), LockFailurePolicy.SKIP) {})

        val ttl = redisTemplate.getExpire("scheduler-lock:$name", TimeUnit.MILLISECONDS)
        assertTrue(ttl in 1..5000, "남은 TTL이 lockAtLeastFor 이하로 줄어 있어야 함: $ttl")
    }

    @Test
    fun `lockAtLeastFor가 지난 뒤 끝난 작업은 락을 바로 해제한다`() {
        val name = uniqueName()

        lock.runExclusively(name, Duration.ofSeconds(30), Duration.ZERO, LockFailurePolicy.SKIP) {}

        assertNull(redisTemplate.opsForValue().get("scheduler-lock:$name"))
    }

    @Test
    fun `다른 인스턴스가 새로 잡은 락은 지우지 않는다`() {
        val name = uniqueName()
        val key = "scheduler-lock:$name"

        lock.runExclusively(name, Duration.ofSeconds(30), Duration.ZERO, LockFailurePolicy.SKIP) {
            // 작업 도중 락이 만료되고 다른 인스턴스가 새로 잡은 상황을 흉내 낸다
            redisTemplate.opsForValue().set(key, "other-instance-token", Duration.ofSeconds(30))
        }

        assertEquals("other-instance-token", redisTemplate.opsForValue().get(key))
    }
}
