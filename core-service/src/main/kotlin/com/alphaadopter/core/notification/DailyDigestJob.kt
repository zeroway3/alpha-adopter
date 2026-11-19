package com.alphaadopter.core.notification

import com.alphaadopter.core.scheduling.DistributedSchedulerLock
import com.alphaadopter.core.scheduling.LockFailurePolicy
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

// 매일 아침 다이제스트 발송 트리거. 파드가 여러 개면 cron이 파드마다 동시에 울려 같은 메일이
// 파드 수만큼 나갈 수 있어(발송 전 조회 시점에는 모두 아직 MATCHED 상태), 분산 락으로 한 파드만
// 발송하게 한다. 메일은 되돌릴 수 없으므로 락 확인이 안 되면(Redis 장애) 보내지 않는다 — 미발송
// 알림은 MATCHED로 남아 다음 발송 때 함께 나간다. lockAtLeastFor를 길게 잡아 시계가 어긋난 다른
// 파드가 몇 초 늦게 같은 발송을 다시 시작하지 못하게 한다.
@Component
class DailyDigestJob(
    private val dailyDigestScheduler: DailyDigestScheduler,
    private val schedulerLock: DistributedSchedulerLock,
) {
    @Scheduled(cron = "\${app.notification.digest-cron}")
    fun trigger() {
        schedulerLock.runExclusively(
            name = "daily-digest",
            lockAtMostFor = Duration.ofMinutes(30),
            lockAtLeastFor = Duration.ofMinutes(10),
            onLockFailure = LockFailurePolicy.SKIP,
        ) { dailyDigestScheduler.sendDigest() }
    }
}
