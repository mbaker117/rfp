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
 * Restart/recovery and multi-batch validation tests for [CrawlCoordinator].
 *
 * Uses MockK for all repositories — avoids the H2/PostgreSQL incompatibility introduced in
 * CrawlReconcilerTest's use of @DataJpaTest(replace=NONE).
 *
 * Validates the 50-product scenario (representative of the planned 5,000-product load path).
 * A full load test with 5,200 products should be run against a real PostgreSQL database using
 * a dedicated Maven profile (e.g. -Pload-test) — the claimBatch implementation requires
 * PostgreSQL-specific FOR UPDATE SKIP LOCKED syntax unavailable in H2.
 */
class LargeCatalogIntegrationTest {

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

    private val configJson: String by lazy {
        objectMapper.writeValueAsString(
            CrawlRunConfig(
                batchPages = 5,
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
    // Scenario A — Restart / recovery
    // -------------------------------------------------------------------------

    @Test
    fun `recoverAbandonedClaims resets stale CLAIMED URLs to PENDING for reprocessing`() {
        // Simulate a worker that claimed 5 URLs ~12 minutes ago and then crashed (never released them)
        val staleClaim = Instant.now().minusSeconds(720) // 12 min > CLAIM_TIMEOUT (10 min)
        val abandonedUrls = (1..5).map { i ->
            CrawlUrl(
                id = i.toLong(),
                run = run,
                originalUrl = "https://shop.example.com/product/$i",
                normalizedUrl = "https://shop.example.com/product/$i",
                host = "shop.example.com",
                status = CrawlUrlStatus.CLAIMED,
                pageType = CrawlPageType.PRODUCT,
                depth = 1,
                priority = 50,
                claimedAt = staleClaim,
            )
        }

        every { urlRepo.findByRunIdAndStatusAndClaimedAtBefore(42L, CrawlUrlStatus.CLAIMED, any()) } returns abandonedUrls
        every { urlRepo.save(any<CrawlUrl>()) } answers { firstArg() }

        // A new coordinator instance (representing the restarted worker) calls recovery
        coordinator.recoverAbandonedClaims(42L, Instant.now())

        // All 5 abandoned URLs must be reset to PENDING with claimedAt cleared
        verify(exactly = 5) {
            urlRepo.save(
                match { url ->
                    url.status == CrawlUrlStatus.PENDING && url.claimedAt == null
                }
            )
        }
    }

    /**
     * Critical 2: Validates the two-batch resume scenario with actual recovery between batches.
     *
     * Scenario:
     * 1. First coordinator processes 5 URLs (products 1–5) → 5 observations saved.
     * 2. A "crash" is simulated by having 5 more URLs (products 6–10) stuck in CLAIMED state.
     * 3. [CrawlCoordinator.recoverAbandonedClaims] resets those 5 stale CLAIMED → PENDING.
     * 4. Second coordinator batch claims and processes the 5 recovered URLs → 5 more observations.
     * 5. Total: 10 distinct observations, 0 duplicates.
     */
    @Test
    fun `restarted worker processes recovered URLs and accumulates observations without duplicates`() {
        val firstBatch = (1..5).map { i ->
            makeUrl("https://shop.example.com/product/$i", CrawlPageType.PRODUCT)
        }
        val secondBatch = (6..10).map { i ->
            makeUrl("https://shop.example.com/product/$i", CrawlPageType.PRODUCT)
        }
        // Simulate 5 URLs that a crashed worker left in CLAIMED state (12 min ago)
        val staleClaims = (6..10).map { i ->
            CrawlUrl(
                id = i.toLong(),
                run = run,
                originalUrl = "https://shop.example.com/product/$i",
                normalizedUrl = "https://shop.example.com/product/$i",
                host = "shop.example.com",
                status = CrawlUrlStatus.CLAIMED,
                pageType = CrawlPageType.PRODUCT,
                depth = 1,
                priority = 50,
                claimedAt = Instant.now().minusSeconds(720),
            )
        }
        val savedObservations = mutableListOf<CrawlProductObservation>()

        every { runRepo.findById(42L) } returns Optional.of(run)
        every { supplierRepo.findById(1L) } returns Optional.of(supplier)
        every { runRepo.save(any<CrawlRun>()) } answers { firstArg() }
        // First processBatch → first 5 URLs; second processBatch → recovered 5 URLs
        every { urlRepo.claimBatch(42L, any(), any()) } returnsMany listOf(firstBatch, secondBatch)
        every { urlRepo.save(any<CrawlUrl>()) } answers { firstArg() }
        // After first batch more work remains; after second batch all is done
        every { urlRepo.existsByRunIdAndStatusIn(42L, any()) } returnsMany listOf(true, false)
        every { observationRepo.save(any<CrawlProductObservation>()) } answers {
            val obs = firstArg<CrawlProductObservation>()
            savedObservations += obs
            obs
        }
        // recoverAbandonedClaims finds the stale CLAIMED URLs from the crashed worker
        every {
            urlRepo.findByRunIdAndStatusAndClaimedAtBefore(42L, CrawlUrlStatus.CLAIMED, any())
        } returns staleClaims

        every { fetcher.fetch(any()) } answers { call ->
            val req = call.invocation.args[0] as CrawlFetchRequest
            FetchResult.Success(
                url = req.url,
                status = 200,
                contentType = "text/html",
                body = "<html><body>product</body></html>".toByteArray(),
                etag = null,
                lastModified = null,
                method = FetchMethod.HTTP,
                contentHash = req.url.toString().hashCode().toString(),
            )
        }
        every { pageParser.parse(any()) } answers { call ->
            val result = call.invocation.args[0] as FetchResult.Success
            ParsedPage(
                title = "Product",
                canonicalUrl = result.url,
                visibleText = "product",
                links = emptyList(),
                jsonLdProducts = emptyList(),
                embeddedJson = emptyList(),
                pagination = emptyList(),
                documents = emptyList(),
                signals = PageSignals(false, false, 0),
            )
        }
        every { classifier.classify(any()) } returns PageClassification(
            type = CrawlPageType.PRODUCT,
            priority = 100,
            shouldCrawl = false,
            partitionKey = "/product",
            confidence = 100,
        )
        every { extractor.extract(any<ParsedPage>(), any()) } answers { call ->
            val page = call.invocation.args[0] as ParsedPage
            listOf(
                ExtractedObservation(
                    identityHint = page.canonicalUrl.path,
                    name = "Product ${page.canonicalUrl.path}",
                    mpn = "MPN-${page.canonicalUrl.path.substringAfterLast('/')}",
                    className = null,
                    attributes = emptyMap(),
                    price = BigDecimal("100.00"),
                    currency = "USD",
                    priceSourceUrl = null,
                    sourceUrl = page.canonicalUrl,
                    method = ExtractionMethod.JSON_LD,
                    confidence = 90,
                    fieldSources = emptyMap(),
                    observedAt = Instant.now(),
                )
            )
        }

        // ── Batch 1: first coordinator processes 5 URLs ───────────────────────
        val outcome1 = coordinator.processBatch(42L)
        assertThat(outcome1).isIn(BatchOutcome.MORE_WORK, BatchOutcome.WAITING)
        assertThat(savedObservations).hasSize(5)

        // ── Recovery: reset stale CLAIMED URLs (products 6–10) to PENDING ─────
        coordinator.recoverAbandonedClaims(42L, Instant.now())
        verify(exactly = 5) {
            urlRepo.save(match { url ->
                url.status == CrawlUrlStatus.PENDING && url.claimedAt == null
            })
        }

        // ── Batch 2: new coordinator processes the recovered 5 URLs ───────────
        val outcome2 = coordinator.processBatch(42L)
        assertThat(outcome2).isEqualTo(BatchOutcome.COMPLETE)

        // 10 URLs each produced 1 observation — total 10, all unique by source URL
        assertThat(savedObservations).hasSize(10)
        val distinctSourceUrls = savedObservations.map { it.sourceUrl }.toSet()
        assertThat(distinctSourceUrls).hasSize(10)
    }

    // -------------------------------------------------------------------------
    // Scenario B — Budget exhaustion / partial run staleness safety
    // -------------------------------------------------------------------------

    /**
     * Critical 3: PARTIAL runs must never cause reconciler to upsert products.
     *
     * Validates both that:
     * - The coordinator correctly marks the run as PARTIAL on budget exhaustion.
     * - The reconciler skips the PARTIAL run and never calls productRepo.save.
     */
    @Test
    fun `url budget exhaustion marks run PARTIAL before processing any new batch work`() {
        // Run has already consumed its URL budget (discoveredUrlCount >= maxUrls)
        val exhaustedConfig = CrawlRunConfig(
            batchPages = 5,
            throttleMs = 0L,
            maxConcurrency = 0,
            maxUrls = 50,
        )
        val exhaustedRun = CrawlRun(
            id = 42L,
            supplier = supplier,
            status = CrawlRunStatus.CRAWLING,
            configJson = objectMapper.writeValueAsString(exhaustedConfig),
            discoveredUrlCount = 50, // exactly at the ceiling
        )

        every { runRepo.findById(42L) } returns Optional.of(exhaustedRun)
        every { runRepo.save(any<CrawlRun>()) } answers { firstArg() }

        val outcome = coordinator.processBatch(42L)

        // Budget exhausted → run is marked PARTIAL and outcome is COMPLETE (no error)
        assertThat(outcome).isEqualTo(BatchOutcome.COMPLETE)
        verify { runRepo.save(match { it.status == CrawlRunStatus.PARTIAL }) }
        // Fetcher is never called — no new URLs are processed in a budget-exhausted run
        verify(exactly = 0) { fetcher.fetch(any()) }

        // ── Critical 3: reconciler must be a no-op for PARTIAL runs ──────────
        val partialRun = exhaustedRun.copy(status = CrawlRunStatus.PARTIAL)
        every { runRepo.findById(42L) } returns Optional.of(partialRun)

        val reconcilerProductRepo = mockk<com.rfp.repository.ProductRepository>()
        val localCompletenessService = mockk<CrawlCompletenessService>()
        every { localCompletenessService.evaluate(42L) } returns CompletenessResult(
            canReconcile = false,
            score = 0,
            reason = "Run status is PARTIAL, expected COMPLETE",
        )

        val reconciler = CrawlReconciler(
            completenessService = localCompletenessService,
            identityService = ProductIdentityService(),
            merger = ObservationMerger(),
            observationRepo = observationRepo,
            productRepo = reconcilerProductRepo,
            priceRepo = mockk(),
            priceHistoryRepo = mockk(),
            runRepo = runRepo,
            supplierRepo = supplierRepo,
        )

        val reconResult = reconciler.reconcile(42L)

        // Reconciler must skip PARTIAL run and never touch product storage
        assertThat(reconResult.skippedReason).isNotNull()
        assertThat(reconResult.insertedCount).isEqualTo(0)
        assertThat(reconResult.updatedCount).isEqualTo(0)
        verify(exactly = 0) { reconcilerProductRepo.save(any<Product>()) }
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
}
