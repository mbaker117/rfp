package com.rfp.service.crawl

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Micrometer counters for the adaptive crawl system.
 *
 * Tag values MUST NOT contain supplier names or URLs (cardinality explosion risk).
 * All tag values are drawn from fixed enumerations defined here.
 */
@Component
class CrawlMetrics(registry: MeterRegistry) {

    // crawl.fetch.outcome — outcome of a single URL fetch attempt
    // tags: status=success|error|timeout|rejected, renderer=http|playwright
    private val fetchOutcomeCounters: Map<Pair<String, String>, Counter> = buildMap {
        for (status in listOf("success", "error", "timeout", "rejected")) {
            for (renderer in listOf("http", "playwright")) {
                put(
                    status to renderer,
                    Counter.builder("crawl.fetch.outcome")
                        .tag("status", status)
                        .tag("renderer", renderer)
                        .description("Count of URL fetch attempts by outcome and renderer")
                        .register(registry)
                )
            }
        }
    }

    // crawl.fetch.retries — total retry attempts
    private val fetchRetries: Counter = Counter.builder("crawl.fetch.retries")
        .description("Count of URL fetch retries")
        .register(registry)

    // crawl.extraction.yield — products extracted per method
    // tags: method=json_ld|api|product_page_llm|listing_llm|manual
    private val extractionYieldCounters: Map<String, Counter> = buildMap {
        for (method in listOf("json_ld", "api", "product_page_llm", "listing_llm", "manual")) {
            put(
                method,
                Counter.builder("crawl.extraction.yield")
                    .tag("method", method)
                    .description("Count of products extracted by method")
                    .register(registry)
            )
        }
    }

    // crawl.observation.duplicate — same identity key seen twice in a run
    private val observationDuplicate: Counter = Counter.builder("crawl.observation.duplicate")
        .description("Count of duplicate product observations within a single run")
        .register(registry)

    // crawl.run.completed — completed run outcomes
    // tags: outcome=complete|partial|failed|cancelled
    private val runCompletedCounters: Map<String, Counter> = buildMap {
        for (outcome in listOf("complete", "partial", "failed", "cancelled")) {
            put(
                outcome,
                Counter.builder("crawl.run.completed")
                    .tag("outcome", outcome)
                    .description("Count of completed crawl runs by final outcome")
                    .register(registry)
            )
        }
    }

    // -------------------------------------------------------------------------
    // Public increment API
    // -------------------------------------------------------------------------

    fun incrementFetchOutcome(status: String, renderer: String) {
        fetchOutcomeCounters[status to renderer]?.increment()
    }

    fun incrementFetchRetry() = fetchRetries.increment()

    fun incrementExtractionYield(method: String) {
        extractionYieldCounters[method.lowercase()]?.increment()
    }

    fun incrementObservationDuplicate() = observationDuplicate.increment()

    fun incrementRunCompleted(outcome: String) {
        runCompletedCounters[outcome.lowercase()]?.increment()
    }
}
