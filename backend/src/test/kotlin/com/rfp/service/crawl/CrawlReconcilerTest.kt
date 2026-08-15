package com.rfp.service.crawl

import com.rfp.domain.CrawlPageType
import com.rfp.domain.CrawlProductObservation
import com.rfp.domain.CrawlRun
import com.rfp.domain.CrawlRunStatus
import com.rfp.domain.CrawlUrl
import com.rfp.domain.CrawlUrlStatus
import com.rfp.domain.Product
import com.rfp.domain.Supplier
import com.rfp.repository.CrawlProductObservationRepository
import com.rfp.repository.CrawlRunRepository
import com.rfp.repository.CrawlUrlRepository
import com.rfp.repository.ProductPriceHistoryRepository
import com.rfp.repository.ProductPriceRepository
import com.rfp.repository.ProductRepository
import com.rfp.repository.SupplierRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.math.BigDecimal
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CrawlReconcilerTest(
    @Autowired val runRepo: CrawlRunRepository,
    @Autowired val urlRepo: CrawlUrlRepository,
    @Autowired val observationRepo: CrawlProductObservationRepository,
    @Autowired val supplierRepo: SupplierRepository,
    @Autowired val productRepo: ProductRepository,
    @Autowired val priceRepo: ProductPriceRepository,
    @Autowired val priceHistoryRepo: ProductPriceHistoryRepository,
) {
    @PersistenceContext
    private lateinit var em: EntityManager

    private val identityService = ProductIdentityService()
    private val merger = ObservationMerger()

    private lateinit var reconciler: CrawlReconciler
    private lateinit var completenessService: CrawlCompletenessService
    private lateinit var supplier: Supplier

    @BeforeEach
    fun setup() {
        completenessService = CrawlCompletenessService(runRepo, urlRepo)
        reconciler = CrawlReconciler(
            completenessService = completenessService,
            identityService = identityService,
            merger = merger,
            observationRepo = observationRepo,
            productRepo = productRepo,
            priceRepo = priceRepo,
            priceHistoryRepo = priceHistoryRepo,
            runRepo = runRepo,
            supplierRepo = supplierRepo,
        )
        supplier = supplierRepo.save(
            Supplier(name = "Test Supplier", officialWebsite = "https://shop.test.com")
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeRun(status: CrawlRunStatus): CrawlRun = runRepo.save(
        CrawlRun(supplier = supplier, status = status, configJson = "{}")
    )

    private fun makeCompleteRun(): CrawlRun = makeRun(CrawlRunStatus.COMPLETE)

    private fun addExtractedUrl(run: CrawlRun) = urlRepo.save(
        CrawlUrl(
            run = run,
            originalUrl = "https://shop.test.com/product",
            normalizedUrl = "https://shop.test.com/product",
            host = "shop.test.com",
            status = CrawlUrlStatus.EXTRACTED,
            pageType = CrawlPageType.PRODUCT,
            depth = 1,
            priority = 50,
        )
    )

    private fun addPendingUrl(run: CrawlRun) = urlRepo.save(
        CrawlUrl(
            run = run,
            originalUrl = "https://shop.test.com/page",
            normalizedUrl = "https://shop.test.com/page",
            host = "shop.test.com",
            status = CrawlUrlStatus.PENDING,
            pageType = CrawlPageType.LISTING,
            depth = 1,
            priority = 50,
        )
    )

    /** Compute identity key using the 4-arg core overload (supplierId is not part of the key). */
    private fun key(mpn: String?, name: String, sourceUrl: String = "https://shop.test.com/product") =
        identityService.identity(mpn = mpn, productName = name, productClassName = null, sourceUrl = sourceUrl)

    private fun makeObservation(
        run: CrawlRun,
        mpn: String?,
        productName: String = "Test Product",
        price: BigDecimal? = null,
        extractionMethod: String = "JSON_LD",
        confidence: Int = 90,
        sourceUrl: String = "https://shop.test.com/product",
    ): CrawlProductObservation {
        val identityKey = key(mpn, productName, sourceUrl)
        return observationRepo.save(
            CrawlProductObservation(
                run = run,
                supplier = supplier,
                identityKey = identityKey,
                productName = productName,
                mpn = mpn,
                sourceUrl = sourceUrl,
                extractionMethod = extractionMethod,
                confidence = confidence,
                price = price,
                currency = if (price != null) "JOD" else null,
            )
        )
    }

    private fun makeProduct(
        identityKey: String,
        missCount: Int = 0,
        crawlerStale: Boolean = false,
        mpn: String? = null,
    ): Product = productRepo.save(
        Product(
            supplier = supplier,
            name = "Existing Product",
            mpn = mpn,
            source = "scrape",
            crawlMissCount = missCount,
            crawlerStale = crawlerStale,
            identityKey = identityKey,
        )
    )

    private fun flush() { em.flush(); em.clear() }

    private fun reloadProduct(id: Long): Product = productRepo.findById(id).get()

    // -------------------------------------------------------------------------
    // Completeness guard — partial/failed/cancelled runs must not change products
    // -------------------------------------------------------------------------

    @Test
    fun `partial run cannot increment product miss count`() {
        val product = makeProduct(key("DMM-100", "DMM"), missCount = 1)
        val run = makeRun(CrawlRunStatus.PARTIAL)
        flush()

        val result = reconciler.reconcile(run.id)

        flush()
        assertThat(result.skippedReason).isNotNull()
        assertThat(reloadProduct(product.id).crawlMissCount).isEqualTo(1)
        assertThat(reloadProduct(product.id).crawlerStale).isFalse()
    }

    @Test
    fun `failed run cannot increment product miss count`() {
        val product = makeProduct(key("DMM-100", "DMM"), missCount = 0)
        val run = makeRun(CrawlRunStatus.FAILED)
        flush()

        reconciler.reconcile(run.id)

        flush()
        assertThat(reloadProduct(product.id).crawlMissCount).isEqualTo(0)
    }

    @Test
    fun `cancelled run cannot increment product miss count`() {
        val product = makeProduct(key("DMM-100", "DMM"), missCount = 0)
        val run = makeRun(CrawlRunStatus.CANCELLED)
        flush()

        reconciler.reconcile(run.id)

        flush()
        assertThat(reloadProduct(product.id).crawlMissCount).isEqualTo(0)
    }

    @Test
    fun `complete run with pending URL cannot reconcile`() {
        val product = makeProduct(key("DMM-100", "DMM"), missCount = 1)
        val run = makeCompleteRun()
        addPendingUrl(run)
        flush()

        val result = reconciler.reconcile(run.id)

        flush()
        assertThat(result.skippedReason).isNotNull()
        assertThat(reloadProduct(product.id).crawlMissCount).isEqualTo(1)
    }

    // -------------------------------------------------------------------------
    // Two-snapshot miss / stale logic
    // -------------------------------------------------------------------------

    @Test
    fun `first consecutive complete miss increments miss count but does not mark stale`() {
        val product = makeProduct(key("DMM-100", "DMM"), missCount = 0)
        val run = makeCompleteRun()
        // No observation for this product
        flush()

        reconciler.reconcile(run.id)

        flush()
        assertThat(reloadProduct(product.id).crawlMissCount).isEqualTo(1)
        assertThat(reloadProduct(product.id).crawlerStale).isFalse()
    }

    @Test
    fun `second consecutive complete miss marks product stale`() {
        val product = makeProduct(key("DMM-100", "DMM"), missCount = 1)  // already missed once
        val run = makeCompleteRun()
        // No observation again
        flush()

        val result = reconciler.reconcile(run.id)

        flush()
        assertThat(reloadProduct(product.id).crawlMissCount).isEqualTo(2)
        assertThat(reloadProduct(product.id).crawlerStale).isTrue()
        assertThat(result.staleMarkedCount).isEqualTo(1)
    }

    @Test
    fun `reappearance clears miss count and crawlerStale`() {
        val productKey = key("DMM-100", "DMM", "https://shop.test.com/product")
        val product = makeProduct(productKey, missCount = 2, crawlerStale = true)
        val run = makeCompleteRun()
        makeObservation(run, mpn = "DMM-100", productName = "DMM", sourceUrl = "https://shop.test.com/product")
        flush()

        reconciler.reconcile(run.id)

        flush()
        assertThat(reloadProduct(product.id).crawlMissCount).isEqualTo(0)
        assertThat(reloadProduct(product.id).crawlerStale).isFalse()
    }

    @Test
    fun `crawlerStale does not affect isStale (manual admin staleness preserved)`() {
        val productKey = key("DMM-100", "Admin Stale")
        val product = productRepo.save(
            Product(
                supplier = supplier, name = "Admin Stale", source = "scrape",
                identityKey = productKey, isStale = true, crawlMissCount = 1,
            )
        )
        val run = makeCompleteRun()
        // No observation — product still missing
        flush()

        reconciler.reconcile(run.id)

        flush()
        val reloaded = reloadProduct(product.id)
        assertThat(reloaded.isStale).isTrue()      // admin staleness NOT cleared
        assertThat(reloaded.crawlMissCount).isEqualTo(2)
        assertThat(reloaded.crawlerStale).isTrue()
    }

    // -------------------------------------------------------------------------
    // Product upsert
    // -------------------------------------------------------------------------

    @Test
    fun `new product is inserted on first complete run`() {
        val run = makeCompleteRun()
        makeObservation(run, mpn = "XYZ-500", productName = "Oscilloscope")
        flush()

        val result = reconciler.reconcile(run.id)

        flush()
        assertThat(result.insertedCount).isEqualTo(1)
        assertThat(result.updatedCount).isEqualTo(0)
        val productKey = key("XYZ-500", "Oscilloscope")
        val saved = productRepo.findBySupplierIdAndIdentityKey(supplier.id, productKey)
        assertThat(saved).isNotNull()
        assertThat(saved!!.mpn).isEqualTo("XYZ-500")
        assertThat(saved.crawlMissCount).isEqualTo(0)
        assertThat(saved.crawlerStale).isFalse()
    }

    @Test
    fun `existing product is updated on second complete run`() {
        val productKey = key("XYZ-500", "Oscilloscope")
        makeProduct(productKey, missCount = 0)
        val run = makeCompleteRun()
        makeObservation(run, mpn = "XYZ-500", productName = "Oscilloscope Pro")
        flush()

        val result = reconciler.reconcile(run.id)

        flush()
        assertThat(result.updatedCount).isEqualTo(1)
        assertThat(result.insertedCount).isEqualTo(0)
        // Note: the observation's normalized key is for "Oscilloscope Pro" with same MPN
        val updatedKey = key("XYZ-500", "Oscilloscope Pro")
        val updated = productRepo.findBySupplierIdAndIdentityKey(supplier.id, updatedKey)
            ?: productRepo.findBySupplierIdAndIdentityKey(supplier.id, productKey)
        assertThat(updated).isNotNull()
        assertThat(updated!!.name).isEqualTo("Oscilloscope Pro")
    }

    @Test
    fun `back-fill existing product found by MPN when identityKey is null`() {
        val mpn = "XYZ-500"
        val oldProduct = productRepo.save(
            Product(supplier = supplier, name = "Old Name", mpn = mpn, source = "scrape", identityKey = null)
        )
        val run = makeCompleteRun()
        makeObservation(run, mpn = mpn, productName = "New Name")
        flush()

        val result = reconciler.reconcile(run.id)

        flush()
        assertThat(result.updatedCount).isEqualTo(1)
        assertThat(result.insertedCount).isEqualTo(0)
        val updated = reloadProduct(oldProduct.id)
        assertThat(updated.name).isEqualTo("New Name")
        assertThat(updated.identityKey).isNotNull()
    }

    // -------------------------------------------------------------------------
    // Price upsert
    // -------------------------------------------------------------------------

    @Test
    fun `price is upserted on observation with price`() {
        val run = makeCompleteRun()
        makeObservation(run, mpn = "METER-1", productName = "Meter", price = BigDecimal("250.000"))
        flush()

        reconciler.reconcile(run.id)

        flush()
        val productKey = key("METER-1", "Meter")
        val product = productRepo.findBySupplierIdAndIdentityKey(supplier.id, productKey)!!
        val price = priceRepo.findById(product.id).orElse(null)
        assertThat(price).isNotNull()
        assertThat(price!!.price).isEqualByComparingTo(BigDecimal("250.000"))
    }

    @Test
    fun `price change writes history entry`() {
        val productKey = key("METER-1", "Meter")
        val product = makeProduct(productKey)
        priceRepo.save(
            com.rfp.domain.ProductPrice(productId = product.id, price = BigDecimal("200.000"), currency = "JOD")
        )
        val run = makeCompleteRun()
        makeObservation(run, mpn = "METER-1", productName = "Meter", price = BigDecimal("300.000"))
        flush()

        reconciler.reconcile(run.id)

        flush()
        val history = priceHistoryRepo.findAll().filter { it.product.id == product.id }
        assertThat(history).hasSize(1)
        assertThat(history[0].price).isEqualByComparingTo(BigDecimal("200.000"))
    }

    @Test
    fun `unchanged price does not write history entry`() {
        val productKey = key("METER-1", "Meter")
        val product = makeProduct(productKey)
        priceRepo.save(
            com.rfp.domain.ProductPrice(productId = product.id, price = BigDecimal("200.000"), currency = "JOD")
        )
        val run = makeCompleteRun()
        makeObservation(run, mpn = "METER-1", productName = "Meter", price = BigDecimal("200.000"))
        flush()

        reconciler.reconcile(run.id)

        flush()
        val history = priceHistoryRepo.findAll().filter { it.product.id == product.id }
        assertThat(history).isEmpty()
    }

    // -------------------------------------------------------------------------
    // Merge (method rank)
    // -------------------------------------------------------------------------

    @Test
    fun `product-page observation beats listing observation when both present`() {
        val run = makeCompleteRun()
        val sourceUrl = "https://shop.test.com/product"
        val identityKey = key("DMM-555", "Multimeter", sourceUrl)
        observationRepo.save(
            CrawlProductObservation(
                run = run, supplier = supplier,
                identityKey = identityKey,
                productName = "Multimeter from Listing",
                mpn = "DMM-555",
                sourceUrl = sourceUrl,
                extractionMethod = "LISTING_PAGE",
                confidence = 95,
            )
        )
        observationRepo.save(
            CrawlProductObservation(
                run = run, supplier = supplier,
                identityKey = identityKey,
                productName = "Multimeter from Product Page",
                mpn = "DMM-555",
                sourceUrl = sourceUrl,
                extractionMethod = "PRODUCT_PAGE",
                confidence = 80,
            )
        )
        flush()

        reconciler.reconcile(run.id)

        flush()
        val saved = productRepo.findBySupplierIdAndIdentityKey(supplier.id, identityKey)!!
        assertThat(saved.name).isEqualTo("Multimeter from Product Page")
    }

    @Test
    fun `JSON_LD beats product page in method rank`() {
        val run = makeCompleteRun()
        val sourceUrl = "https://shop.test.com/product"
        val identityKey = key("DMM-666", "Multimeter", sourceUrl)
        observationRepo.save(
            CrawlProductObservation(
                run = run, supplier = supplier,
                identityKey = identityKey,
                productName = "From JSON-LD",
                mpn = "DMM-666",
                sourceUrl = sourceUrl,
                extractionMethod = "JSON_LD",
                confidence = 70,
            )
        )
        observationRepo.save(
            CrawlProductObservation(
                run = run, supplier = supplier,
                identityKey = identityKey,
                productName = "From Product Page",
                mpn = "DMM-666",
                sourceUrl = sourceUrl,
                extractionMethod = "PRODUCT_PAGE",
                confidence = 99,
            )
        )
        flush()

        reconciler.reconcile(run.id)

        flush()
        val saved = productRepo.findBySupplierIdAndIdentityKey(supplier.id, identityKey)!!
        assertThat(saved.name).isEqualTo("From JSON-LD")
    }

    @Test
    fun `on method-rank tie higher confidence wins`() {
        val run = makeCompleteRun()
        val sourceUrl = "https://shop.test.com/product"
        val identityKey = key("DMM-777", "Multimeter", sourceUrl)
        observationRepo.save(
            CrawlProductObservation(
                run = run, supplier = supplier,
                identityKey = identityKey,
                productName = "Low Confidence",
                mpn = "DMM-777",
                sourceUrl = sourceUrl,
                extractionMethod = "JSON_LD",
                confidence = 60,
            )
        )
        observationRepo.save(
            CrawlProductObservation(
                run = run, supplier = supplier,
                identityKey = identityKey,
                productName = "High Confidence",
                mpn = "DMM-777",
                sourceUrl = sourceUrl,
                extractionMethod = "JSON_LD",
                confidence = 95,
            )
        )
        flush()

        reconciler.reconcile(run.id)

        flush()
        val saved = productRepo.findBySupplierIdAndIdentityKey(supplier.id, identityKey)!!
        assertThat(saved.name).isEqualTo("High Confidence")
    }

    @Test
    fun `newer low-confidence observation does not overwrite older high-confidence one`() {
        val run = makeCompleteRun()
        val sourceUrl = "https://shop.test.com/product"
        val identityKey = key("DMM-888", "Multimeter", sourceUrl)
        val now = Instant.now()
        observationRepo.save(
            CrawlProductObservation(
                run = run, supplier = supplier,
                identityKey = identityKey,
                productName = "Old High Confidence",
                mpn = "DMM-888",
                sourceUrl = sourceUrl,
                extractionMethod = "JSON_LD",
                confidence = 90,
                observedAt = now.minusSeconds(60),
            )
        )
        observationRepo.save(
            CrawlProductObservation(
                run = run, supplier = supplier,
                identityKey = identityKey,
                productName = "New Low Confidence",
                mpn = "DMM-888",
                sourceUrl = sourceUrl,
                extractionMethod = "JSON_LD",
                confidence = 40,
                observedAt = now,
            )
        )
        flush()

        reconciler.reconcile(run.id)

        flush()
        val saved = productRepo.findBySupplierIdAndIdentityKey(supplier.id, identityKey)!!
        assertThat(saved.name).isEqualTo("Old High Confidence")
    }

    // -------------------------------------------------------------------------
    // Idempotency
    // -------------------------------------------------------------------------

    @Test
    fun `reconciliation is idempotent — running twice yields the same product state`() {
        val productKey = key("IDEM-1", "Idempotent Product")
        val product = makeProduct(productKey, missCount = 1)
        val run = makeCompleteRun()
        // No observation → product misses again (miss count: 1 → 2, crawlerStale = true)
        flush()

        reconciler.reconcile(run.id)
        flush()
        val afterFirst = reloadProduct(product.id)

        // Second call must be a no-op due to reconciledAt guard
        reconciler.reconcile(run.id)
        flush()
        val afterSecond = reloadProduct(product.id)

        assertThat(afterSecond.crawlMissCount).isEqualTo(afterFirst.crawlMissCount)
        assertThat(afterSecond.crawlerStale).isEqualTo(afterFirst.crawlerStale)
    }

    // -------------------------------------------------------------------------
    // Result metadata
    // -------------------------------------------------------------------------

    @Test
    fun `reconcile returns skippedReason when run is not complete`() {
        val run = makeRun(CrawlRunStatus.CRAWLING)
        val result = reconciler.reconcile(run.id)
        assertThat(result.skippedReason).isNotNull()
        assertThat(result.insertedCount).isEqualTo(0)
        assertThat(result.updatedCount).isEqualTo(0)
        assertThat(result.staleMarkedCount).isEqualTo(0)
    }

    @Test
    fun `reconcile returns null skippedReason on success`() {
        val run = makeCompleteRun()
        flush()

        val result = reconciler.reconcile(run.id)

        assertThat(result.skippedReason).isNull()
    }
}
