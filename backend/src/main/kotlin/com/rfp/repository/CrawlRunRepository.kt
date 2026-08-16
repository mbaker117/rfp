package com.rfp.repository

import com.rfp.domain.CrawlRun
import com.rfp.domain.CrawlRunStatus
import org.springframework.data.jpa.repository.JpaRepository

interface CrawlRunRepository : JpaRepository<CrawlRun, Long> {
    fun findByStatusIn(statuses: Collection<CrawlRunStatus>): List<CrawlRun>
    fun existsBySupplierIdAndStatusIn(supplierId: Long, statuses: Collection<CrawlRunStatus>): Boolean
    /** Returns the most recent 100 crawl runs for the given supplier, newest first. */
    fun findTop100BySupplierIdOrderByCreatedAtDesc(supplierId: Long): List<CrawlRun>
}
