package com.rfp.repository

import com.rfp.domain.CrawlRun
import org.springframework.data.jpa.repository.JpaRepository

interface CrawlRunRepository : JpaRepository<CrawlRun, Long>
