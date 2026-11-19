package com.alphaadopter.core.collector

import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import com.alphaadopter.core.scheduling.DistributedSchedulerLock
import com.alphaadopter.core.scheduling.LockFailurePolicy
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

// 구독 중인 키워드들을 주기적으로 NAVER 뉴스 API로 수집해 news.raw 토픽으로 발행한다.
// 키워드 하나를 어떻게 빠짐없이 따라가는지는 NewsCollector, 여러 파드 중 한 곳에서만 돌게 하는
// 건 DistributedSchedulerLock이 담당한다.
@Component
class NewsCollectorScheduler(
    private val subscriptionRepository: SubscriptionRepository,
    private val naverNewsClient: NaverNewsClient,
    private val newsCollector: NewsCollector,
    private val schedulerLock: DistributedSchedulerLock,
    private val meterRegistry: MeterRegistry,
    @Value("\${app.collector.fixed-delay-ms:60000}") private val fixedDelayMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.collector.fixed-delay-ms:60000}")
    fun scheduledCollect() {
        if (!naverNewsClient.isConfigured) {
            log.debug("NAVER API 인증정보가 없어 뉴스 수집을 건너뜁니다.")
            return
        }
        // 중복 실행돼도 하류에서 링크 기준으로 걸러지므로, Redis 장애 시에는 한 주기를 통째로
        // 놓치기보다 그냥 수집하는 쪽(RUN_ANYWAY)을 택한다. lockAtLeastFor를 주기의 80%로 둬서
        // 다른 파드의 같은 주기 실행이 락 해제 직후 끼어들지 못하게 한다.
        schedulerLock.runExclusively(
            name = "news-collector",
            lockAtMostFor = Duration.ofMinutes(5),
            lockAtLeastFor = Duration.ofMillis(fixedDelayMs * 8 / 10),
            onLockFailure = LockFailurePolicy.RUN_ANYWAY,
        ) { collect() }
    }

    fun collect(): List<KeywordCollectResult> {
        val keywords = subscriptionRepository.findAll().map { it.keyword }.distinct()
        if (keywords.isEmpty()) {
            log.info("수집할 구독 키워드가 없습니다.")
            return emptyList()
        }

        val sample = Timer.start(meterRegistry)
        // 한 키워드의 예외가 다른 키워드 수집을 막지 않도록 키워드 단위로 격리한다
        val results = keywords.map { keyword ->
            runCatching { newsCollector.collect(keyword) }
                .getOrElse { e ->
                    log.error("'{}' 키워드 수집 중 예상치 못한 오류: {}", keyword, e.message, e)
                    KeywordCollectResult(keyword, 0, 0, 0, gapDetected = false, failed = true)
                }
        }
        sample.stop(meterRegistry.timer("news.collector.run"))

        log.info(
            "뉴스 수집 완료: 키워드 {}개, 새 기사 {}건, 발행 {}건, 누락 의심 {}건, 실패 {}건",
            results.size,
            results.sumOf { it.newArticles },
            results.sumOf { it.published },
            results.count { it.gapDetected },
            results.count { it.failed },
        )
        return results
    }
}
