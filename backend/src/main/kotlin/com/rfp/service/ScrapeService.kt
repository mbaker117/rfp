package com.rfp.service

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.rfp.domain.CatalogIngest
import com.rfp.repository.CatalogIngestRepository
import com.rfp.repository.SupplierRepository
import com.rfp.service.crawl.CrawlCoordinator
import okhttp3.OkHttpClient
import okhttp3.Request
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit

data class CrawlResult(val content: String, val stepLog: String)

// open so tests can subclass and override runScrapeJobAsync without Playwright
@Service
open class ScrapeService(
    private val llmService: LlmService,
    @Value("\${rfp.scraper.throttle-ms:1500}") val throttleMs: Long = 1500,
    @Value("\${rfp.scraper.adaptive-enabled:true}") val adaptiveEnabled: Boolean = true
) {
    @Autowired @Lazy lateinit var self: ScrapeService

    /**
     * Injected only when adaptive-enabled=true; lateinit so tests that override
     * runScrapeJobAsync can construct ScrapeService without a CrawlCoordinator bean.
     */
    @Autowired @Lazy lateinit var coordinator: CrawlCoordinator

    // Dependencies used only by the legacy scrape path (adaptive-enabled=false).
    // Injected via @Autowired(required=false) so unit tests that override
    // runScrapeJobAsync can construct ScrapeService without these beans.
    @set:Autowired(required = false)
    var legacySupplierRepo: SupplierRepository? = null

    @set:Autowired(required = false)
    var legacyIngestRepo: CatalogIngestRepository? = null

    @set:Autowired(required = false)
    @set:Lazy
    var legacyIngestService: CatalogIngestService? = null

    /**
     * Entry point called by [com.rfp.job.CatalogRefreshJob] and any controller that
     * triggers a supplier scrape.
     *
     * When [adaptiveEnabled] is **true** (default): delegates to [CrawlCoordinator.enqueue]
     * and returns immediately — the durable, resumable crawler takes over.
     *
     * When [adaptiveEnabled] is **false**: runs the legacy Playwright-based
     * pipeline inline via [legacyScrape].  The old Playwright code is preserved in
     * [crawlWebsite] and is intentionally NOT removed during the rollout period.
     */
    @Async("taskExecutor")
    open fun runScrapeJobAsync(supplierId: Long) {
        if (adaptiveEnabled) {
            coordinator.enqueue(supplierId, null)
        } else {
            legacyScrape(supplierId)
        }
    }

    /**
     * Legacy Playwright crawl + ingest pipeline.  Mirrors the logic previously in
     * [CatalogIngestService.ingestScrape], now consolidated here so the refresh job
     * has a single entry point regardless of the feature flag.
     *
     * Called only when [adaptiveEnabled] is false.
     */
    private fun legacyScrape(supplierId: Long) {
        val sr = legacySupplierRepo ?: error("SupplierRepository not wired (legacy scrape path)")
        val ir = legacyIngestRepo   ?: error("CatalogIngestRepository not wired (legacy scrape path)")
        val cs = legacyIngestService ?: error("CatalogIngestService not wired (legacy scrape path)")

        val supplier = sr.findById(supplierId).orElseThrow { NoSuchElementException("Supplier $supplierId not found") }
        val ingest = ir.save(
            CatalogIngest(supplier = supplier, kind = "scrape", status = "RUNNING", startedAt = Instant.now())
        )
        try {
            val crawl = crawlWebsite(
                supplier.officialWebsite
                    ?: throw IllegalStateException("No website for supplier ${supplier.name}")
            )
            ir.save(ingest.copy(stepLog = crawl.stepLog))
            cs.runIngest(ingest.copy(stepLog = crawl.stepLog), crawl.content, "scrape")
        } catch (e: Exception) {
            ir.save(ingest.copy(status = "FAILED", errorMsg = e.message, finishedAt = Instant.now()))
            sr.save(supplier.copy(scrapeStatus = "FAILED"))
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 3-stage pipeline:
     *   1. Fetch homepage (Playwright → HTTP fallback)
     *   2. LLM identifies product catalog URLs from extracted link list
     *   3. Fetch product pages (HTTP → Playwright fallback), strip HTML
     * Returns combined stripped text + step log.
     */
    fun crawlWebsite(baseUrl: String): CrawlResult {
        val log = StringBuilder()

        // Stage 1 — homepage
        val (homepageHtml, stage1Method) = try {
            Pair(crawlWithPlaywright(baseUrl), "playwright")
        } catch (_: Exception) {
            try {
                Pair(crawlWithHttp(baseUrl), "http")
            } catch (e: Exception) {
                Pair("", "failed: ${e.message?.take(100)}")
            }
        }
        val homepageStripped = stripHtml(homepageHtml)
        log.append("=== Stage 1: Homepage ===\n")
        log.append("URL: $baseUrl\n")
        log.append("Method: $stage1Method\n")
        log.append("Raw HTML: ${homepageHtml.length} chars → Stripped text: ${homepageStripped.length} chars\n\n")

        // Stage 2 — LLM discovers product page URLs from focused link list
        val navLinks = extractNavigationLinks(homepageHtml)
        log.append("=== Stage 2: LLM URL Discovery ===\n")
        log.append("Navigation links extracted: ${navLinks.lines().size} items\n")
        val discoveredUrls = llmService.identifyProductUrls(baseUrl, navLinks)
            .map { resolveUrl(baseUrl, it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(30)
        log.append("LLM discovered: ${discoveredUrls.size} product URL(s)\n")
        discoveredUrls.forEach { log.append("  - $it\n") }
        log.append("\n")

        // Stage 3 — fetch product pages (HTTP → Playwright fallback, failures skipped)
        log.append("=== Stage 3: Product Pages ===\n")
        val productTexts = discoveredUrls.mapNotNull { url ->
            try {
                Thread.sleep(throttleMs)
                val (text, method) = fetchPageStripped(url)
                log.append("  - $url: OK via $method (${text.length} chars stripped)\n")
                text
            } catch (e: Exception) {
                log.append("  - $url: FAILED (${e.message?.take(120)})\n")
                null
            }
        }
        log.append("\n")

        val allText = (listOf(homepageStripped) + productTexts)
            .joinToString("\n\n--- next page ---\n\n")
        val capped = allText.take(100_000)

        log.append("=== Extraction Input ===\n")
        log.append("Combined text: ${allText.length} chars → capped to ${capped.length} chars\n")
        log.append("Content preview (first 500 chars):\n${capped.take(500)}\n")

        return CrawlResult(content = capped, stepLog = log.toString())
    }

    /** Fetch a URL and return (stripped text, method used). Tries HTTP first, then Playwright. */
    private fun fetchPageStripped(url: String): Pair<String, String> {
        return try {
            val html = crawlWithHttp(url)
            val stripped = stripHtml(html)
            // Fall through to Playwright if HTTP returns sparse content (JS-rendered)
            if (stripped.length < 800) throw RuntimeException("Too short via HTTP (${stripped.length} chars) — likely JS-rendered")
            Pair(stripped, "http")
        } catch (_: Exception) {
            val html = crawlWithPlaywright(url)
            Pair(stripHtml(html), "playwright")
        }
    }

    /** Extract anchor tags as "label -> href" lines — much cleaner input for URL discovery than raw HTML. */
    private fun extractNavigationLinks(html: String): String {
        val linkRegex = Regex("""<a[^>]+href=["']([^"'#\s][^"']*)["'][^>]*>([^<]*)</a>""", RegexOption.IGNORE_CASE)
        return linkRegex.findAll(html)
            .take(300)
            .map { m ->
                val href = m.groupValues[1].trim()
                val label = m.groupValues[2].trim().replace(Regex("\\s+"), " ")
                if (label.isNotBlank()) "$label -> $href" else href
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(8000)
    }

    /** Remove script/style blocks and all HTML tags, collapse whitespace. */
    internal fun stripHtml(html: String): String {
        return html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)), " ")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&[a-zA-Z]+;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun resolveUrl(base: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        return try {
            val u = URL(base)
            val origin = "${u.protocol}://${u.host}${if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else ""}"
            if (href.startsWith("/")) "$origin$href" else "$origin/$href"
        } catch (_: Exception) { "" }
    }

    private fun crawlWithPlaywright(url: String): String {
        Playwright.create().use { pw ->
            val browser = pw.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
            val page = browser.newPage()
            page.navigate(url, Page.NavigateOptions().setTimeout(30000.0))
            // networkidle ensures JS-rendered content (dynamic product listings) is loaded
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
            val html = page.content()
            browser.close()
            return html
        }
    }

    private fun crawlWithHttp(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; RFP-Scraper/1.0)")
            .build()
        return httpClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code} for $url")
            r.body?.string() ?: throw RuntimeException("Empty response from $url")
        }
    }
}
