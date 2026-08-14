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

    // Product-related path keywords to probe when homepage yields little content
    private val productPaths = listOf(
        "/products", "/product", "/catalog", "/catalogue",
        "/shop", "/store", "/items", "/instruments",
        "/equipment", "/hardware", "/solutions"
    )

    fun crawlWebsite(baseUrl: String): String {
        val homepageHtml = try {
            crawlWithPlaywright(baseUrl)
        } catch (e: Exception) {
            crawlWithHttp(baseUrl)
        }

        // Try to extract product sub-pages from the homepage HTML
        val additionalPages = extractProductLinks(baseUrl, homepageHtml)
            .take(5)  // cap at 5 additional pages to avoid runaway
            .mapNotNull { link ->
                try {
                    Thread.sleep(throttleMs)
                    crawlWithHttp(link)
                } catch (_: Exception) { null }
            }

        // Also probe common product paths if few links were found
        val probedPages = if (additionalPages.size < 2) {
            val origin = baseUrl.trimEnd('/')
            productPaths.mapNotNull { path ->
                try {
                    Thread.sleep(throttleMs)
                    val html = crawlWithHttp("$origin$path")
                    if (html.length > 500) html else null
                } catch (_: Exception) { null }
            }.take(3)
        } else emptyList()

        val allContent = (listOf(homepageHtml) + additionalPages + probedPages)
            .joinToString("\n\n---PAGE---\n\n")
        return allContent.take(24000)  // LLM will take first 12k; give more raw content
    }

    private fun extractProductLinks(baseUrl: String, html: String): List<String> {
        val origin = try {
            val u = java.net.URL(baseUrl)
            "${u.protocol}://${u.host}${if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else ""}"
        } catch (_: Exception) { return emptyList() }

        val linkRegex = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val keywords = setOf("product", "catalog", "catalogue", "shop", "item",
            "instrument", "equipment", "hardware", "solution")
        return linkRegex.findAll(html)
            .map { it.groupValues[1] }
            .filter { href ->
                val lower = href.lowercase()
                keywords.any { lower.contains(it) }
            }
            .map { href ->
                when {
                    href.startsWith("http") -> href
                    href.startsWith("//") -> "https:$href"
                    href.startsWith("/") -> "$origin$href"
                    else -> "$origin/$href"
                }
            }
            .filter { it.startsWith(origin) }  // stay on same domain
            .distinct()
            .toList()
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
