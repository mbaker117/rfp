package com.rfp.job

import com.rfp.domain.CrawlRunStatus
import com.rfp.repository.CrawlRunRepository
import com.rfp.repository.SupplierRepository
import com.rfp.service.ScrapeService
import com.rfp.service.crawl.CrawlCoordinator
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class CatalogRefreshJob(
    private val supplierRepo: SupplierRepository,
    private val scrapeService: ScrapeService,
    private val coordinator: CrawlCoordinator,
    private val crawlRunRepo: CrawlRunRepository,
    @Value("\${rfp.scraper.adaptive-enabled:true}") private val adaptiveEnabled: Boolean = true
) {
    companion object {
        /** Non-terminal statuses that mean a supplier is already being crawled. */
        private val ACTIVE_STATUSES = listOf(CrawlRunStatus.QUEUED, CrawlRunStatus.CRAWLING)
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Amman")
    @SchedulerLock(name = "catalogRefreshJob", lockAtMostFor = "PT2H")
    fun refresh() {
        val cutoff = Instant.now().minus(7, ChronoUnit.DAYS)
        supplierRepo.findByLastScrapedAtBeforeOrLastScrapedAtIsNull(cutoff)
            .filter { supplier ->
                // Skip suppliers that already have a non-terminal crawl run in progress.
                // Uses a repository query so the check is durable across restarts.
                !crawlRunRepo.existsBySupplierIdAndStatusIn(supplier.id, ACTIVE_STATUSES)
            }
            .forEach { supplier ->
                if (adaptiveEnabled) {
                    coordinator.enqueue(supplier.id, null)
                } else {
                    scrapeService.runScrapeJobAsync(supplier.id)
                }
            }
    }
}
