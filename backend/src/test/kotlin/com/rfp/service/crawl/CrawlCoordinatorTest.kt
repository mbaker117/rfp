package com.rfp.service.crawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.rfp.domain.CrawlPageType
import com.rfp.domain.CrawlRun
import com.rfp.domain.CrawlRunStatus
import com.rfp.domain.CrawlUrl
import com.rfp.domain.CrawlUrlStatus
import com.rfp.domain.Supplier
import com.rfp.dto.ExtractedObservation
import com.rfp.dto.ExtractionMethod
import com.rfp.dto.FieldSource
import com.rfp.dto.PageClassification
import com.rfp.repository.CrawlProductObservationRepository
import com.rfp.repository.CrawlRunRepository
import com.rfp.repository.CrawlUrlRepository
import com.rfp.repository.SupplierRepository
import com.rfp.repository.countPending
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.net.URI
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CrawlCoordinatorTest(
    @Autowired val runRepo: CrawlRunRepository,
    @Autowired val urlRepo: CrawlUrlRepository,
    @Autowired val observationRepo: CrawlProductObservationRepository,
    @Autowired val supplierRepo: SupplierRepository,
) {
    private val fetcher: CrawlFetcher = mockk()
    private val pageParser: PageParser = mockk()
    private val classifier: CrawlClassifier = mockk()
    private val extractor: ProductPageExtractor = mockk()
    private val sitemapParser: SitemapParser = mockk(relaxed = true)
    private val crawlMetrics: CrawlMetrics = mockk(relaxed = true)
    private val completenessService: CrawlCompletenessService = mockk(relaxed = true)
    private val crawlReconciler: CrawlReconciler = mockk(relaxed = true)

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())

    private lateinit var coordinator: CrawlCoordinator
    private lateinit var supplier: Supplier

    // Default fetch result: non-retryable rejection (simulates a 404 / blocked request)
    private val rejectedFetch = FetchResult.Rejected(FetchError.HTTP_FAILURE, retryable = false)

    @BeforeEach
    fun setup() {
        coordinator = CrawlCoordinator(
            runRepo = runRepo,
            urlRepo = urlRepo,
            observationRepo = observationRepo,
            supplierRepo = supplierRepo,
            fetcher = fetcher,
            pageParser = pageParser,
            classifier = classifier,
            extractor = extractor,
            canonicalizer = UrlCanonicalizer(),
            sitemapParser = sitemapParser,
            crawlMetrics = crawlMetrics,
            completenessService = completenessService,
            crawlReconciler = crawlReconciler,
            objectMapper = objectMapper,
        )

        supplier = supplierRepo.save(
            Supplier(
                name = "Test Supplier",
                officialWebsite = "https://shop.example.com",
            )
        )
    }

    // -------------------------------------------------------------------------
    // enqueue + budget exhaustion
    // -------------------------------------------------------------------------

    @Test
    fun `enqueue seeds homepage sitemap and robots_txt as pending URLs`() {
        val runId = coordinator.enqueue(supplier.id)

        val pending = urlRepo.countPending(runId)
        assertThat(pending).isEqualTo(3) // homepage + sitemap.xml + robots.txt
    }

    @Test
    fun `budget exhaustion checkpoints pending URLs and schedules another batch`() {
        every { fetcher.fetch(any()) } returns rejectedFetch

        // 3 seeds, batch of 2 → 1 stays pending
        val runId = coordinator.enqueue(supplier.id, CrawlRunOverrides(batchPages = 2))
        val outcome = coordinator.processBatch(runId)

        assertThat(outcome).isEqualTo(BatchOutcome.MORE_WORK)
        assertThat(urlRepo.countPending(runId)).isGreaterThan(0)
        assertThat(runRepo.findById(runId).get().status).isEqualTo(CrawlRunStatus.CRAWLING)
    }

    @Test
    fun `batches never claim more URLs than the configured batchPages limit`() {
        every { fetcher.fetch(any()) } returns rejectedFetch

        val runId = coordinator.enqueue(supplier.id, CrawlRunOverrides(batchPages = 2))
        coordinator.processBatch(runId)

        // 3 seeds: 2 should be FAILED (processed this batch), 1 still PENDING
        val failedCount = urlRepo.countByRunIdAndStatus(runId, CrawlUrlStatus.FAILED)
        val pendingCount = urlRepo.countPending(runId)
        assertThat(failedCount + pendingCount).isEqualTo(3)
        assertThat(failedCount).isEqualTo(2)
        assertThat(pendingCount).isEqualTo(1)
    }

    // -------------------------------------------------------------------------
    // Multi-batch run to COMPLETE
    // -------------------------------------------------------------------------

    @Test
    fun `multi-batch run reaches COMPLETE when all seed URLs are consumed`() {
        every { fetcher.fetch(any()) } returns rejectedFetch

        val runId = coordinator.enqueue(supplier.id, CrawlRunOverrides(batchPages = 2))

        val batch1 = coordinator.processBatch(runId)
        assertThat(batch1).isEqualTo(BatchOutcome.MORE_WORK)

        val batch2 = coordinator.processBatch(runId)
        assertThat(batch2).isEqualTo(BatchOutcome.COMPLETE)

        val run = runRepo.findById(runId).get()
        assertThat(run.status).isEqualTo(CrawlRunStatus.COMPLETE)
        assertThat(run.finishedAt).isNotNull()
    }

    // -------------------------------------------------------------------------
    // State machine: QUEUED → CRAWLING on first batch
    // -------------------------------------------------------------------------

    @Test
    fun `first processBatch transitions run from QUEUED to CRAWLING`() {
        every { fetcher.fetch(any()) } returns rejectedFetch

        val runId = coordinator.enqueue(supplier.id, CrawlRunOverrides(batchPages = 2))
        assertThat(runRepo.findById(runId).get().status).isEqualTo(CrawlRunStatus.QUEUED)

        coordinator.processBatch(runId)

        // After the first batch, still CRAWLING (pending work remains)
        assertThat(runRepo.findById(runId).get().status).isEqualTo(CrawlRunStatus.CRAWLING)
        assertThat(runRepo.findById(runId).get().startedAt).isNotNull()
    }

    // -------------------------------------------------------------------------
    // Cancellation
    // -------------------------------------------------------------------------

    @Test
    fun `cancellation before claiming returns CANCELLED and does not claim any URLs`() {
        val runId = coordinator.enqueue(supplier.id)
        coordinator.cancel(runId)

        val outcome = coordinator.processBatch(runId)

        assertThat(outcome).isEqualTo(BatchOutcome.CANCELLED)
        assertThat(runRepo.findById(runId).get().status).isEqualTo(CrawlRunStatus.CANCELLED)
        // All URLs should still be PENDING (nothing was claimed)
        assertThat(urlRepo.countPending(runId)).isEqualTo(3)
    }

    // -------------------------------------------------------------------------
    // Pending state survives new coordinator (no in-memory state)
    // -------------------------------------------------------------------------

    @Test
    fun `pending frontier URLs survive creating a new coordinator instance`() {
        every { fetcher.fetch(any()) } returns rejectedFetch

        val runId = coordinator.enqueue(supplier.id, CrawlRunOverrides(batchPages = 2))
        // Process one batch with the original coordinator
        coordinator.processBatch(runId)

        // Build a brand-new coordinator (simulates a worker restart)
        val freshCoordinator = CrawlCoordinator(
            runRepo = runRepo,
            urlRepo = urlRepo,
            observationRepo = observationRepo,
            supplierRepo = supplierRepo,
            fetcher = fetcher,
            pageParser = pageParser,
            classifier = classifier,
            extractor = extractor,
            canonicalizer = UrlCanonicalizer(),
            sitemapParser = sitemapParser,
            crawlMetrics = crawlMetrics,
            completenessService = completenessService,
            crawlReconciler = crawlReconciler,
            objectMapper = objectMapper,
        )

        // Fresh coordinator can pick up where the previous one left off
        val outcome = freshCoordinator.processBatch(runId)
        assertThat(outcome).isEqualTo(BatchOutcome.COMPLETE)
    }

    // -------------------------------------------------------------------------
    // Expired claim recovery
    // -------------------------------------------------------------------------

    @Test
    fun `expired claims return to pending after worker restart`() {
        val runId = coordinator.enqueue(supplier.id)
        val run = runRepo.findById(runId).get()

        // Simulate an abandoned claim: create a CLAIMED URL with a claimedAt far in the past
        val staleClaimedAt = Instant.now().minusSeconds(20 * 60) // 20 minutes ago
        val url = urlRepo.save(
            CrawlUrl(
                run = run,
                originalUrl = "https://shop.example.com/stale",
                normalizedUrl = "https://shop.example.com/stale",
                host = "shop.example.com",
                status = CrawlUrlStatus.CLAIMED,
                pageType = CrawlPageType.UNKNOWN,
                depth = 1,
                priority = 60,
                claimedAt = staleClaimedAt,
            )
        )

        coordinator.recoverAbandonedClaims(runId, Instant.now())

        val recovered = urlRepo.findById(url.id).get()
        assertThat(recovered.status).isEqualTo(CrawlUrlStatus.PENDING)
        assertThat(recovered.claimedAt).isNull()
    }

    @Test
    fun `recently claimed URLs are not returned to pending by recoverAbandonedClaims`() {
        val runId = coordinator.enqueue(supplier.id)
        val run = runRepo.findById(runId).get()

        // Claim time is recent — within the timeout window
        val recentClaimedAt = Instant.now().minusSeconds(60) // 1 minute ago
        val url = urlRepo.save(
            CrawlUrl(
                run = run,
                originalUrl = "https://shop.example.com/recent",
                normalizedUrl = "https://shop.example.com/recent",
                host = "shop.example.com",
                status = CrawlUrlStatus.CLAIMED,
                pageType = CrawlPageType.UNKNOWN,
                depth = 1,
                priority = 60,
                claimedAt = recentClaimedAt,
            )
        )

        coordinator.recoverAbandonedClaims(runId, Instant.now())

        val unchanged = urlRepo.findById(url.id).get()
        assertThat(unchanged.status).isEqualTo(CrawlUrlStatus.CLAIMED) // not recovered
    }

    // -------------------------------------------------------------------------
    // Product observation storage
    // -------------------------------------------------------------------------

    @Test
    fun `observation is stored for a product page with source URL and extraction method`() {
        val now = Instant.now()
        val sourceUri = URI("https://shop.example.com/products/meter-1000")

        val observation = ExtractedObservation(
            identityHint = "METER-1000",
            name = "Digital Meter 1000",
            mpn = "METER-1000",
            className = null,
            attributes = mapOf("description" to "Precision bench meter"),
            price = null,
            currency = null,
            priceSourceUrl = null,
            sourceUrl = sourceUri,
            method = ExtractionMethod.PRODUCT_PAGE,
            confidence = 85,
            fieldSources = mapOf(
                "name" to FieldSource(sourceUri, ExtractionMethod.PRODUCT_PAGE),
            ),
            observedAt = now,
        )

        val fakePage = ParsedPage(
            title = "Digital Meter 1000",
            canonicalUrl = sourceUri,
            visibleText = "METER-1000 bench multimeter",
            links = emptyList(),
            jsonLdProducts = emptyList(),
            embeddedJson = emptyList(),
            pagination = emptyList(),
            documents = emptyList(),
            signals = PageSignals(hasArabicText = false, hasProductStructuredData = false, skippedEmbeddedJson = 0),
        )

        val productClassification = PageClassification(
            type = CrawlPageType.PRODUCT,
            priority = 100,
            shouldCrawl = false, // avoid link enqueueing complexity
            partitionKey = "/products",
            confidence = 100,
        )

        every { fetcher.fetch(any()) } returns FetchResult.Success(
            url = sourceUri,
            status = 200,
            contentType = "text/html",
            body = "<html><body>METER-1000 bench multimeter</body></html>".toByteArray(),
            etag = null,
            lastModified = null,
            method = FetchMethod.HTTP,
            contentHash = "abc123",
        )
        every { pageParser.parse(any()) } returns fakePage
        every { classifier.classify(any()) } returns productClassification
        every { extractor.extract(any<ParsedPage>(), any()) } returns listOf(observation)

        val runId = coordinator.enqueue(supplier.id)
        coordinator.processBatch(runId)

        val observationCount = observationRepo.countByRunId(runId)
        assertThat(observationCount).isGreaterThanOrEqualTo(1)

        val stored = observationRepo.findAll().first { it.run.id == runId }
        assertThat(stored.productName).isEqualTo("Digital Meter 1000")
        assertThat(stored.mpn).isEqualTo("METER-1000")
        assertThat(stored.sourceUrl).isEqualTo(sourceUri.toString())
        assertThat(stored.extractionMethod).isEqualTo("PRODUCT_PAGE")
        assertThat(stored.confidence).isEqualTo(85)
    }

    // -------------------------------------------------------------------------
    // retryFailed
    // -------------------------------------------------------------------------

    @Test
    fun `retryFailed resets FAILED URLs back to PENDING`() {
        every { fetcher.fetch(any()) } returns FetchResult.Rejected(FetchError.HTTP_FAILURE, retryable = false)

        val runId = coordinator.enqueue(supplier.id)
        // Drive to completion so all 3 seeds are FAILED
        repeat(2) { coordinator.processBatch(runId) }

        val failedBefore = urlRepo.countByRunIdAndStatus(runId, CrawlUrlStatus.FAILED)
        assertThat(failedBefore).isGreaterThan(0)

        coordinator.retryFailed(runId)

        val failedAfter = urlRepo.countByRunIdAndStatus(runId, CrawlUrlStatus.FAILED)
        assertThat(failedAfter).isEqualTo(0)
        assertThat(urlRepo.countPending(runId)).isEqualTo(failedBefore)
    }

    // -------------------------------------------------------------------------
    // Config ceiling enforcement
    // -------------------------------------------------------------------------

    @Test
    fun `overrides cannot raise batchPages above the ceiling`() {
        val runId = coordinator.enqueue(supplier.id, CrawlRunOverrides(batchPages = 9999))

        val run = runRepo.findById(runId).get()
        val config = objectMapper.readValue(run.configJson, CrawlRunConfig::class.java)
        assertThat(config.batchPages).isEqualTo(CrawlRunConfig.MAX_BATCH_PAGES)
    }

    @Test
    fun `supplier crawlBatchPages is capped at the ceiling`() {
        val cappedSupplier = supplierRepo.save(
            Supplier(
                name = "Capped Supplier",
                officialWebsite = "https://capped.example.com",
                crawlBatchPages = 9999,
            )
        )

        val runId = coordinator.enqueue(cappedSupplier.id)
        val run = runRepo.findById(runId).get()
        val config = objectMapper.readValue(run.configJson, CrawlRunConfig::class.java)
        assertThat(config.batchPages).isEqualTo(CrawlRunConfig.MAX_BATCH_PAGES)
    }

    @Test
    fun `supplier crawlBatchPages lower than ceiling is respected`() {
        val restrictedSupplier = supplierRepo.save(
            Supplier(
                name = "Restricted Supplier",
                officialWebsite = "https://restricted.example.com",
                crawlBatchPages = 10,
            )
        )

        val runId = coordinator.enqueue(restrictedSupplier.id)
        val run = runRepo.findById(runId).get()
        val config = objectMapper.readValue(run.configJson, CrawlRunConfig::class.java)
        assertThat(config.batchPages).isEqualTo(10)
    }
}
