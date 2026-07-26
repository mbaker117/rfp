package com.rfp.job

import com.rfp.repository.SupplierRepository
import com.rfp.service.CatalogIngestService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class CatalogRefreshJob(
    private val supplierRepo: SupplierRepository,
    private val catalogIngestService: CatalogIngestService
) {
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Amman")
    @SchedulerLock(name = "catalogRefreshJob", lockAtMostFor = "PT2H")
    fun refreshStaleSuppliers() {
        val cutoff = Instant.now().minus(7, ChronoUnit.DAYS)
        supplierRepo.findByLastScrapedAtBefore(cutoff)
            .forEach { catalogIngestService.ingestScrape(it.id) }
    }
}
