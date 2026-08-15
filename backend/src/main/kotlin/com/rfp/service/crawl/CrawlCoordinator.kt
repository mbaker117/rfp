package com.rfp.service.crawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.rfp.domain.CrawlPageType
import com.rfp.domain.CrawlProductObservation
import com.rfp.domain.CrawlRun
import com.rfp.domain.CrawlRunStatus
import com.rfp.domain.CrawlUrl
import com.rfp.domain.CrawlUrlStatus
import com.rfp.domain.Supplier
import com.rfp.dto.ClassSchema
import com.rfp.repository.CrawlProductObservationRepository
import com.rfp.repository.CrawlRunRepository
import com.rfp.repository.CrawlUrlRepository
import com.rfp.repository.SupplierRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Outcome of a single [CrawlCoordinator.processBatch] call.
 */
enum class BatchOutcome {
    /** Batch finished normally; more PENDING/RETRY URLs remain. */
    MORE_WORK,
    /** All URLs are done; run has been set to COMPLETE or PARTIAL. */
    COMPLETE,
    /** Cancellation was detected; run has been set to CANCELLED. */
    CANCELLED,
    /** An unrecoverable error occurred; run has been set to FAILED. */
    ERROR,
}

/**
 * Central orchestrator for a resumable adaptive crawl run.
 *
 * Responsibilities:
 * - Lifecycle: [enqueue], [resume], [cancel], [retryFailed]
 * - Batch execution: [processBatch] — atomically claims URLs, fetches, parses, classifies,
 *   enqueues discovered links, extracts observations, and checkpoints run stats.
 * - Fault recovery: [recoverAbandonedClaims] resets stale CLAIMED URLs to PENDING.
 *
 * Key safety invariants (code-enforced, not overrideable by LLM):
 * - Budget ceilings in [CrawlRunConfig] are always applied before supplier/caller overrides.
 * - Partial, cancelled, failed, or budget-exhausted runs NEVER mark products stale.
 * - Cancellation is checked before each [urlRepo] claim and once after each batch.
 * - Only HTTP and HTTPS URLs are enqueued.
 */
