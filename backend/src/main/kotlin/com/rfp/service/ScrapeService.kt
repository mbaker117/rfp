package com.rfp.service

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import okhttp3.OkHttpClient
import okhttp3.Request
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.net.URL
import java.util.concurrent.TimeUnit

// open so tests can subclass and override runScrapeJobAsync without Playwright
@Service
open class ScrapeService(
    private val llmService: LlmService,
    @Value("\${rfp.scraper.throttle-ms:1500}") val throttleMs: Long = 1500
) {
    @Autowired @Lazy lateinit var self: ScrapeService

    @Async("taskExecutor")
    open fun runScrapeJobAsync(supplierId: Long) {
        // entry point kept for subclass override in tests
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 3-stage pipeline:
     *   1. Fetch homepage (Playwright → HTTP fallback)
     *   2. LLM identifies which links lead to product catalog pages
     *   3. Fetch those pages, combine, return for LLM extraction
     */
    fun crawlWebsite(baseUrl: String): String {
        // Stage 1 — homepage
        val homepageHtml = try {
            crawlWithPlaywright(baseUrl)
        } catch (_: Exception) {
            crawlWithHttp(baseUrl)
        }

        // Stage 2 — LLM discovers product page URLs
        val discoveredUrls = llmService.identifyProductUrls(baseUrl, homepageHtml)
            .map { resolveUrl(baseUrl, it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)

        // Stage 3 — fetch product pages (throttled, failures silently skipped)
        val productPages = discoveredUrls.mapNotNull { url ->
            try {
                Thread.sleep(throttleMs)
                crawlWithHttp(url)
            } catch (_: Exception) { null }
        }

        val allContent = (listOf(homepageHtml) + productPages)
            .joinToString("\n\n---PAGE---\n\n")
        // LlmService takes first 12k — give generous raw content so product pages are included
        return allContent.take(30000)
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
            page.navigate(url)
            page.waitForLoadState()
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
