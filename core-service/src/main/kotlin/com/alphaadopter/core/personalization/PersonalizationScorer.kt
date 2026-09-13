package com.alphaadopter.core.personalization

import com.alphaadopter.core.domain.notification.NotificationRepository
import com.alphaadopter.core.domain.notification.NotificationStatus
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

// 참여도(읽음/클릭) 기반 개인화 필터링 (docs/future-ideas.md). 문자열 매칭·AI 관련도 판단을
// 통과해 실제로 알림을 만들 때, "이 사용자가 이 구독(키워드)의 알림을 평소 얼마나 읽어왔는지"를
// 0.0~1.0 점수로 계산해 함께 남긴다. 판단 자체를 막지는 않고(fail-open 원칙 유지) 우선순위
// 정보로만 쓴다 — 일일 다이제스트가 이 점수로 항목을 정렬하는 데 사용한다.
@Component
class PersonalizationScorer(
    private val notificationRepository: NotificationRepository,
    private val meterRegistry: MeterRegistry,
    // 전달 이력이 이 값 미만이면 콜드스타트로 보고 null(판단 보류)을 반환한다. 데이터가
    // 1~2건뿐인 상태에서 나온 0% 또는 100%는 우연에 가까워 신뢰할 수 없다.
    @Value("\${app.personalization.min-sample-size:5}") private val minSampleSize: Long,
) {
    fun scoreFor(subscriptionId: Long): Double? {
        val delivered = notificationRepository.countByStatusAndSubscriptionId(NotificationStatus.SENT, subscriptionId)
        if (delivered < minSampleSize) {
            meterRegistry.counter("personalization.score", "result", "cold-start").increment()
            return null
        }

        val read = notificationRepository.countByStatusAndSubscriptionIdAndReadAtIsNotNull(
            NotificationStatus.SENT,
            subscriptionId,
        )
        meterRegistry.counter("personalization.score", "result", "scored").increment()
        return read.toDouble() / delivered.toDouble()
    }
}
