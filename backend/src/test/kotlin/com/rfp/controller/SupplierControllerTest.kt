// backend/src/test/kotlin/com/rfp/controller/SupplierControllerTest.kt
package com.rfp.controller

import com.rfp.domain.Supplier
import com.rfp.repository.CatalogIngestRepository
import com.rfp.repository.CrawlRunRepository
import com.rfp.repository.SupplierRepository
import com.rfp.service.crawl.CrawlCoordinator
import io.mockk.every
import io.mockk.mockk
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class SupplierControllerTest {
    private val repo = mockk<SupplierRepository>()
    private val catalogIngestRepo = mockk<CatalogIngestRepository>()
    private val crawlRunRepo = mockk<CrawlRunRepository>()
    private val crawlCoordinator = mockk<CrawlCoordinator>()
    private val controller = SupplierController(repo, catalogIngestRepo, crawlRunRepo, crawlCoordinator)

    @Test
    fun `register saves and returns supplier`() {
        val req = SupplierRequest(name = "Acme", officialWebsite = "https://acme.com")
        every { repo.findByNameIgnoreCase("Acme") } returns null
        every { repo.save(any()) } answers { firstArg<Supplier>().copy(id = 1L) }
        val resp = controller.register(req)
        assertThat(resp.statusCode.value()).isEqualTo(200)
        assertThat(resp.body?.name).isEqualTo("Acme")
    }

    @Test
    fun `getById returns 404 when missing`() {
        every { repo.findById(99L) } returns Optional.empty()
        val resp = controller.getById(99L)
        assertThat(resp.statusCode.value()).isEqualTo(404)
    }

    // -----------------------------------------------------------------------
    // crawlAllowedHosts validation — tested via the isValidCrawlHost function
    // -----------------------------------------------------------------------

    @Test
    fun `hostname validator accepts plain DNS hostnames`() {
        assertThat(isValidCrawlHost("example.com")).isTrue()
        assertThat(isValidCrawlHost("sub.example.com")).isTrue()
    }

    @Test
    fun `hostname validator rejects IPv4 literals`() {
        assertThat(isValidCrawlHost("192.168.1.1")).isFalse()
        assertThat(isValidCrawlHost("10.0.0.1")).isFalse()
    }

    @Test
    fun `hostname validator rejects IPv6 bracket notation`() {
        assertThat(isValidCrawlHost("[::1]")).isFalse()
        assertThat(isValidCrawlHost("[2001:db8::1]")).isFalse()
    }

    @Test
    fun `hostname validator rejects URL strings with scheme`() {
        assertThat(isValidCrawlHost("https://example.com")).isFalse()
        assertThat(isValidCrawlHost("http://example.com")).isFalse()
        assertThat(isValidCrawlHost("ftp://files.example.com")).isFalse()
    }

    @Test
    fun `hostname validator rejects port suffixes`() {
        assertThat(isValidCrawlHost("example.com:8080")).isFalse()
        assertThat(isValidCrawlHost("host:443")).isFalse()
    }

    @Test
    fun `hostname validator rejects blank entries`() {
        assertThat(isValidCrawlHost("")).isFalse()
        assertThat(isValidCrawlHost("  ")).isFalse()
    }

    // -----------------------------------------------------------------------
    // Crawl limit field range validation
    // -----------------------------------------------------------------------

    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `crawl limit fields reject out-of-range values`() {
        val req = SupplierRequest(
            name = "Test",
            crawlThrottleMs = -1L,         // min 0
            crawlMaxConcurrency = 0,        // min 1
            crawlBatchPages = 0,            // min 1
            crawlMaxUrls = 0               // min 1
        )
        val violations = validator.validate(req)
        assertThat(violations).hasSize(4)
    }

    @Test
    fun `crawl limit fields reject above-max values`() {
        val req = SupplierRequest(
            name = "Test",
            crawlThrottleMs = 10001L,       // max 10000
            crawlMaxConcurrency = 11,       // max 10
            crawlBatchPages = 501,          // max 500
            crawlMaxUrls = 100001          // max 100000
        )
        val violations = validator.validate(req)
        assertThat(violations).hasSize(4)
    }

    @Test
    fun `crawl limit fields accept boundary values`() {
        val req = SupplierRequest(
            name = "Test",
            crawlThrottleMs = 0L,
            crawlMaxConcurrency = 1,
            crawlBatchPages = 1,
            crawlMaxUrls = 1
        )
        val violations = validator.validate(req)
        assertThat(violations).isEmpty()
    }
}
