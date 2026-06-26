package com.rfp.service

import com.rfp.domain.Company
import com.rfp.domain.ScrapeJob
import com.rfp.domain.enums.ScrapeStatus
import com.rfp.dto.ScrapedInstrument
import com.rfp.repository.CompanyRepository
import com.rfp.repository.InstrumentPriceHistoryRepository
import com.rfp.repository.InstrumentRepository
import com.rfp.repository.ScrapeJobRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

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
    fun `enqueueScrapeJob saves a PENDING job`() {
        val company = Company(id = 1L, name = "Tektronix", officialWebsite = "https://tek.com")
        val savedJob = ScrapeJob(id = 1L, company = company, status = ScrapeStatus.PENDING)
        every { companyRepo.findById(1L) } returns java.util.Optional.of(company)
        every { scrapeJobRepo.save(any<ScrapeJob>()) } returns savedJob

        service.enqueueScrapeJob(1L)

        val slot = slot<ScrapeJob>()
        verify { scrapeJobRepo.save(capture(slot)) }
        assertEquals(ScrapeStatus.PENDING, slot.captured.status)
    }

    @Test
    fun `persistScrapedInstruments saves instruments to DB`() {
        val company = Company(id = 1L, name = "Tektronix")
        val scraped = listOf(
            ScrapedInstrument("Oscilloscope 200MHz", "Oscilloscope 200MHz", "https://tek.com/manual.pdf", BigDecimal("1200.00"))
        )
        every { instrumentRepo.findByCompanyIdAndNormalizedName(any(), any()) } returns null
        every { instrumentRepo.findByCompanyId(any()) } returns emptyList()
        every { instrumentRepo.save(any()) } returnsArgument 0

        service.persistScrapedInstruments(company, scraped)

        verify(exactly = 1) { instrumentRepo.save(any()) }
    }
}
