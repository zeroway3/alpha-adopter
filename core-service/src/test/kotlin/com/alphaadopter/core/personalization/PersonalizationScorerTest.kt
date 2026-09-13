package com.alphaadopter.core.personalization

import com.alphaadopter.core.domain.notification.NotificationRepository
import com.alphaadopter.core.domain.notification.NotificationStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertNull

// PersonalizationScorer의 콜드스타트 임계값·읽음 비율 계산을 실제 DB 없이 검증한다.
class PersonalizationScorerTest {

    // Mockito.eq()는 내부적으로 null을 반환한 뒤 매처 스택에 기록하는 방식이라, Kotlin의
    // non-null 파라미터(NotificationStatus)에 그대로 쓰면 "eq(...) must not be null" NPE가 난다.
    // 엘비스 연산자로 컴파일러의 null 검사만 통과시키고 매처 등록 자체는 그대로 유지한다.
    private fun eqStatus(status: NotificationStatus): NotificationStatus = Mockito.eq(status) ?: status

    private fun scorerWith(delivered: Long, read: Long, minSampleSize: Long = 5): PersonalizationScorer {
        val repository = Mockito.mock(NotificationRepository::class.java)
        Mockito.`when`(
            repository.countByStatusAndSubscriptionId(eqStatus(NotificationStatus.SENT), anyLong()),
        ).thenReturn(delivered)
        Mockito.`when`(
            repository.countByStatusAndSubscriptionIdAndReadAtIsNotNull(eqStatus(NotificationStatus.SENT), anyLong()),
        ).thenReturn(read)
        return PersonalizationScorer(repository, SimpleMeterRegistry(), minSampleSize)
    }

    @Test
    fun `전달 이력이 최소 표본 미만이면 콜드스타트로 null을 반환한다`() {
        val scorer = scorerWith(delivered = 4, read = 4, minSampleSize = 5)

        assertNull(scorer.scoreFor(1L))
    }

    @Test
    fun `전달 이력이 최소 표본과 같으면 콜드스타트가 아니다`() {
        val scorer = scorerWith(delivered = 5, read = 3, minSampleSize = 5)

        assertEquals(0.6, scorer.scoreFor(1L))
    }

    @Test
    fun `표본이 충분하면 읽음 비율을 반환한다`() {
        val scorer = scorerWith(delivered = 10, read = 7, minSampleSize = 5)

        assertEquals(0.7, scorer.scoreFor(1L))
    }

    @Test
    fun `한 번도 읽지 않았어도 표본이 충분하면 0을 반환한다`() {
        val scorer = scorerWith(delivered = 5, read = 0, minSampleSize = 5)

        assertEquals(0.0, scorer.scoreFor(1L))
    }
}
