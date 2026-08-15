package com.rfp.service.crawl

import com.rfp.domain.Product
import com.rfp.domain.ProductPrice
import com.rfp.domain.ProductPriceHistory
import com.rfp.repository.CrawlProductObservationRepository
import com.rfp.repository.CrawlRunRepository
import com.rfp.repository.ProductPriceHistoryRepository
import com.rfp.repository.ProductPriceRepository
import com.rfp.repository.ProductRepository
import com.rfp.repository.SupplierRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Summary of a single [CrawlReconciler.reconcile] call.
 *
 * @param insertedCount    number of new [Product] rows created
 * @param updatedCount     number of existing [Product] rows updated
 * @param staleMarkedCount number of products newly marked as [Product.crawlerStale]
 * @param skippedReason    non-null when reconciliation was skipped; null on success
 */
data class ReconciliationResult(
    val insertedCount: Int,
    val updatedCount: Int,
    val staleMarkedCount: Int,
    val skippedReason: String?,
)

/**
 * Turns raw per-page [com.rfp.domain.CrawlProductObservation]s from a completed crawl run into
 * durable [Product] and [ProductPrice] records, and applies the two-snapshot miss/stale logic.
 *
 * **Critical invariants (enforced here, not overrideable by LLM):**
 * - Partial, failed, or cancelled runs NEVER touch [Product.crawlMissCount] or [Product.crawlerStale].
 * - Only COMPLETE runs drive miss counting and [Product.crawlerStale] marking.
 * - [Product.isStale] (manual admin flag) is never cleared by this reconciler.
 * - Reconciliation is idempotent — re-running on an already-reconciled run is a no-op.
 * - Identity key collision across different suppliers CANNOT merge products (scoping is per supplier).
 */
