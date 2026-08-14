package com.rfp.service.crawl

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.WaitUntilState
import jakarta.annotation.PreDestroy
import org.jsoup.Jsoup
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

class PlaywrightRenderer(
    private val crawlPolicy: CrawlPolicy,
    private val robotsPolicy: RobotsPolicyService,
    private val playwrightFactory: () -> Playwright = { Playwright.create() },
) {
    private val browserLock = Any()

    @Volatile
    private var playwright: Playwright? = null

    @Volatile
    private var browser: Browser? = null

    fun renderIfNeeded(
        fetch: FetchResult.Success,
        request: CrawlFetchRequest,
        parserRequiresJavaScript: Boolean,
        meaningfulContentThreshold: Int = 200,
        maximumWait: Duration = Duration.ofSeconds(10),
    ): FetchResult {
        require(meaningfulContentThreshold >= 0) { "meaningfulContentThreshold must not be negative" }
        require(!maximumWait.isNegative && !maximumWait.isZero) { "maximumWait must be positive" }
        if (fetch.contentType != "text/html" && fetch.contentType != "application/xhtml+xml") return fetch
        if (!parserRequiresJavaScript && meaningfulTextLength(fetch) >= meaningfulContentThreshold) return fetch
        if (crawlPolicy.validate(fetch.url, request.supplierRoot, request.explicitHosts) !is PolicyDecision.Allowed) {
            return FetchResult.Rejected(FetchError.POLICY_REJECTED)
        }
        robotsRejection(fetch.url, request)?.let { return FetchResult.Rejected(it) }

        return synchronized(browserLock) {
            render(fetch.url, request, maximumWait)
        }
    }

    private fun render(uri: URI, request: CrawlFetchRequest, maximumWait: Duration): FetchResult {
        val context = sharedBrowser().newContext(
            Browser.NewContextOptions().setUserAgent(request.userAgent),
        )
        context.use {
            val rejected = AtomicReference<FetchError?>()
            context.route("**/*") { route ->
                val routeUri = runCatching { URI(route.request().url()) }.getOrNull()
                val policyDecision = routeUri?.let {
                    crawlPolicy.validate(it, request.supplierRoot, request.explicitHosts)
                }
                val robotsError = if (policyDecision is PolicyDecision.Allowed && route.request().isNavigationRequest()) {
                    robotsRejection(routeUri, request)
                } else {
                    null
                }
                val error = when {
                    policyDecision !is PolicyDecision.Allowed -> FetchError.POLICY_REJECTED
                    robotsError != null -> robotsError
                    else -> null
                }
                if (error != null) {
                    rejected.compareAndSet(null, error)
                    route.abort()
                } else {
                    route.resume()
                }
            }

            val page = context.newPage()
            val maximumWaitMillis = maximumWait.toMillis().toDouble()
            val startedAt = System.nanoTime()
            val response = runCatching {
                page.navigate(
                    uri.toString(),
                    Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(maximumWaitMillis),
                )
            }.getOrElse {
                return FetchResult.Rejected(rejected.get() ?: FetchError.NETWORK_FAILURE)
            }
            rejected.get()?.let { return FetchResult.Rejected(it) }

            val finalUri = runCatching { URI(page.url()) }.getOrNull()
                ?: return FetchResult.Rejected(FetchError.POLICY_REJECTED)
            if (crawlPolicy.validate(finalUri, request.supplierRoot, request.explicitHosts) !is PolicyDecision.Allowed) {
                return FetchResult.Rejected(FetchError.POLICY_REJECTED)
            }
            robotsRejection(finalUri, request)?.let { return FetchResult.Rejected(it) }

            val html = waitForDomStability(page, startedAt, maximumWait)
            val bytes = html.toByteArray(Charsets.UTF_8)
            if (bytes.size.toLong() > request.maxResponseBytes) {
                return FetchResult.Rejected(FetchError.RESPONSE_TOO_LARGE)
            }
            return FetchResult.Success(
                url = finalUri,
                status = response?.status() ?: 200,
                contentType = "text/html",
                body = bytes,
                etag = null,
                lastModified = null,
                method = FetchMethod.PLAYWRIGHT,
                contentHash = sha256(bytes),
            )
        }
    }

    private fun robotsRejection(uri: URI, request: CrawlFetchRequest): FetchError? =
        when (robotsPolicy.canFetch(uri, request.userAgent)) {
            RobotsDecision.Allowed -> null
            RobotsDecision.Disallowed -> FetchError.ROBOTS_DISALLOWED
            RobotsDecision.Unavailable -> FetchError.ROBOTS_UNAVAILABLE
        }

    private fun waitForDomStability(page: Page, startedAt: Long, maximumWait: Duration): String {
        val maximumNanos = maximumWait.toNanos()
        var previous = page.content()
        var stableChecks = 0
        while (System.nanoTime() - startedAt < maximumNanos && stableChecks < 2) {
            val remainingMillis = (maximumNanos - (System.nanoTime() - startedAt)) / 1_000_000
            if (remainingMillis <= 0) break
            page.waitForTimeout(minOf(100L, remainingMillis).toDouble())
            val current = page.content()
            stableChecks = if (current == previous) stableChecks + 1 else 0
            previous = current
        }
        return previous
    }

    private fun meaningfulTextLength(fetch: FetchResult.Success): Int {
        if (fetch.contentType != "text/html" && fetch.contentType != "application/xhtml+xml") {
            return fetch.body.size
        }
        return Jsoup.parse(fetch.body.toString(Charsets.UTF_8)).text().length
    }

    private fun sharedBrowser(): Browser {
        browser?.let { return it }
        val newPlaywright = playwrightFactory()
        val newBrowser = newPlaywright.chromium().launch(
            BrowserType.LaunchOptions().setHeadless(true),
        )
        playwright = newPlaywright
        browser = newBrowser
        return newBrowser
    }

    @PreDestroy
    fun close() {
        synchronized(browserLock) {
            browser?.close()
            browser = null
            playwright?.close()
            playwright = null
        }
    }
}
