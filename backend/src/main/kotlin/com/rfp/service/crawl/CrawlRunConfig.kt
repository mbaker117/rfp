package com.rfp.service.crawl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.rfp.domain.Supplier

/**
 * Effective configuration for a single crawl run, snapshotted into [com.rfp.domain.CrawlRun.configJson].
 *
 * Each constant named MAX_* is the absolute ceiling — supplier-level overrides may only be lower (or equal)
 * and per-invocation overrides (via [CrawlRunOverrides]) may further tighten but never loosen them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CrawlRunConfig(
    val batchPages: Int = MAX_BATCH_PAGES,
    val batchDocuments: Int = MAX_BATCH_DOCUMENTS,
    val maxConcurrency: Int = MAX_CONCURRENCY,
    val throttleMs: Long = MAX_THROTTLE_MS,
    val batchDurationMinutes: Int = MAX_BATCH_DURATION_MINUTES,
    val maxUrls: Int = MAX_URLS,
    val maxDurationMinutes: Int = MAX_DURATION_MINUTES,
    val maxAttempts: Int = MAX_ATTEMPTS,
    val maxDepth: Int = MAX_DEPTH,
    val robotsFailClosed: Boolean = true,
    val allowedHosts: List<String> = emptyList(),
) {
    companion object {
        /** Max pages to process in a single [com.rfp.service.crawl.CrawlCoordinator.processBatch] call. */
        const val MAX_BATCH_PAGES = 100
        /** Max documents to process in a single batch. */
        const val MAX_BATCH_DOCUMENTS = 20
        /** Max simultaneous in-flight requests per host. */
        const val MAX_CONCURRENCY = 2
        /** Minimum inter-request delay in ms (higher = safer). */
        const val MAX_THROTTLE_MS = 2_000L
        /** Wall-clock ceiling for one batch, in minutes. */
        const val MAX_BATCH_DURATION_MINUTES = 15
        /** Maximum URLs allowed in a single run. */
        const val MAX_URLS = 50_000
        /** Maximum total run duration, in minutes (24 h). */
        const val MAX_DURATION_MINUTES = 24 * 60
        /** Maximum fetch attempts per URL before marking FAILED. */
        const val MAX_ATTEMPTS = 3
        /** Maximum crawl depth from the seed URLs; links discovered beyond this depth are skipped. */
        const val MAX_DEPTH = 10

        /**
         * Build a [CrawlRunConfig] by applying supplier-level fields, then [overrides], always enforcing ceilings.
         * Supplier values may only lower (not raise) the defaults; overrides may only further tighten.
         */
        fun ceilingEnforced(supplier: Supplier, overrides: CrawlRunOverrides? = null): CrawlRunConfig {
            // Apply supplier settings — ceiling-capped
            val batchPages = minOfOrDefault(MAX_BATCH_PAGES, supplier.crawlBatchPages)
            val batchDocuments = minOfOrDefault(MAX_BATCH_DOCUMENTS, supplier.crawlBatchDocuments)
            val maxConcurrency = minOfOrDefault(MAX_CONCURRENCY, supplier.crawlMaxConcurrency)
            // Throttle: supplier may only RAISE it (more conservative), never lower
            val throttleMs = maxOfOrDefault(MAX_THROTTLE_MS, supplier.crawlThrottleMs)
            val maxUrls = minOfOrDefault(MAX_URLS, supplier.crawlMaxUrls)
            val maxDurationMinutes = minOfOrDefault(MAX_DURATION_MINUTES, supplier.crawlMaxDurationMinutes)
            val robotsFailClosed = supplier.crawlRobotsFailClosed ?: true
            val allowedHosts = supplier.crawlAllowedHosts.toList()

            // Apply per-invocation overrides — may only tighten further
            return CrawlRunConfig(
                batchPages = overrides?.batchPages?.let { minOf(batchPages, it) } ?: batchPages,
                batchDocuments = overrides?.batchDocuments?.let { minOf(batchDocuments, it) } ?: batchDocuments,
                maxConcurrency = overrides?.maxConcurrency?.let { minOf(maxConcurrency, it) } ?: maxConcurrency,
                throttleMs = overrides?.throttleMs?.let { maxOf(throttleMs, it) } ?: throttleMs,
                batchDurationMinutes = MAX_BATCH_DURATION_MINUTES,
                maxUrls = overrides?.maxUrls?.let { minOf(maxUrls, it) } ?: maxUrls,
                maxDurationMinutes = overrides?.maxDurationMinutes?.let { minOf(maxDurationMinutes, it) } ?: maxDurationMinutes,
                maxAttempts = overrides?.maxAttempts?.let { minOf(MAX_ATTEMPTS, it) } ?: MAX_ATTEMPTS,
                maxDepth = overrides?.maxDepth?.let { minOf(MAX_DEPTH, it) } ?: MAX_DEPTH,
                robotsFailClosed = overrides?.robotsFailClosed ?: robotsFailClosed,
                allowedHosts = overrides?.allowedHosts ?: allowedHosts,
            )
        }

        private fun minOfOrDefault(default: Int, value: Int?) = if (value != null) minOf(default, value) else default
        private fun maxOfOrDefault(default: Long, value: Long?) = if (value != null) maxOf(default, value) else default
    }
}

/**
 * Per-invocation overrides that may only tighten (not loosen) the configured defaults.
 * Each field is optional; null means "use the supplier/default value".
 */
data class CrawlRunOverrides(
    val batchPages: Int? = null,
    val batchDocuments: Int? = null,
    val maxConcurrency: Int? = null,
    val throttleMs: Long? = null,
    val maxUrls: Int? = null,
    val maxDurationMinutes: Int? = null,
    val maxAttempts: Int? = null,
    val maxDepth: Int? = null,
    val robotsFailClosed: Boolean? = null,
    val allowedHosts: List<String>? = null,
)