@Service
class CrawlReconciler(
    private val completenessService: CrawlCompletenessService,
    private val identityService: ProductIdentityService,
    private val merger: ObservationMerger,
    private val observationRepo: CrawlProductObservationRepository,
    private val productRepo: ProductRepository,
    private val priceRepo: ProductPriceRepository,
    private val priceHistoryRepo: ProductPriceHistoryRepository,
    private val runRepo: CrawlRunRepository,
    private val supplierRepo: SupplierRepository,
) {

    /**
     * Reconcile the observations from run [runId] into the product catalogue.
     *
     * @return [ReconciliationResult] — if [ReconciliationResult.skippedReason] is non-null
     *         the run was skipped and no product records were modified.
     */
    @Transactional
    fun reconcile(runId: Long): ReconciliationResult {
        val run = runRepo.findById(runId)
            .orElseThrow { IllegalArgumentException("Run $runId not found") }

        // ── Idempotency guard ────────────────────────────────────────────────
        if (run.reconciledAt != null) {
            log.info("Run $runId already reconciled at ${run.reconciledAt}; skipping")
            return ReconciliationResult(0, 0, 0, "already reconciled at ${run.reconciledAt}")
        }

        // ── Completeness check ───────────────────────────────────────────────
        val completeness = completenessService.evaluate(runId)
        if (!completeness.canReconcile) {
            log.info("Run $runId not reconcilable: ${completeness.reason}")
            return ReconciliationResult(0, 0, 0, completeness.reason)
        }

        val supplierId = run.supplier.id
        val supplier = supplierRepo.getReferenceById(supplierId)
        val now = Instant.now()

        // ── Collect and group observations ───────────────────────────────────
        val observations = observationRepo.findByRunId(runId)

        // Compute normalized identity keys and group observations by them.
        // The stored identityKey in CrawlProductObservation is the raw extractor hint;
        // ProductIdentityService normalizes it (e.g. "dmm-1000" → "mpn:DMM1000").
        val groups = observations.groupBy { obs ->
            identityService.identity(supplierId, obs)
        }

        val seenNormalizedKeys = mutableSetOf<String>()
        var insertedCount = 0
        var updatedCount = 0

        // ── Upsert each merged observation ───────────────────────────────────
        for ((normalizedKey, group) in groups) {
            seenNormalizedKeys.add(normalizedKey)
            val merged = merger.merge(group)

            val product = upsertProduct(normalizedKey, merged, supplierId, supplier, now)
                .let { (p, wasInserted) ->
                    if (wasInserted) insertedCount++ else updatedCount++
                    p
                }

            upsertPrice(product, merged)
        }

        // ── Two-snapshot miss / stale logic ──────────────────────────────────
        // Only COMPLETE runs participate; the completeness check above already guarantees this.
        var staleMarkedCount = 0
        val allSupplierProducts = productRepo.findBySupplierId(supplierId)

        for (product in allSupplierProducts) {
            val key = product.identityKey
            if (key == null || key in seenNormalizedKeys) continue  // seen this run → already reset

            val newMissCount = product.crawlMissCount + 1
            val becameStale = newMissCount >= STALE_MISS_THRESHOLD && !product.crawlerStale

            productRepo.save(
                product.copy(
                    crawlMissCount = newMissCount,
                    crawlerStale = newMissCount >= STALE_MISS_THRESHOLD,
                    updatedAt = now,
                )
            )

            if (becameStale) staleMarkedCount++
        }

        // ── Update run counters & mark reconciled ────────────────────────────
        run.insertedProductCount = insertedCount
        run.updatedProductCount = updatedCount
        run.staleProductCount = staleMarkedCount
        run.reconciledAt = now
        run.updatedAt = now
        runRepo.save(run)

        log.info(
            "Run $runId reconciled: inserted=$insertedCount updated=$updatedCount " +
                "staleMarked=$staleMarkedCount"
        )
        return ReconciliationResult(insertedCount, updatedCount, staleMarkedCount, null)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Find or create a [Product] for [normalizedKey] + [supplierId], then update it with
     * values from [merged].
     *
     * @return a pair of (saved Product, wasInserted).
     */
    private fun upsertProduct(
        normalizedKey: String,
        merged: MergedProduct,
        supplierId: Long,
        supplier: com.rfp.domain.Supplier,
        now: Instant,
    ): Pair<Product, Boolean> {
        // Primary lookup: by normalized identity key + supplierId
        var existing = productRepo.findBySupplierIdAndIdentityKey(supplierId, normalizedKey)

        // Back-fill for pre-crawler products that lack identityKey but have a matching MPN
        if (existing == null && normalizedKey.startsWith("mpn:")) {
            val mpn = merged.mpn
            if (mpn != null) {
                existing = productRepo.findBySupplierIdAndMpnIgnoreCase(supplierId, mpn)
            }
        }

        return if (existing != null) {
            val updated = productRepo.save(
                existing.copy(
                    name = merged.productName,
                    mpn = merged.mpn,
                    attributes = merged.attributesJson,
                    canonicalSourceUrl = merged.sourceUrl,
                    lastObservedAt = merged.observedAt,
                    crawlMissCount = 0,
                    crawlerStale = false,
                    identityKey = normalizedKey,   // back-fill if it was null
                    updatedAt = now,
                )
            )
            Pair(updated, false)
        } else {
            val created = productRepo.save(
                Product(
                    supplier = supplier,
                    name = merged.productName,
                    mpn = merged.mpn,
                    attributes = merged.attributesJson,
                    source = SOURCE_CRAWL,
                    canonicalSourceUrl = merged.sourceUrl,
                    lastObservedAt = merged.observedAt,
                    crawlMissCount = 0,
                    crawlerStale = false,
                    identityKey = normalizedKey,
                )
            )
            Pair(created, true)
        }
    }

    /**
     * Upsert [ProductPrice] for [product] using values from [merged].
     * Writes a [ProductPriceHistory] row if the price has changed.
     * Does nothing if [merged] has no price.
     */
    private fun upsertPrice(product: Product, merged: MergedProduct) {
        val newPrice = merged.price ?: return

        val existing = priceRepo.findById(product.id).orElse(null)

        if (existing != null) {
            // Write history if price changed
            if (existing.price != null && existing.price.compareTo(newPrice) != 0) {
                priceHistoryRepo.save(
                    ProductPriceHistory(
                        product = product,
                        price = existing.price,
                        currency = existing.currency,
                        sourceUrl = existing.sourceUrl,
                        extractionMethod = existing.extractionMethod,
                        observedAt = existing.observedAt,
                    )
                )
            }
            priceRepo.save(
                existing.copy(
                    price = newPrice,
                    currency = merged.currency ?: existing.currency,
                    sourceUrl = merged.sourceUrl,
                    extractionMethod = merged.extractionMethod,
                    observedAt = merged.observedAt,
                )
            )
        } else {
            priceRepo.save(
                ProductPrice(
                    productId = product.id,
                    price = newPrice,
                    currency = merged.currency ?: DEFAULT_CURRENCY,
                    sourceUrl = merged.sourceUrl,
                    extractionMethod = merged.extractionMethod,
                    observedAt = merged.observedAt,
                )
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CrawlReconciler::class.java)

        /** Products are marked [Product.crawlerStale] after this many consecutive complete-run misses. */
        private const val STALE_MISS_THRESHOLD = 2

        private const val SOURCE_CRAWL = "scrape"
        private const val DEFAULT_CURRENCY = "JOD"
    }
}
