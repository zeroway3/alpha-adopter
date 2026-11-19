package com.alphaadopter.core.domain.collector

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// 키워드별로 "어디까지 수집했는지"를 기억하는 지점. 다음 수집 때 이 지점에 닿을 때까지 최신순으로
// 페이지를 넘기며 받기 때문에, 한 주기 사이에 기사가 많이 나와도 빠지지 않는다. 재시작·스케일아웃
// 후에도 이어서 수집해야 해서 휘발성 메모리나 캐시가 아니라 DB에 둔다.
@Entity
@Table(name = "collector_checkpoints")
class CollectorCheckpoint(
    @Id
    @Column(length = 255)
    val keyword: String,

    @Column(nullable = false)
    var lastPublishedAt: Instant,

    @Column(nullable = false, length = 1000)
    var lastLink: String,
) {
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
}
