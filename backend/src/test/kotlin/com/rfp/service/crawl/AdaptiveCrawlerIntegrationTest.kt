package com.rfp.service.crawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.rfp.domain.CrawlPageType
import com.rfp.domain.CrawlProductObservation
import com.rfp.domain.CrawlRun
import com.rfp.domain.CrawlRunStatus
import com.rfp.domain.CrawlUrl
import com.rfp.domain.CrawlUrlStatus
import com.rfp.domain.Product
import com.rfp.domain.Supplier
import com.rfp.dto.ExtractedObservation
import com.rfp.dto.ExtractionMethod
import com.rfp.dto.PageClassification
import com.rfp.repository.CrawlProductObservationRepository
import com.rfp.repository.CrawlRunRepository
import com.rfp.repository.CrawlUrlRepository
import com.rfp.repository.ProductPriceHistoryRepository
import com.rfp.repository.ProductPriceRepository
import com.rfp.repository.ProductRepository
import com.rfp.repository.SupplierRepository
import com.rfp.service.LlmService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetAddress
import java.net.URI
import java.time.Instant
import java.util.Optional

/**
 * End-to-end integration tests for [CrawlCoordinator] batch lifecycle.
 *
 * Uses MockK for all repositories (avoids the H2/PostgreSQL incompatibility from
 * CrawlReconcilerTest's use of @DataJpaTest(replace=NONE)) and — for the primary
 * happy-path test — a real [CrawlFetcher] backed by [MockWebServer] to validate the
 * full HTTP fetch → parse → classify → extract → save pipeline without any mocked
 * HTTP layer.
 *
 * The cancellation and 503-retry tests keep a mocked [CrawlFetcher] to isolate the
 * coordinator's orchestration logic.
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

    /** Real HTTP server used by the real-fetcher E2E test. */
    private lateinit var server: MockWebServer

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
        server = MockWebServer()
        server.start()

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

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    // -------------------------------------------------------------------------
    // Happy-path: full crawl loop with real HTTP fetcher + MockWebServer
    // -------------------------------------------------------------------------

    @Test
    fun `full crawl loop with real fetcher processes product page and saves observation`() {
        // Arrange: supplier whose website is served by MockWebServer
        val serverAddress = InetAddress.getByName(server.hostName)
        val permissivePolicy = DestinationValidator { _, _, _ ->
            PolicyDecision.Allowed(listOf(serverAddress))
        }
        val httpClient = OkHttpClient()
        val realFetcher = CrawlFetcher(
            client = httpClient,
            crawlPolicy = permissivePolicy,
            canonicalizer = canonicalizer,
            robotsPolicy = RobotsPolicyService(
                client = httpClient,
                destinationValidator = permissivePolicy,
                sleeper = RetrySleeper { },
                jitterMillis = { 0 },
            ),
        )

        // LlmService mock: maxCrawlCandidateCharacters satisfies ProductPageExtractor's init check.
        // JSON-LD extraction never calls the LLM — the mock is only to satisfy the constructor.
        val mockLlm = mockk<LlmService>()
        every { mockLlm.maxCrawlCandidateCharacters } returns 12_000

        val serverSupplier = Supplier(
            id = 1L,
            name = "Test Supplier",
            officialWebsite = server.url("/").toString(),
        )
        val serverRun = CrawlRun(
            id = 42L,
            supplier = serverSupplier,
            status = CrawlRunStatus.QUEUED,
            configJson = configJson,
        )

        // MockWebServer queue: robots.txt first (fetched by RobotsPolicyService), then product page
        server.enqueue(MockResponse().setBody("User-agent: *\nAllow: /"))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(String(productPageHtml("Widget Pro", "WIDGET-100", "99.99"))),
        )

        val productUrl = CrawlUrl(
            id = 1L,
            run = serverRun,
            originalUrl = server.url("/product/1").toString(),
            normalizedUrl = server.url("/product/1").toString(),
            host = server.hostName,
            status = CrawlUrlStatus.CLAIMED,
            pageType = CrawlPageType.PRODUCT,
            depth = 1,
            priority = 50,
        )

        every { runRepo.findById(42L) } returns Optional.of(serverRun)
        every { supplierRepo.findById(1L) } returns Optional.of(serverSupplier)
        every { runRepo.save(any<CrawlRun>()) } answers { firstArg() }
        every { urlRepo.claimBatch(42L, any(), any()) } returns listOf(productUrl)
        every { urlRepo.save(any<CrawlUrl>()) } answers { firstArg() }
        every { urlRepo.existsByRunIdAndStatusIn(42L, any()) } returns false
        every { observationRepo.save(any<CrawlProductObservation>()) } answers { firstArg() }

        val realCoordinator = CrawlCoordinator(
            runRepo = runRepo,
            urlRepo = urlRepo,
            observationRepo = observationRepo,
            supplierRepo = supplierRepo,
            fetcher = realFetcher,
            pageParser = PageParser(canonicalizer),
            classifier = CrawlClassifier(null),  // JSON-LD path never calls LLM
            extractor = ProductPageExtractor(mockLlm),
            canonicalizer = canonicalizer,
            objectMapper = objectMapper,
        )

        // Act
        val outcome = realCoordinator.processBatch(42L)

        // Assert: observation saved and run marked COMPLETE
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
    // Retry: 503 eventually succeeds on the second batch
    // -------------------------------------------------------------------------

    @Test
    fun `transient 503 on first batch eventually succeeds with 200 on second batch`() {
        val retryUrl = makeUrl("https://shop.example.com/product/503", CrawlPageType.UNKNOWN)
        val successBody = productPageHtml("Widget Pro", "WIDGET-100", "99.99")

        every { runRepo.findById(42L) } returns Optional.of(run)
        every { supplierRepo.findById(1L) } returns Optional.of(supplier)
        every { runRepo.save(any<CrawlRun>()) } answers { firstArg() }
        every { urlRepo.claimBatch(42L, any(), any()) } returnsMany listOf(
            listOf(retryUrl),
            listOf(retryUrl),
        )
        every { urlRepo.save(any<CrawlUrl>()) } answers { firstArg() }
        // First batch: RETRY still exists; second batch: all done
        every { urlRepo.existsByRunIdAndStatusIn(42L, any()) } returnsMany listOf(true, false)
        every { observationRepo.save(any<CrawlProductObservation>()) } answers { firstArg() }

        every { fetcher.fetch(any()) } returnsMany listOf(
            FetchResult.Rejected(FetchError.HTTP_FAILURE, retryable = true),  // batch 1 → 503
            FetchResult.Success(                                               // batch 2 → 200
                url = URI("https://shop.example.com/product/503"),
                status = 200, contentType = "text/html",
                body = successBody, etag = null, lastModified = null,
                method = FetchMethod.HTTP, contentHash = "hash200",
            ),
        )
        every { pageParser.parse(any()) } returns
            makeParsedPage(URI("https://shop.example.com/product/503"))
        every { classifier.classify(any()) } returns PageClassification(
            type = CrawlPageType.PRODUCT, priority = 100,
            shouldCrawl = false, partitionKey = "/product", confidence = 100,
        )
        every { extractor.extract(any<ParsedPage>(), any()) } returns
            listOf(makeObservation(URI("https://shop.example.com/product/503")))

        // First batch: 503 → URL saved as RETRY, outcome is MORE_WORK or WAITING
        val firstOutcome = coordinator.processBatch(42L)
        assertThat(firstOutcome).isIn(BatchOutcome.MORE_WORK, BatchOutcome.WAITING)
        verify { urlRepo.save(match { it.status == CrawlUrlStatus.RETRY }) }

        // Second batch: 200 → observation saved, run COMPLETE
        val secondOutcome = coordinator.processBatch(42L)
        assertThat(secondOutcome).isEqualTo(BatchOutcome.COMPLETE)
        verify(exactly = 1) { observationRepo.save(any<CrawlProductObservation>()) }
        verify { runRepo.save(match { it.status == CrawlRunStatus.COMPLETE }) }
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
        // Fetcher and claimBatch must never be called when cancellation is detected
        verify(exactly = 0) { fetcher.fetch(any()) }
        verify(exactly = 0) { urlRepo.claimBatch(any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Critical 1: reconciler upserts products from saved observations
    // -------------------------------------------------------------------------

    @Test
    fun `reconciler upserts products from saved observations after run completes`() {
        val productRepo = mockk<ProductRepository>()
        val priceRepo = mockk<ProductPriceRepository>()
        val priceHistoryRepo = mockk<ProductPriceHistoryRepository>()
        val completenessService = mockk<CrawlCompletenessService>()

        val completeRun = CrawlRun(
            id = 42L,
            supplier = supplier,
            status = CrawlRunStatus.COMPLETE,
            configJson = configJson,
            reconciledAt = null,
        )
        val obs = CrawlProductObservation(
            id = 1L,
            run = completeRun,
            supplier = supplier,
            identityKey = "widget-100",
            productName = "Widget Pro",
            mpn = "WIDGET-100",
            price = BigDecimal("99.99"),
            currency = "USD",
            sourceUrl = "https://shop.example.com/product/1",
            extractionMethod = "JSON_LD",
            confidence = 95,
            observedAt = Instant.now(),
        )
        val savedProduct = Product(
            id = 999L,
            supplier = supplier,
            name = "Widget Pro",
            mpn = "WIDGET-100",
            source = "scrape",
            identityKey = "mpn:WIDGET100",
        )

        every { runRepo.findById(42L) } returns Optional.of(completeRun)
        every { supplierRepo.getReferenceById(1L) } returns supplier
        every { completenessService.evaluate(42L) } returns
            CompletenessResult(canReconcile = true, score = 100, reason = null)
        every { observationRepo.findByRunId(42L) } returns listOf(obs)
        every { productRepo.findBySupplierIdAndIdentityKey(1L, "mpn:WIDGET100") } returns null
        every { productRepo.findBySupplierIdAndMpnIgnoreCase(1L, "WIDGET-100") } returns null
        every { productRepo.save(any<Product>()) } returns savedProduct
        every { productRepo.findBySupplierId(1L) } returns emptyList()
        every { priceRepo.findById(any()) } returns Optional.empty()
        every { priceRepo.save(any()) } answers { firstArg() }
        every { runRepo.save(any<CrawlRun>()) } answers { firstArg() }

        val reconciler = CrawlReconciler(
            completenessService = completenessService,
            identityService = ProductIdentityService(),
            merger = ObservationMerger(),
            observationRepo = observationRepo,
            productRepo = productRepo,
            priceRepo = priceRepo,
            priceHistoryRepo = priceHistoryRepo,
            runRepo = runRepo,
            supplierRepo = supplierRepo,
        )

        val result = reconciler.reconcile(42L)

        assertThat(result.skippedReason).isNull()
        assertThat(result.insertedCount).isEqualTo(1)
        assertThat(result.updatedCount).isEqualTo(0)
        verify { productRepo.save(match { it.name == "Widget Pro" && it.mpn == "WIDGET-100" }) }
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
