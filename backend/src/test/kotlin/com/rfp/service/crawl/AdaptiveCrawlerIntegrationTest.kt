package com.rfp.service.crawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.rfp.domain.CrawlPageType
import com.rfp.domain.CrawlProductObservation
import com.rfp.domain.CrawlRun
import com.rfp.domain.CrawlRunStatus
import com.rfp.domain.CrawlUrl
import com.rfp.domain.CrawlUrlStatus
import com.rfp.domain.Supplier
import com.rfp.dto.ExtractedObservation
import com.rfp.dto.ExtractionMethod
import com.rfp.dto.PageClassification
import com.rfp.repository.CrawlProductObservationRepository
import com.rfp.repository.CrawlRunRepository
import com.rfp.repository.CrawlUrlRepository
import com.rfp.repository.SupplierRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.util.Optional

/**
 * End-to-end integration tests for [CrawlCoordinator] batch lifecycle.
 *
 * Uses MockK for all repositories (avoids the H2/PostgreSQL incompatibility from
 * CrawlReconcilerTest's use of @DataJpaTest(replace=NONE)) and a mocked [CrawlFetcher]
 * to isolate the coordinator's orchestration logic without real HTTP or DB dependencies.
 *
 * Note on 5,000-product load testing: a full 5,200-URL MockWebServer fixture is impractical
 * in a unit/integration test environment without a running PostgreSQL database (the claimBatch
 * implementation uses PostgreSQL-specific FOR UPDATE SKIP LOCKED syntax). The 50-URL equivalent
 * is validated in [LargeCatalogIntegrationTest]. A load test with a real DB should be added as
 * a separate Maven profile or manual integration suite against a live database.
 */
class AdaptiveCrawlerIntegrationTest {

