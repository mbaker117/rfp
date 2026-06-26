package com.rfp.service

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.rfp.domain.Instrument
import com.rfp.domain.InstrumentPriceHistory
import com.rfp.domain.ScrapeJob
import com.rfp.domain.Company
import com.rfp.domain.enums.ScrapeStatus
import com.rfp.dto.ScrapedInstrument
import com.rfp.repository.CompanyRepository
import com.rfp.repository.InstrumentPriceHistoryRepository
import com.rfp.repository.InstrumentRepository
import com.rfp.repository.ScrapeJobRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ScrapeService(
    private val companyRepo: CompanyRepository,
    private val scrapeJobRepo: ScrapeJobRepository,
    private val instrumentRepo: InstrumentRepository,
    private val llmService: LlmService,
    @Value("\${rfp.scraper.throttle-ms:2000}") val throttleMs: Long = 2000,
    private val priceHistoryRepo: InstrumentPriceHistoryRepository? = null
) {
    fun enqueueScrapeJob(companyId: Long) {
        val company = companyRepo.findById(companyId).orElseThrow { NoSuchElementException("Company $companyId not found") }
        val job = scrapeJobRepo.save(ScrapeJob(company = company))
        runScrapeJobAsync(companyId, job.id)
    }

    @Async("taskExecutor")
    fun runScrapeJobAsync(companyId: Long, jobId: Long) {
        val job = scrapeJobRepo.findById(jobId).orElseThrow { NoSuchElementException("Job $jobId not found") }
        val company = companyRepo.findById(companyId).orElseThrow { NoSuchElementException("Company $companyId not found") }
        try {
            scrapeJobRepo.save(job.copy(status = ScrapeStatus.RUNNING, startedAt = Instant.now()))
            val website = company.officialWebsite
                ?: throw IllegalStateException("No website for company ${company.name}")
            val html = crawlWithPlaywright(website)
            Thread.sleep(throttleMs)
            val instruments = llmService.structureScrapeData(html, company.name)
            persistScrapedInstruments(company, instruments)
            companyRepo.save(company.copy(scrapeStatus = ScrapeStatus.DONE, lastScrapedAt = Instant.now()))
            scrapeJobRepo.save(job.copy(status = ScrapeStatus.DONE, finishedAt = Instant.now()))
        } catch (e: Exception) {
            scrapeJobRepo.save(job.copy(status = ScrapeStatus.FAILED, errorMsg = e.message, finishedAt = Instant.now()))
            companyRepo.save(company.copy(scrapeStatus = ScrapeStatus.FAILED))
        }
    }

    fun persistScrapedInstruments(company: Company, scraped: List<ScrapedInstrument>) {
        val scrapedNames = scraped.map { it.normalizedName }.toSet()

        scraped.forEach { s ->
            val existing = instrumentRepo.findByCompanyIdAndNormalizedName(company.id, s.normalizedName)
            if (existing != null) {
                // Gap A: record price history if price changed
                val existingPrice = existing.price
                if (s.price != null && existingPrice != null && s.price.compareTo(existingPrice) != 0) {
                    priceHistoryRepo?.save(
                        InstrumentPriceHistory(
                            instrument = existing,
                            price = existingPrice,
                            currency = existing.currency
                        )
                    )
                }
                instrumentRepo.save(
                    existing.copy(
                        description = s.description,
                        manualLink = s.manualLink,
                        price = s.price,
                        updatedAt = Instant.now(),
                        isStale = false
                    )
                )
            } else {
                instrumentRepo.save(
                    Instrument(
                        company = company,
                        description = s.description,
                        normalizedName = s.normalizedName,
                        manualLink = s.manualLink,
                        price = s.price,
                        currency = s.currency
                    )
                )
            }
        }

        // Gap B: mark instruments not in scraped set as stale
        instrumentRepo.findByCompanyId(company.id)
            .filter { it.normalizedName !in scrapedNames && !it.isStale }
            .forEach { instrumentRepo.save(it.copy(isStale = true)) }
    }

    private fun crawlWithPlaywright(url: String): String {
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
