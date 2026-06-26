package com.rfp.service

import com.rfp.domain.Company
import com.rfp.domain.enums.ScrapeStatus
import com.rfp.repository.CompanyRepository
import com.rfp.repository.InstrumentRepository
import com.rfp.repository.ScrapeJobRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompanyResolutionServiceTest {
    private val companyRepo = mockk<CompanyRepository>()
    private val instrumentRepo = mockk<InstrumentRepository>()
    private val scrapeJobRepo = mockk<ScrapeJobRepository>()
    private val scrapeService = mockk<ScrapeService>(relaxed = true)
    private val service = CompanyResolutionService(companyRepo, instrumentRepo, scrapeJobRepo, scrapeService)

    @Test
    fun `resolveCompanies reuses existing company when found by name`() {
        val existing = Company(id = 1L, name = "Tektronix")
        every { companyRepo.findByNameIgnoreCase("Tektronix") } returns existing
        every { instrumentRepo.findByCompanyIdIn(listOf(1L)) } returns listOf(mockk())

        val result = service.resolveCompanies(listOf("Tektronix"))
        assertEquals(1, result.size)
        assertEquals(1L, result[0].id)
        verify(exactly = 0) { scrapeService.enqueueScrapeJob(any()) }
    }

    @Test
    fun `resolveCompanies creates company and enqueues scrape job when not found`() {
        val saved = Company(id = 2L, name = "NewCo")
        every { companyRepo.findByNameIgnoreCase("NewCo") } returns null
        every { companyRepo.save(any()) } returns saved
        every { instrumentRepo.findByCompanyIdIn(listOf(2L)) } returns emptyList()
        every { scrapeService.enqueueScrapeJob(2L) } just Runs

        val result = service.resolveCompanies(listOf("NewCo"))
        assertEquals(1, result.size)
        verify(exactly = 1) { scrapeService.enqueueScrapeJob(2L) }
    }
}
