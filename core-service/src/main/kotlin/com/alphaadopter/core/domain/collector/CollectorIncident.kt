package com.alphaadopter.core.domain.collector

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class CollectorIncidentType {
    // 페이지 상한까지 넘겨도 마지막 수집 지점에 닿지 못함 — 그 사이 기사 일부를 놓쳤을 수 있다
    GAP,

    // 재시도까지 모두 실패해 이번 주기에 이 키워드를 수집하지 못함
    FETCH_FAILED,

    // 기사는 받았지만 Kafka 발행에 실패함 — 수집 지점을 전진시키지 않아 다음 주기에 다시 받는다
    PUBLISH_FAILED,
}

// 정상 수집은 Micrometer 지표로만 남기고, 사람이 확인해야 하는 이상 상황(누락 가능성, 실패)만
// 조회 가능한 테이블에 기록한다. 매 주기 모든 실행을 행으로 남기면 키워드 15개 기준 하루 2만 건이
// 넘게 쌓여, 정작 봐야 할 이상 기록이 묻힌다.
@Entity
@Table(name = "collector_incidents")
class CollectorIncident(
    @Column(nullable = false, length = 255)
    val keyword: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val type: CollectorIncidentType,

    @Column(nullable = false, length = 1000)
    val detail: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var occurredAt: Instant = Instant.now()
}
