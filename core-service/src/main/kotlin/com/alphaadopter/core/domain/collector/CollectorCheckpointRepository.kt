package com.alphaadopter.core.domain.collector

import org.springframework.data.jpa.repository.JpaRepository

interface CollectorCheckpointRepository : JpaRepository<CollectorCheckpoint, String>