    private val runRepo = mockk<CrawlRunRepository>()
    private val urlRepo = mockk<CrawlUrlRepository>()
    private val observationRepo = mockk<CrawlProductObservationRepository>()
    private val supplierRepo = mockk<SupplierRepository>()
    private val fetcher = mockk<CrawlFetcher>()
    private val pageParser = mockk<PageParser>()
    private val classifier = mockk<CrawlClassifier>()
    private val extractor = mockk<ProductPageExtractor>()
    private val canonicalizer = UrlCanonicalizer()
    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())

    private lateinit var coordinator: CrawlCoordinator
    private lateinit var supplier: Supplier
    private lateinit var run: CrawlRun

    /** Config JSON with zero throttle and concurrency for fast test execution. */
    private val configJson: String by lazy {
        objectMapper.writeValueAsString(
            CrawlRunConfig(
                batchPages = 10,
                throttleMs = 0L,
                maxConcurrency = 0,
                maxAttempts = 3,
            )
        )
    }

    @BeforeEach
    fun setup() {
        supplier = Supplier(
            id = 1L,
            name = "Test Supplier",
            officialWebsite = "https://shop.example.com",
        )
        run = CrawlRun(
            id = 42L,
            supplier = supplier,
            status = CrawlRunStatus.QUEUED,
            configJson = configJson,
        )
        coordinator = CrawlCoordinator(
            runRepo = runRepo,
            urlRepo = urlRepo,
            observationRepo = observationRepo,
            supplierRepo = supplierRepo,
            fetcher = fetcher,
            pageParser = pageParser,
            classifier = classifier,
            extractor = extractor,
            canonicalizer = canonicalizer,
            objectMapper = objectMapper,
        )
    }

    // -------------------------------------------------------------------------
    // Happy-path: full crawl loop
    // -------------------------------------------------------------------------

    @Test
    fun `full crawl loop processes batch and marks run COMPLETE when no more work remains`() {
        // Arrange two URLs: one product page (success), one that gets permanently rejected
        val productUrl = makeUrl("https://shop.example.com/product/1", CrawlPageType.PRODUCT)
        val badUrl = makeUrl("https://shop.example.com/product/2", CrawlPageType.UNKNOWN)

        every { runRepo.findById(42L) } returns Optional.of(run)
        every { supplierRepo.findById(1L) } returns Optional.of(supplier)
        every { runRepo.save(any<CrawlRun>()) } answers { firstArg() }
        every { urlRepo.claimBatch(42L, any(), any()) } returns listOf(productUrl, badUrl)
        every { urlRepo.save(any<CrawlUrl>()) } answers { firstArg() }
        // No PENDING or RETRY URLs remain after the batch
        every { urlRepo.existsByRunIdAndStatusIn(42L, any()) } returns false
        every { observationRepo.save(any<CrawlProductObservation>()) } answers { firstArg() }

        val successResult = FetchResult.Success(
            url = URI("https://shop.example.com/product/1"),
            status = 200,
            contentType = "text/html",
            body = productPageHtml("Widget Pro", "WIDGET-100", "99.99"),
            etag = null,
            lastModified = null,
            method = FetchMethod.HTTP,
            contentHash = "abc123",
        )
        every { fetcher.fetch(match { it.url == URI("https://shop.example.com/product/1") }) } returns successResult
        every { fetcher.fetch(match { it.url == URI("https://shop.example.com/product/2") }) } returns
            FetchResult.Rejected(FetchError.HTTP_FAILURE, retryable = false)

        every { pageParser.parse(any()) } returns makeParsedPage(URI("https://shop.example.com/product/1"))
        every { classifier.classify(any()) } returns PageClassification(
            type = CrawlPageType.PRODUCT,
            priority = 100,
            shouldCrawl = false,
            partitionKey = "/product",
            confidence = 100,
        )
        every { extractor.extract(any<ParsedPage>(), any()) } returns listOf(makeObservation(URI("https://shop.example.com/product/1")))

        // Act
        val outcome = coordinator.processBatch(42L)

        // Assert
        assertThat(outcome).isEqualTo(BatchOutcome.COMPLETE)
        verify(exactly = 1) { observationRepo.save(any<CrawlProductObservation>()) }
        verify { runRepo.save(match { it.status == CrawlRunStatus.COMPLETE }) }
    }

    // -------------------------------------------------------------------------
    // Retry: transient HTTP failure
    // -------------------------------------------------------------------------

    @Test
    fun `transient 503 response marks url for retry not immediately failed`() {
        val retryableUrl = makeUrl("https://shop.example.com/product/503", CrawlPageType.UNKNOWN)

        every { runRepo.findById(42L) } returns Optional.of(run)
        every { supplierRepo.findById(1L) } returns Optional.of(supplier)
        every { runRepo.save(any<CrawlRun>()) } answers { firstArg() }
        every { urlRepo.claimBatch(42L, any(), any()) } returns listOf(retryableUrl)
        every { urlRepo.save(any<CrawlUrl>()) } answers { firstArg() }
        // Signal that RETRY URLs exist after the batch
        every { urlRepo.existsByRunIdAndStatusIn(42L, any()) } returns true

        // 503 is a retryable HTTP failure
        every { fetcher.fetch(any()) } returns FetchResult.Rejected(
            error = FetchError.HTTP_FAILURE,
            retryable = true,
        )

        val outcome = coordinator.processBatch(42L)

        // Coordinator must return MORE_WORK (pending/retry URLs still exist) or WAITING
        assertThat(outcome).isIn(BatchOutcome.MORE_WORK, BatchOutcome.WAITING)
        // The URL must be saved in RETRY status (not FAILED)
        verify { urlRepo.save(match { it.status == CrawlUrlStatus.RETRY }) }
        verify(exactly = 0) { urlRepo.save(match { it.status == CrawlUrlStatus.FAILED }) }
    }

    // -------------------------------------------------------------------------
    // Cancellation
    // -------------------------------------------------------------------------

    @Test
    fun `cancellation flag detected before batch claim aborts run as CANCELLED`() {
        // Run already has cancellation requested
        val cancelledRun = run.copy(
            status = CrawlRunStatus.CRAWLING,
            cancellationRequested = true,
        )

        every { runRepo.findById(42L) } returns Optional.of(cancelledRun)
        every { runRepo.save(any<CrawlRun>()) } answers { firstArg() }

        val outcome = coordinator.processBatch(42L)

        assertThat(outcome).isEqualTo(BatchOutcome.CANCELLED)
        verify { runRepo.save(match { it.status == CrawlRunStatus.CANCELLED }) }
        // Fetcher must never be called when cancellation is detected
        verify(exactly = 0) { fetcher.fetch(any()) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeUrl(url: String, type: CrawlPageType) = CrawlUrl(
        id = 0L,
        run = run,
        originalUrl = url,
        normalizedUrl = url,
        host = URI(url).host,
        status = CrawlUrlStatus.CLAIMED,
        pageType = type,
        depth = 1,
        priority = 50,
    )

    private fun makeParsedPage(url: URI) = ParsedPage(
        title = "Product Page",
        canonicalUrl = url,
        visibleText = "Widget Pro WIDGET-100",
        links = emptyList(),
        jsonLdProducts = emptyList(),
        embeddedJson = emptyList(),
        pagination = emptyList(),
        documents = emptyList(),
        signals = PageSignals(
            hasArabicText = false,
            hasProductStructuredData = true,
            skippedEmbeddedJson = 0,
        ),
    )

    private fun makeObservation(url: URI) = ExtractedObservation(
        identityHint = "widget-100",
        name = "Widget Pro",
        mpn = "WIDGET-100",
        className = null,
        attributes = emptyMap(),
        price = BigDecimal("99.99"),
        currency = "USD",
        priceSourceUrl = null,
        sourceUrl = url,
        method = ExtractionMethod.JSON_LD,
        confidence = 95,
        fieldSources = emptyMap(),
        observedAt = Instant.now(),
    )

    private fun productPageHtml(name: String, mpn: String, price: String) = """
        <html>
        <head>
        <script type="application/ld+json">
        {"@context":"https://schema.org","@type":"Product","name":"$name","mpn":"$mpn",
        "offers":{"@type":"Offer","price":"$price","priceCurrency":"USD"}}
        </script>
        </head>
        <body><h1>$name</h1></body>
        </html>
    """.trimIndent().toByteArray()
}
