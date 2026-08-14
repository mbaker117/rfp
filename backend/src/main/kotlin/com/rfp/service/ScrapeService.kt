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
import java.util.concurrent.TimeUnit

// open so tests can subclass and override runScrapeJobAsync without Playwright
@Service
open class ScrapeService(
    private val llmService: LlmService,
    @Value("\${rfp.scraper.throttle-ms:2000}") val throttleMs: Long = 2000
) {
    // Self-inject via setter to get the Spring proxy (fixes @Async bypass)
    @Autowired
    @Lazy
    lateinit var self: ScrapeService

    @Async("taskExecutor")
    open fun runScrapeJobAsync(supplierId: Long) {
        // entry point kept for subclass override in tests
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun crawlWebsite(url: String): String {
        return try {
            crawlWithPlaywright(url)
        } catch (e: Exception) {
            // Playwright not installed or failed — fall back to plain HTTP fetch
            crawlWithHttp(url)
        }
    }

    private fun crawlWithPlaywright(url: String): String {
        Playwright.create().use { pw ->
            val browser = pw.chromium().launch(
                BrowserType.LaunchOptions().setHeadless(true)
            )
            val page = browser.newPage()
            page.navigate(url)
            page.waitForLoadState()
            val html = page.content()
            browser.close()
            return html
        }
    }

    private fun crawlWithHttp(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; RFP-Scraper/1.0)")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code} for $url")
            response.body?.string() ?: throw RuntimeException("Empty response from $url")
        }
    }
}
