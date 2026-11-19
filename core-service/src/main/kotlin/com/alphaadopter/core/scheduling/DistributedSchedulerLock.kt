package com.alphaadopter.core.scheduling

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

// Redis 장애로 락 획득 여부를 판단할 수 없을 때 작업을 그대로 실행할지(fail-open), 건너뛸지
// (fail-closed)를 작업 성격에 따라 고른다. 뉴스 수집은 중복 실행돼도 하류(링크 유니크 저장)에서
// 걸러지므로 한 주기를 통째로 놓치는 쪽이 더 나쁘다. 반면 다이제스트 메일은 한 번 나간 메일을
// 되돌릴 수 없으므로, 확인이 안 되면 보내지 않고 다음 주기로 미룬다(미발송 알림은 그대로 남는다).
enum class LockFailurePolicy { RUN_ANYWAY, SKIP }

// @Scheduled는 파드마다 각자 돈다. HPA로 core-service가 여러 개 떠 있으면 같은 수집·발송 작업이
// 파드 수만큼 동시에 실행돼 외부 API 할당량을 배로 쓰고, 다이제스트 메일이 중복 발송된다. 그래서
// 스케줄 작업 진입 시 Redis SET NX로 클러스터 전체에서 한 파드만 실행하도록 막는다. RateLimitFilter·
// NewsDeduplicationService와 같은 이유로 ShedLock 같은 라이브러리 대신 이미 쓰는 Redis로 직접 구현했다.
@Component
class DistributedSchedulerLock(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // lockAtMostFor: 작업 도중 파드가 죽어도 이 시간이 지나면 락이 풀려 다른 파드가 이어받는다.
    // lockAtLeastFor: 작업이 빨리 끝나도 이 시간 동안은 락을 유지한다. 바로 풀어버리면 시계가 몇 초
    // 어긋난 다른 파드의 같은 주기 실행이 그 직후 락을 잡아 한 번 더 돌게 된다.
    fun runExclusively(
        name: String,
        lockAtMostFor: Duration,
        lockAtLeastFor: Duration,
        onLockFailure: LockFailurePolicy,
        task: () -> Unit,
    ): Boolean {
        val key = "scheduler-lock:$name"
        val token = UUID.randomUUID().toString()

        val acquired = runCatching { redisTemplate.opsForValue().setIfAbsent(key, token, lockAtMostFor) == true }
            .getOrElse { e ->
                log.warn("[{}] Redis 오류로 스케줄 락을 확인하지 못했습니다(정책={}): {}", name, onLockFailure, e.message)
                record(name, "lock_error")
                if (onLockFailure == LockFailurePolicy.SKIP) return false
                task()
                return true
            }

        if (!acquired) {
            log.debug("[{}] 다른 인스턴스가 실행 중이라 이번 주기는 건너뜁니다.", name)
            record(name, "skipped")
            return false
        }

        record(name, "acquired")
        val startedAt = System.nanoTime()
        try {
            task()
        } finally {
            val remaining = lockAtLeastFor.minusNanos(System.nanoTime() - startedAt)
            runCatching { release(key, token, remaining) }
                .onFailure { e -> log.warn("[{}] 스케줄 락 해제 실패(lockAtMostFor 이후 자동 만료): {}", name, e.message) }
        }
        return true
    }

    // 다른 인스턴스가 이미 만료 후 새로 잡은 락을 지우면 안 되므로, 내 토큰일 때만 해제·단축한다.
    private fun release(key: String, token: String, remaining: Duration) {
        if (remaining.toMillis() > 0) {
            redisTemplate.execute(SHORTEN_SCRIPT, listOf(key), token, remaining.toMillis().toString())
        } else {
            redisTemplate.execute(RELEASE_SCRIPT, listOf(key), token)
        }
    }

    private fun record(name: String, result: String) {
        meterRegistry.counter("scheduler.lock", "task", name, "result", result).increment()
    }

    companion object {
        private val RELEASE_SCRIPT = DefaultRedisScript(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long::class.java,
        )
        private val SHORTEN_SCRIPT = DefaultRedisScript(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long::class.java,
        )
    }
}
