package com.rfp.repository

import com.rfp.domain.CrawlProductObservation
import org.springframework.data.jpa.repository.JpaRepository

interface CrawlProductObservationRepository : JpaRepository<CrawlProductObservation, Long> {
    fun countByRunId(runId: Long): Long
    fun findByRunId(runId: Long): List<CrawlProductObservation>
}
