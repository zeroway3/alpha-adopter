package com.alphaadopter.core.domain.collector

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface CollectorIncidentRepository : JpaRepository<CollectorIncident, Long> {
    fun findAllByOrderByOccurredAtDesc(pageable: Pageable): List<CollectorIncident>
}
