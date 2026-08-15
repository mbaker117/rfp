package com.rfp.service

import com.rfp.service.crawl.CrawlCoordinator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScrapeServiceTest {
    private val llmService = mockk<LlmService>()

    // Legacy subclass pattern — overrides runScrapeJobAsync so coordinator is never touched.
    private val legacyService = object : ScrapeService(llmService, throttleMs = 0) {
        override fun runScrapeJobAsync(supplierId: Long) { /* no-op in tests */ }
    }.also { it.self = it }

    @Test
    fun `crawlWebsite method is accessible`() {
        assertTrue(legacyService::crawlWebsite.name == "crawlWebsite")
    }

    @Test
    fun `scrape facade enqueues exactly one durable crawl`() {
        val coordinator = mockk<CrawlCoordinator>()
        every { coordinator.enqueue(7L, null) } returns 41L

        // Construct with adaptive flag enabled; coordinator is set directly (no Spring context).
        val service = ScrapeService(llmService, throttleMs = 0, adaptiveEnabled = true)
        service.self = service
        service.coordinator = coordinator

        service.runScrapeJobAsync(7L)

        verify(exactly = 1) { coordinator.enqueue(7L, null) }
    }
}
