package com.rfp.repository

import com.rfp.domain.ScrapeJob
import com.rfp.domain.enums.ScrapeStatus
import org.springframework.data.jpa.repository.JpaRepository

interface ScrapeJobRepository : JpaRepository<ScrapeJob, Long> {
    fun findByCompanyIdAndStatus(companyId: Long, status: ScrapeStatus): List<ScrapeJob>
}
