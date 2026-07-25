package com.rfp.service

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.rfp.repository.CompanyRepository
import com.rfp.repository.InstrumentPriceHistoryRepository
import com.rfp.repository.InstrumentRepository
import com.rfp.repository.ScrapeJobRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

// open so tests can subclass and override runScrapeJobAsync without Playwright
@Service
open class ScrapeService(
    private val companyRepo: CompanyRepository,
    private val scrapeJobRepo: ScrapeJobRepository,
    private val instrumentRepo: InstrumentRepository,
    private val priceHistoryRepo: InstrumentPriceHistoryRepository,
    private val llmService: LlmService,
    @Value("\${rfp.scraper.throttle-ms:2000}") val throttleMs: Long = 2000
) {
    // Self-inject via setter to get the Spring proxy (fixes @Async bypass)
    @Autowired
    @Lazy
    lateinit var self: ScrapeService

    fun enqueueScrapeJob(companyId: Long) {
        // Deprecated — use CatalogIngestService.ingestScrape instead
    }

    @Async("taskExecutor")
    open fun runScrapeJobAsync(companyId: Long, jobId: Long) {
        // removed — use CatalogIngestService.ingestScrape
    }

    fun crawlWebsite(url: String): String {
        Playwright.create().use { pw ->
            val browser = pw.chromium().launch(
                BrowserType.LaunchOptions().setHeadless(true)
            )
            val page = browser.newPage()
            page.navigate(url)
            page.waitForLoadState()
            val html = page.content()
            browser.close()
            return html
        }
    }
}
