package com.rfp.service

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScrapeServiceTest {
    private val llmService = mockk<LlmService>()

    private val service = object : ScrapeService(llmService, throttleMs = 0) {
        override fun runScrapeJobAsync(supplierId: Long) { /* no-op in tests */ }
    }.also { it.self = it }

    @Test
    fun `crawlWebsite method is accessible`() {
        assertTrue(service::crawlWebsite.name == "crawlWebsite")
    }
}
