package com.alphaadopter.core.admin

import com.alphaadopter.core.auth.AuthPrincipal
import com.alphaadopter.core.domain.collector.CollectorCheckpointRepository
import com.alphaadopter.core.domain.collector.CollectorIncident
import com.alphaadopter.core.domain.collector.CollectorIncidentRepository
import com.alphaadopter.core.domain.collector.CollectorIncidentType
import com.alphaadopter.core.domain.user.UserRole
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

data class CollectorCheckpointSummary(
    val keyword: String,
    val lastPublishedAt: Instant,
    val updatedAt: Instant,
)

data class CollectorIncidentSummary(
    val id: Long,
    val keyword: String,
    val type: CollectorIncidentType,
    val detail: String,
    val occurredAt: Instant,
) {
    companion object {
        fun from(incident: CollectorIncident) = CollectorIncidentSummary(
            id = incident.id!!,
            keyword = incident.keyword,
            type = incident.type,
            detail = incident.detail,
            occurredAt = incident.occurredAt,
        )
    }
}

data class CollectorStatusResponse(
    val checkpoints: List<CollectorCheckpointSummary>,
    val recentIncidents: List<CollectorIncidentSummary>,
)

// 수집 운영 현황: 키워드별로 어디까지 수집했는지(마지막 기사 시각이 오래 멈춰 있으면 수집이 막힌
// 것)와, 사람이 확인해야 하는 최근 이상 기록(누락 의심·호출 실패·발행 실패)을 관리자 화면에 보여준다.
@RestController
@RequestMapping("/api/admin/collector")
class AdminCollectorController(
    private val checkpointRepository: CollectorCheckpointRepository,
    private val incidentRepository: CollectorIncidentRepository,
) {

    @GetMapping("/status")
    fun status(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(defaultValue = "50") limit: Int,
    ): CollectorStatusResponse {
        if (principal.role != UserRole.ADMIN) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 접근할 수 있습니다.")
        }
        val checkpoints = checkpointRepository.findAll(Sort.by("keyword"))
            .map { CollectorCheckpointSummary(it.keyword, it.lastPublishedAt, it.updatedAt) }
        val incidents = incidentRepository.findAllByOrderByOccurredAtDesc(PageRequest.of(0, limit.coerceIn(1, 200)))
            .map(CollectorIncidentSummary::from)
        return CollectorStatusResponse(checkpoints, incidents)
    }
}
