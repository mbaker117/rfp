package com.rfp.job

import com.rfp.domain.CrawlRunStatus
import com.rfp.domain.Supplier
import com.rfp.repository.CrawlRunRepository
import com.rfp.repository.SupplierRepository
import com.rfp.service.ScrapeService
import com.rfp.service.crawl.CrawlCoordinator
import io.mockk.*
import org.junit.jupiter.api.Test
import java.time.Instant

class CatalogRefreshJobTest {

    private val supplierRepo  = mockk<SupplierRepository>()
    private val scrapeService = mockk<ScrapeService>()
    private val coordinator   = mockk<CrawlCoordinator>()
    private val crawlRunRepo  = mockk<CrawlRunRepository>()

    private fun makeJob(adaptiveEnabled: Boolean) = CatalogRefreshJob(
        supplierRepo    = supplierRepo,
        scrapeService   = scrapeService,
        coordinator     = coordinator,
        crawlRunRepo    = crawlRunRepo,
        adaptiveEnabled = adaptiveEnabled
    )

    @Test
    fun `refresh job ignores suppliers with active non-terminal runs`() {
        val supplier1 = Supplier(id = 1L, name = "S1", officialWebsite = "https://s1.example.com")
        val supplier2 = Supplier(id = 2L, name = "S2", officialWebsite = "https://s2.example.com")

        every { supplierRepo.findByLastScrapedAtBeforeOrLastScrapedAtIsNull(any()) } returns
            listOf(supplier1, supplier2)
        every { crawlRunRepo.existsBySupplierIdAndStatusIn(1L, any()) } returns true
        every { crawlRunRepo.existsBySupplierIdAndStatusIn(2L, any()) } returns false
        every { coordinator.enqueue(2L, null) } returns 42L

        makeJob(adaptiveEnabled = true).refresh()

        verify(exactly = 0) { coordinator.enqueue(1L, any()) }
        verify(exactly = 1) { coordinator.enqueue(2L, null) }
    }

    @Test
    fun `refresh job calls legacy scrape when adaptive flag is false`() {
        val supplier = Supplier(id = 3L, name = "S3", officialWebsite = "https://s3.example.com")

        every { supplierRepo.findByLastScrapedAtBeforeOrLastScrapedAtIsNull(any()) } returns listOf(supplier)
        every { crawlRunRepo.existsBySupplierIdAndStatusIn(3L, any()) } returns false
        every { scrapeService.runScrapeJobAsync(3L) } just Runs

        makeJob(adaptiveEnabled = false).refresh()

        verify(exactly = 1) { scrapeService.runScrapeJobAsync(3L) }
        verify(exactly = 0) { coordinator.enqueue(any(), any()) }
    }

    @Test
    fun `refresh job skips all suppliers if all have active runs`() {
        val supplier1 = Supplier(id = 1L, name = "S1", officialWebsite = "https://s1.example.com")
        val supplier2 = Supplier(id = 2L, name = "S2", officialWebsite = "https://s2.example.com")

        every { supplierRepo.findByLastScrapedAtBeforeOrLastScrapedAtIsNull(any()) } returns
            listOf(supplier1, supplier2)
        every { crawlRunRepo.existsBySupplierIdAndStatusIn(any(), any()) } returns true

        makeJob(adaptiveEnabled = true).refresh()

        verify(exactly = 0) { coordinator.enqueue(any(), any()) }
    }
}
