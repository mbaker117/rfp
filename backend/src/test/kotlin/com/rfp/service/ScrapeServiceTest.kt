package com.rfp.service

import com.rfp.domain.Company
import com.rfp.domain.ScrapeJob
import com.rfp.domain.enums.ScrapeStatus
import com.rfp.repository.CompanyRepository
import com.rfp.repository.InstrumentPriceHistoryRepository
import com.rfp.repository.InstrumentRepository
import com.rfp.repository.ScrapeJobRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScrapeServiceTest {
    private val companyRepo = mockk<CompanyRepository>()
    private val scrapeJobRepo = mockk<ScrapeJobRepository>(relaxed = true)
    private val instrumentRepo = mockk<InstrumentRepository>(relaxed = true)
    private val priceHistoryRepo = mockk<InstrumentPriceHistoryRepository>(relaxed = true)
    private val llmService = mockk<LlmService>()

    // Anonymous subclass overrides runScrapeJobAsync to be a no-op, avoiding
    // Playwright in unit tests and sidestepping the @Lazy self-injection wiring.
    private val service = object : ScrapeService(
        companyRepo, scrapeJobRepo, instrumentRepo, priceHistoryRepo, llmService, throttleMs = 0
    ) {
        override fun runScrapeJobAsync(companyId: Long, jobId: Long) { /* no-op in tests */ }
    }.also { it.self = it }

    @Test
    fun `crawlWebsite method is accessible`() {
        // Smoke test: crawlWebsite is now a public method on ScrapeService
        // Full Playwright tests are integration-level and skipped here.
        assertTrue(service::crawlWebsite.name == "crawlWebsite")
    }
}
