package com.rfp.service

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

// open so tests can subclass and override runScrapeJobAsync without Playwright
@Service
open class ScrapeService(
    private val llmService: LlmService,
    @Value("\${rfp.scraper.throttle-ms:2000}") val throttleMs: Long = 2000
) {
    // Self-inject via setter to get the Spring proxy (fixes @Async bypass)
    @Autowired
    @Lazy
    lateinit var self: ScrapeService

    @Async("taskExecutor")
    open fun runScrapeJobAsync(supplierId: Long) {
        // entry point kept for subclass override in tests
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