@Service
class CrawlCoordinator(
    private val runRepo: CrawlRunRepository,
    private val urlRepo: CrawlUrlRepository,
    private val observationRepo: CrawlProductObservationRepository,
    private val supplierRepo: SupplierRepository,
    private val fetcher: CrawlFetcher,
    private val pageParser: PageParser,
    private val classifier: CrawlClassifier,
    private val extractor: ProductPageExtractor,
    private val canonicalizer: UrlCanonicalizer,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        private val log = LoggerFactory.getLogger(CrawlCoordinator::class.java)
        private val CLAIM_TIMEOUT: Duration = Duration.ofMinutes(10)
        private const val BACKOFF_BASE_MS = 30_000L
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Create a new crawl run for [supplierId], seed the URL frontier with
     * `/robots.txt`, `/sitemap.xml`, and the supplier homepage, and return the run ID.
     *
     * The run starts in [CrawlRunStatus.QUEUED]; [processBatch] transitions it to CRAWLING.
     */
    fun enqueue(supplierId: Long, overrides: CrawlRunOverrides? = null): Long {
        val supplier = supplierRepo.findById(supplierId)
            .orElseThrow { IllegalArgumentException("Supplier $supplierId not found") }
        val website = supplier.officialWebsite
            ?: throw IllegalStateException("Supplier $supplierId has no officialWebsite")

        val config = CrawlRunConfig.ceilingEnforced(supplier, overrides)
        val run = runRepo.save(
            CrawlRun(
                supplier = supplier,
                status = CrawlRunStatus.QUEUED,
                configJson = objectMapper.writeValueAsString(config),
            )
        )

        val rootUri = canonicalizer.resolveAndNormalize(URI(website), website)
            ?: throw IllegalStateException("Cannot normalize supplier website: $website")
        val schemeHost = "${rootUri.scheme}://${rootUri.host}"

        seedUrl(run, rootUri, CrawlPageType.UNKNOWN, depth = 0, priority = 100)
        canonicalizer.resolveAndNormalize(rootUri, "$schemeHost/sitemap.xml")
            ?.let { seedUrl(run, it, CrawlPageType.SITEMAP, depth = 0, priority = 75) }
        canonicalizer.resolveAndNormalize(rootUri, "$schemeHost/robots.txt")
            ?.let { seedUrl(run, it, CrawlPageType.UNKNOWN, depth = 0, priority = 50) }

        return run.id
    }

    /**
     * Process one batch of PENDING URLs for the given run.
     *
     * State machine:
     * - QUEUED → CRAWLING on first call
     * - CRAWLING → COMPLETE when no work remains and no budget exceeded
     * - CRAWLING → PARTIAL when a run-level budget (total URLs / total duration) is exhausted
     * - CRAWLING → CANCELLED when [CrawlRun.cancellationRequested] is detected
     * - CRAWLING → FAILED on unrecoverable error
     *
     * Returns [BatchOutcome.MORE_WORK] while pending URLs remain (caller should invoke again).
     */
    fun processBatch(runId: Long): BatchOutcome {
        val run = runRepo.findById(runId)
            .orElseThrow { IllegalArgumentException("Run $runId not found") }

        // Pre-claim cancellation check (mandatory per spec)
        if (run.cancellationRequested) {
            return finishRun(run, CrawlRunStatus.CANCELLED, BatchOutcome.CANCELLED)
        }

        val now = clock.instant()
        val config: CrawlRunConfig = try {
            objectMapper.readValue(run.configJson, CrawlRunConfig::class.java)
        } catch (e: Exception) {
            log.error("Failed to deserialise config for run $runId", e)
            return finishRun(run, CrawlRunStatus.FAILED, BatchOutcome.ERROR)
        }

        // Status transitions
        when (run.status) {
            CrawlRunStatus.QUEUED -> {
                run.status = CrawlRunStatus.CRAWLING
                run.startedAt = now
            }
            CrawlRunStatus.CRAWLING -> Unit
            else -> {
                log.warn("processBatch called on run $runId in unexpected status ${run.status}")
                return BatchOutcome.ERROR
            }
        }

        // Run-level duration budget
        val startedAt = run.startedAt ?: now
        if (Duration.between(startedAt, now).toMinutes() >= config.maxDurationMinutes) {
            return finishRun(run, CrawlRunStatus.PARTIAL, BatchOutcome.COMPLETE)
        }

        // Run-level URL budget
        if (run.discoveredUrlCount >= config.maxUrls) {
            return finishRun(run, CrawlRunStatus.PARTIAL, BatchOutcome.COMPLETE)
        }

        run.batchCount++
        run.heartbeatAt = now
        run.updatedAt = now
        runRepo.save(run)

        // Claim batch (may call entityManager.clear() internally)
        val batch = urlRepo.claimBatch(runId, config.batchPages, now)

        if (batch.isEmpty()) {
            // claimBatch returned early (no clear) — run is still managed
            return resolveCompletion(runId, run)
        }

        // Non-empty batch: entityManager was cleared — reload fresh managed entities
        val batchRun = runRepo.findById(runId)
            .orElseThrow { IllegalStateException("Run $runId disappeared after claim") }
        val supplier = supplierRepo.findById(batchRun.supplier.id)
            .orElseThrow { IllegalStateException("Supplier missing for run $runId") }
        val supplierRoot: URI? = supplier.officialWebsite
            ?.let { canonicalizer.resolveAndNormalize(URI(it), it) }

        val batchStart = clock.instant()
        val stats = BatchStats()

        for (crawlUrl in batch) {
            // Batch wall-clock ceiling check
            if (Duration.between(batchStart, clock.instant()).toMinutes() >= config.batchDurationMinutes) {
                log.debug("Run $runId: batch duration limit reached, checkpointing")
                break
            }
            processOneUrl(crawlUrl, batchRun, supplier, supplierRoot, config, stats)
        }

        // Post-batch cancellation check (between expensive stages)
        val postRun = runRepo.findById(runId).orElse(batchRun)
        if (postRun.cancellationRequested) {
            applyBatchStats(runId, stats)
            return finishRun(postRun, CrawlRunStatus.CANCELLED, BatchOutcome.CANCELLED)
        }

        applyBatchStats(runId, stats)

        // Determine next outcome based on remaining work
        return if (urlRepo.existsByRunIdAndStatusIn(runId, listOf(CrawlUrlStatus.PENDING, CrawlUrlStatus.RETRY))) {
            BatchOutcome.MORE_WORK
        } else {
            val finalRun = runRepo.findById(runId).orElse(postRun)
            finishRun(finalRun, CrawlRunStatus.COMPLETE, BatchOutcome.COMPLETE)
        }
    }

    /**
     * Resume a previously interrupted run (sets status to QUEUED if it was paused;
     * [CrawlBatchJob] will pick it up on the next tick).
     */
    fun resume(runId: Long) {
        val run = runRepo.findById(runId).orElse(null) ?: return
        if (run.status == CrawlRunStatus.CRAWLING || run.status == CrawlRunStatus.QUEUED) return
        run.status = CrawlRunStatus.QUEUED
        run.cancellationRequested = false
        run.finishedAt = null
        run.updatedAt = clock.instant()
        runRepo.save(run)
    }

    /** Request graceful cancellation. The next [processBatch] call (or in-progress batch) will honour it. */
    fun cancel(runId: Long) {
        val run = runRepo.findById(runId)
            .orElseThrow { IllegalArgumentException("Run $runId not found") }
        run.cancellationRequested = true
        run.updatedAt = clock.instant()
        runRepo.save(run)
    }

    /** Reset all FAILED URLs for a run back to PENDING so they can be retried. */
    fun retryFailed(runId: Long) {
        val now = clock.instant()
        val failed = urlRepo.findByRunIdAndStatus(runId, CrawlUrlStatus.FAILED)
        for (url in failed) {
            url.status = CrawlUrlStatus.PENDING
            url.nextAttemptAt = null
            url.errorCategory = null
            url.errorDetails = null
            url.updatedAt = now
            urlRepo.save(url)
        }
        val run = runRepo.findById(runId).orElse(null) ?: return
        if (run.status == CrawlRunStatus.FAILED || run.status == CrawlRunStatus.PARTIAL) {
            run.status = CrawlRunStatus.QUEUED
            run.finishedAt = null
            run.updatedAt = now
            runRepo.save(run)
        }
    }

    /**
     * Return any CLAIMED URLs whose [CrawlUrl.claimedAt] is older than [CLAIM_TIMEOUT] before [now]
     * back to PENDING, so a restarted worker can reclaim them.
     */
    fun recoverAbandonedClaims(runId: Long, now: Instant) {
        val cutoff = now.minus(CLAIM_TIMEOUT)
        val abandoned = urlRepo.findByRunIdAndStatusAndClaimedAtBefore(runId, CrawlUrlStatus.CLAIMED, cutoff)
        for (url in abandoned) {
            url.status = CrawlUrlStatus.PENDING
            url.claimedAt = null
            url.updatedAt = now
            urlRepo.save(url)
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun seedUrl(run: CrawlRun, uri: URI, type: CrawlPageType, depth: Int, priority: Int) {
        val host = uri.host ?: return
        try {
            urlRepo.save(
                CrawlUrl(
                    run = run,
                    originalUrl = uri.toString(),
                    normalizedUrl = uri.toString(),
                    host = host,
                    status = CrawlUrlStatus.PENDING,
                    pageType = type,
                    depth = depth,
                    priority = priority,
                )
            )
        } catch (_: DataIntegrityViolationException) {
            // Idempotent — URL already seeded for this run
        }
    }

    private fun processOneUrl(
        crawlUrl: CrawlUrl,
        run: CrawlRun,
        supplier: Supplier,
        supplierRoot: URI?,
        config: CrawlRunConfig,
        stats: BatchStats,
    ) {
        val urlStr = crawlUrl.normalizedUrl
        val rootForFetch = supplierRoot ?: run.also {
            log.warn("No supplier root available for run ${run.id}; skipping $urlStr")
        }.let { return }

        val fetchRequest = CrawlFetchRequest(
            url = URI(urlStr),
            supplierRoot = rootForFetch,
            explicitHosts = config.allowedHosts.toSet(),
        )

        when (val result = fetcher.fetch(fetchRequest)) {
            is FetchResult.Success -> handleSuccess(result, crawlUrl, run, supplier, rootForFetch, config, stats)
            is FetchResult.Rejected -> handleRejection(result, crawlUrl, config, stats)
        }
    }

    private fun handleSuccess(
        result: FetchResult.Success,
        crawlUrl: CrawlUrl,
        run: CrawlRun,
        supplier: Supplier,
        supplierRoot: URI,
        config: CrawlRunConfig,
        stats: BatchStats,
    ) {
        crawlUrl.status = CrawlUrlStatus.FETCHED
        crawlUrl.fetchedAt = clock.instant()
        crawlUrl.httpStatus = result.status
        crawlUrl.contentType = result.contentType
        crawlUrl.contentLength = result.body.size.toLong()
        crawlUrl.etag = result.etag
        crawlUrl.lastModified = result.lastModified
        crawlUrl.contentHash = result.contentHash
        crawlUrl.canonicalUrl = result.url.toString()
        crawlUrl.updatedAt = clock.instant()
        urlRepo.save(crawlUrl)
        stats.fetched++

        val parsedPage: ParsedPage = try {
            pageParser.parse(result)
        } catch (e: Exception) {
            log.warn("Parse failed for ${crawlUrl.normalizedUrl}", e)
            return
        }

        val classification: PageClassification = try {
            classifier.classify(parsedPage)
        } catch (e: Exception) {
            log.warn("Classification failed for ${crawlUrl.normalizedUrl}", e)
            return
        }

        // Enqueue discovered links (idempotent via UNIQUE constraint catch)
        if (classification.shouldCrawl) {
            val links = parsedPage.links.map { it.uri } + parsedPage.pagination.map { it.uri }
            for (linkUri in links) {
                enqueueDiscovered(linkUri, run, crawlUrl, classification.priority, supplierRoot, config, stats)
            }
        }

        // Extract observations for product and listing pages
        if (classification.type == CrawlPageType.PRODUCT || classification.type == CrawlPageType.LISTING) {
            try {
                val observations = extractor.extract(parsedPage, emptyList<ClassSchema>())
                for (obs in observations) {
                    observationRepo.save(
                        CrawlProductObservation(
                            run = run,
                            supplier = supplier,
                            identityKey = obs.identityHint,
                            productName = obs.name,
                            mpn = obs.mpn,
                            productClassName = obs.className,
                            attributesJson = objectMapper.writeValueAsString(obs.attributes),
                            price = obs.price,
                            currency = obs.currency,
                            sourceUrl = obs.sourceUrl.toString(),
                            fieldProvenanceJson = objectMapper.writeValueAsString(obs.fieldSources),
                            extractionMethod = obs.method.name,
                            confidence = obs.confidence,
                            contentHash = crawlUrl.contentHash,
                            observedAt = obs.observedAt,
                        )
                    )
                    stats.observed++
                }
            } catch (e: Exception) {
                log.warn("Extraction failed for ${crawlUrl.normalizedUrl}", e)
            }
        }
    }

    private fun enqueueDiscovered(
        uri: URI,
        run: CrawlRun,
        parentUrl: CrawlUrl,
        priority: Int,
        supplierRoot: URI,
        config: CrawlRunConfig,
        stats: BatchStats,
    ) {
        if (!isAllowedScheme(uri)) return
        if (!isAllowedHost(uri, supplierRoot, config.allowedHosts)) return
        val normalized = canonicalizer.resolveAndNormalize(uri, uri.toString()) ?: return
        val host = normalized.host ?: return
        try {
            urlRepo.save(
                CrawlUrl(
                    run = run,
                    originalUrl = uri.toString(),
                    normalizedUrl = normalized.toString(),
                    host = host,
                    status = CrawlUrlStatus.PENDING,
                    pageType = CrawlPageType.UNKNOWN,
                    depth = parentUrl.depth + 1,
                    priority = priority,
                    parentUrl = parentUrl.normalizedUrl,
                )
            )
            stats.discovered++
        } catch (_: DataIntegrityViolationException) {
            // Already in frontier — idempotent
        }
    }

    private fun handleRejection(
        result: FetchResult.Rejected,
        crawlUrl: CrawlUrl,
        config: CrawlRunConfig,
        stats: BatchStats,
    ) {
        val attempts = crawlUrl.attemptCount + 1
        if (result.retryable && attempts < config.maxAttempts) {
            val backoffDuration = result.retryAfter ?: Duration.ofMillis(calculateBackoffMs(attempts))
            crawlUrl.status = CrawlUrlStatus.RETRY
            crawlUrl.attemptCount = attempts
            crawlUrl.nextAttemptAt = clock.instant().plus(backoffDuration)
            crawlUrl.errorCategory = result.error.name
            crawlUrl.updatedAt = clock.instant()
            urlRepo.save(crawlUrl)
            stats.retried++
        } else {
            crawlUrl.status = CrawlUrlStatus.FAILED
            crawlUrl.attemptCount = attempts
            crawlUrl.errorCategory = result.error.name
            crawlUrl.updatedAt = clock.instant()
            urlRepo.save(crawlUrl)
            stats.failed++
        }
    }

    private fun resolveCompletion(runId: Long, run: CrawlRun): BatchOutcome {
        // If RETRY URLs exist (with future nextAttemptAt), come back for them
        if (urlRepo.existsByRunIdAndStatusIn(runId, listOf(CrawlUrlStatus.RETRY))) {
            return BatchOutcome.MORE_WORK
        }
        return finishRun(run, CrawlRunStatus.COMPLETE, BatchOutcome.COMPLETE)
    }

    private fun applyBatchStats(runId: Long, stats: BatchStats) {
        if (stats.isZero()) return
        val run = runRepo.findById(runId).orElse(null) ?: return
        run.fetchedUrlCount += stats.fetched
        run.failedUrlCount += stats.failed
        run.retriedUrlCount += stats.retried
        run.discoveredUrlCount += stats.discovered
        run.observedProductCount += stats.observed
        run.heartbeatAt = clock.instant()
        run.updatedAt = clock.instant()
        runRepo.save(run)
    }

    private fun finishRun(run: CrawlRun, status: CrawlRunStatus, outcome: BatchOutcome): BatchOutcome {
        run.status = status
        run.finishedAt = clock.instant()
        run.updatedAt = clock.instant()
        runRepo.save(run)
        return outcome
    }

    private fun isAllowedScheme(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme == "http" || scheme == "https"
    }

    private fun isAllowedHost(uri: URI, supplierRoot: URI, allowedHosts: List<String>): Boolean {
        val host = uri.host?.lowercase() ?: return false
        val supplierHost = supplierRoot.host?.lowercase() ?: return false
        if (host == supplierHost || host.endsWith(".$supplierHost")) return true
        return allowedHosts.any { allowed ->
            val h = allowed.lowercase()
            host == h || host.endsWith(".$h")
        }
    }

    private fun calculateBackoffMs(attemptCount: Int): Long {
        val exponential = BACKOFF_BASE_MS * (1L shl minOf(attemptCount - 1, 10))
        val jitter = (exponential * 0.25 * (Random.nextDouble() * 2.0 - 1.0)).toLong()
        return (exponential + jitter).coerceAtLeast(BACKOFF_BASE_MS)
    }

    private data class BatchStats(
        var fetched: Int = 0,
        var failed: Int = 0,
        var retried: Int = 0,
        var discovered: Int = 0,
        var observed: Int = 0,
    ) {
        fun isZero() = fetched == 0 && failed == 0 && retried == 0 && discovered == 0 && observed == 0
    }
}
