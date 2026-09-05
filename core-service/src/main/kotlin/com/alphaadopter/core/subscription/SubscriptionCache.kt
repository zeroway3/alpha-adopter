package com.alphaadopter.core.subscription

import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import com.alphaadopter.core.domain.subscription.SubscriptionType
import jakarta.annotation.PostConstruct
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

data class CachedSubscription(
    val id: Long,
    val keyword: String,
    val type: SubscriptionType,
    val userId: Long,
)

// news.raw 컨슈머는 뉴스 기사 1건이 들어올 때마다 전체 구독 목록과 매칭해야 하는데, 매번
// subscriptionRepository.findAll()로 테이블 전체를 긁는 건 뉴스 처리량이 늘수록 그대로 DB
// 부하가 되는 패턴이다(구독 자체가 바뀌는 빈도는 뉴스 수집 주기 app.collector.fixed-delay-ms,
// 기본 60초마다 여러 건 처리되는 빈도에 비해 훨씬 낮음). 그래서 메모리에 캐시해두고 주기적으로만
// 갱신한다. findAllWithUser()가 JOIN FETCH로 user를 함께 읽어오므로, 캐시에 담긴 뒤(세션이
// 끝난 뒤) userId에 접근해도 LazyInitializationException이 나지 않는다.
@Component
class SubscriptionCache(
    private val subscriptionRepository: SubscriptionRepository,
) {
    @Volatile
    private var cached: List<CachedSubscription> = emptyList()

    // 새 구독을 만든 직후(SubscriptionController.create/delete) 반드시 이 메서드를 직접 호출해서
    // 즉시 갱신해야 한다 — 그렇지 않으면 "구독 생성 → 다음 스케줄 갱신 전에 마침 매칭되는 뉴스가
    // 먼저 소비됨" 레이스로 그 매칭을 영영 놓친다(컨슈머는 재시도하지 않음). 통합테스트에서
    // subscriptionRepository.save()만 하고 이 호출을 빼먹었다가 실제로 이 레이스를 재현한 적 있다.
    @PostConstruct
    fun refresh() {
        cached = subscriptionRepository.findAllWithUser().map {
            CachedSubscription(id = it.id!!, keyword = it.keyword, type = it.type, userId = it.user.id!!)
        }
    }

    @Scheduled(fixedDelayString = "\${app.subscription.cache-refresh-ms:30000}")
    fun scheduledRefresh() = refresh()

    fun all(): List<CachedSubscription> = cached
}
