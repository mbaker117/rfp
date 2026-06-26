package com.rfp.job

import com.rfp.repository.CompanyRepository
import com.rfp.service.ScrapeService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class InstrumentRefreshJob(
    private val companyRepo: CompanyRepository,
    private val scrapeService: ScrapeService
) {
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Amman")
    @SchedulerLock(name = "instrumentRefreshJob", lockAtMostFor = "PT2H")
    fun refreshStaleCompanies() {
        val cutoff = Instant.now().minus(7, ChronoUnit.DAYS)
        companyRepo.findByLastScrapedAtBefore(cutoff)
            .forEach { scrapeService.enqueueScrapeJob(it.id) }
    }
}
