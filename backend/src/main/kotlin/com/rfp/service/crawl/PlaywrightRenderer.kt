package com.rfp.service.crawl

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.ServiceWorkerPolicy
import com.microsoft.playwright.options.WaitUntilState
import jakarta.annotation.PreDestroy
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class PlaywrightRenderer(
    client: OkHttpClient,
    destinationValidator: DestinationValidator,
    private val robotsPolicy: RobotsPolicyService,
    private val nanoTimeSource: NanoTimeSource = SystemNanoTimeSource,
    private val playwrightFactory: () -> Playwright = { Playwright.create() },
) {
    private val transport = ValidatedHttpTransport(client, destinationValidator)
    private val browserLock = ReentrantLock()

    @Volatile private var playwright: Playwright? = null
    @Volatile private var browser: Browser? = null

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
        val deadline = DeadlineBudget.start(maximumWait, nanoTimeSource)
        val acquired = try {
            browserLock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return FetchResult.Rejected(FetchError.TIMEOUT)
        return try {
            render(fetch.url, request, deadline)
        } finally {
            browserLock.unlock()
        }
    }

    private fun render(uri: URI, request: CrawlFetchRequest, deadline: DeadlineBudget): FetchResult {
        val context = sharedBrowser().newContext(
            Browser.NewContextOptions()
                .setUserAgent(request.userAgent)
                .setServiceWorkers(ServiceWorkerPolicy.BLOCK),
        )
        context.use {
            val rejected = AtomicReference<FetchError?>()
            val documentResponse = AtomicReference<TransportResult.Success?>()
            val requestCount = AtomicLong()
            val totalBytes = AtomicLong()
            val page = context.newPage()
            context.route("**/*") { route ->
                handleRoute(route, page, request, deadline, rejected, documentResponse, requestCount, totalBytes)
            }

            page.addInitScript(MUTATION_OBSERVER_SCRIPT)
            val navigationTimeout = deadline.remaining().toMillis()
            if (navigationTimeout <= 0) return FetchResult.Rejected(FetchError.TIMEOUT)
            runCatching {
                page.navigate(
                    uri.toString(),
                    Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(navigationTimeout.toDouble()),
                )
            }.getOrElse {
                val error = rejected.get() ?: if (deadline.isExpired()) FetchError.TIMEOUT else FetchError.NETWORK_FAILURE
                return FetchResult.Rejected(error)
            }
            if (deadline.isExpired()) return FetchResult.Rejected(FetchError.TIMEOUT)
            rejected.get()?.let { return FetchResult.Rejected(it) }
            val networkDocument = documentResponse.get()
                ?: return FetchResult.Rejected(FetchError.HTTP_FAILURE)
            if (networkDocument.status !in 200..299) return FetchResult.Rejected(FetchError.HTTP_FAILURE)
            val contentType = networkDocument.contentType
                ?: return FetchResult.Rejected(FetchError.UNSUPPORTED_CONTENT_TYPE)
            if (contentType != "text/html" && contentType != "application/xhtml+xml") {
                return FetchResult.Rejected(FetchError.UNSUPPORTED_CONTENT_TYPE)
            }

            waitForDomStability(page, deadline)
            if (deadline.isExpired()) return FetchResult.Rejected(FetchError.TIMEOUT)
            rejected.get()?.let { return FetchResult.Rejected(it) }
            val domBytes = (page.evaluate(DOM_BYTE_LENGTH_SCRIPT) as? Number)?.toLong()
                ?: return FetchResult.Rejected(FetchError.HTTP_FAILURE)
            if (domBytes > request.maxResponseBytes) {
                return FetchResult.Rejected(FetchError.RESPONSE_TOO_LARGE)
            }
            val html = page.content()
            val bytes = html.toByteArray(Charsets.UTF_8)
            if (bytes.size.toLong() > request.maxResponseBytes) {
                return FetchResult.Rejected(FetchError.RESPONSE_TOO_LARGE)
            }
            val finalUri = runCatching { URI(page.url()) }.getOrNull()
                ?: return FetchResult.Rejected(FetchError.POLICY_REJECTED)
            rejected.get()?.let { return FetchResult.Rejected(it) }
            if (deadline.isExpired()) return FetchResult.Rejected(FetchError.TIMEOUT)
            return FetchResult.Success(
                url = finalUri,
                status = networkDocument.status,
                contentType = contentType,
                body = bytes,
                etag = networkDocument.header("ETag"),
                lastModified = networkDocument.header("Last-Modified"),
                method = FetchMethod.PLAYWRIGHT,
                contentHash = sha256(bytes),
            )
        }
    }

    private fun handleRoute(
        route: Route,
        page: Page,
        request: CrawlFetchRequest,
        deadline: DeadlineBudget,
        rejected: AtomicReference<FetchError?>,
        documentResponse: AtomicReference<TransportResult.Success?>,
        requestCount: AtomicLong,
        totalBytes: AtomicLong,
    ) {
        if (deadline.isExpired()) {
            rejected.compareAndSet(null, FetchError.TIMEOUT)
            route.abort()
            return
        }
        val browserRequest = route.request()
        val uri = runCatching { URI(browserRequest.url()) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https")) {
            route.abort()
            return
        }
        val resourceType = browserRequest.resourceType()
        if (resourceType in OPTIONAL_RESOURCE_TYPES) {
            route.abort()
            return
        }
        if (resourceType !in ALLOWED_RESOURCE_TYPES || requestCount.incrementAndGet() > MAX_ROUTED_REQUESTS) {
            rejected.compareAndSet(null, FetchError.HTTP_FAILURE)
            route.abort()
            return
        }
        when (robotsPolicy.canFetch(uri, request.userAgent, request.scope, deadline)) {
            RobotsDecision.Allowed -> Unit
            RobotsDecision.Disallowed -> {
                rejected.compareAndSet(null, FetchError.ROBOTS_DISALLOWED)
                route.abort()
                return
            }
            is RobotsDecision.Unavailable -> {
                rejected.compareAndSet(null, FetchError.ROBOTS_UNAVAILABLE)
                route.abort()
                return
            }
        }
        if (deadline.isExpired()) {
            rejected.compareAndSet(null, FetchError.TIMEOUT)
            route.abort()
            return
        }
        val perResponseLimit = if (resourceType == "document") {
            request.maxResponseBytes
        } else {
            minOf(request.maxResponseBytes, MAX_SUBRESOURCE_BYTES)
        }
        when (val response = transport.execute(
            TransportRequest(uri, request.scope, request.userAgent, perResponseLimit, deadline = deadline),
        )) {
            is TransportResult.Failure -> {
                rejected.compareAndSet(null, response.error.toRendererError())
                route.abort()
            }
            is TransportResult.Success -> {
                if (deadline.isExpired()) {
                    rejected.compareAndSet(null, FetchError.TIMEOUT)
                    route.abort()
                    return
                }
                if (!isAllowedResourceMime(resourceType, response.contentType, response.status)) {
                    rejected.compareAndSet(null, FetchError.UNSUPPORTED_CONTENT_TYPE)
                    route.abort()
                    return
                }
                if (totalBytes.addAndGet(response.body.size.toLong()) > totalByteBudget(request.maxResponseBytes)) {
                    rejected.compareAndSet(null, FetchError.RESPONSE_TOO_LARGE)
                    route.abort()
                    return
                }
                if (
                    browserRequest.isNavigationRequest() &&
                    browserRequest.frame() == page.mainFrame() &&
                    response.status !in REDIRECT_STATUSES
                ) {
                    documentResponse.set(response)
                }
                route.fulfill(
                    Route.FulfillOptions()
                        .setStatus(response.status)
                        .setHeaders(safeBrowserHeaders(response.headers))
                        .setBodyBytes(response.body),
                )
            }
        }
    }

    private fun waitForDomStability(page: Page, deadline: DeadlineBudget) {
        if (deadline.isExpired()) return
        var previous = page.evaluate(DOM_FINGERPRINT_SCRIPT)?.toString()
        var stableChecks = 0
        while (!deadline.isExpired() && stableChecks < 2) {
            val remainingMillis = deadline.remaining().toMillis()
            if (remainingMillis <= 0) break
            page.waitForTimeout(minOf(100L, remainingMillis).toDouble())
            val current = page.evaluate(DOM_FINGERPRINT_SCRIPT)?.toString()
            stableChecks = if (current == previous) stableChecks + 1 else 0
            previous = current
        }
    }

    private fun meaningfulTextLength(fetch: FetchResult.Success): Int =
        Jsoup.parse(fetch.body.toString(Charsets.UTF_8)).text().length

    private fun sharedBrowser(): Browser {
        browser?.let { return it }
        val newPlaywright = playwrightFactory()
        return try {
            newPlaywright.chromium().launch(
                BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(listOf("--proxy-server=http://127.0.0.1:9", "--proxy-bypass-list=<-loopback>")),
            ).also {
                playwright = newPlaywright
                browser = it
            }
        } catch (failure: Throwable) {
            newPlaywright.close()
            throw failure
        }
    }

    @PreDestroy
    fun close() {
        browserLock.lock()
        try {
            browser?.close()
            browser = null
            playwright?.close()
            playwright = null
        } finally {
            browserLock.unlock()
        }
    }

    companion object {
        private const val MAX_ROUTED_REQUESTS = 64L
        private const val MAX_SUBRESOURCE_BYTES = 1024L * 1024L
        private val ALLOWED_RESOURCE_TYPES = setOf("document", "script", "stylesheet", "xhr", "fetch")
        private val OPTIONAL_RESOURCE_TYPES = setOf("image", "font", "media", "other")
        private val REDIRECT_STATUSES = setOf(300, 301, 302, 303, 307, 308)
        private const val MUTATION_OBSERVER_SCRIPT = """
            (() => {
              window.__rfpMutationCount = 0;
              new MutationObserver(() => window.__rfpMutationCount++)
                .observe(document, {subtree: true, childList: true, attributes: true, characterData: true});
            })();
        """
        private const val DOM_FINGERPRINT_SCRIPT =
            "() => `${'$'}{window.__rfpMutationCount || 0}:${'$'}{document.documentElement?.outerHTML.length || 0}`"
        private const val DOM_BYTE_LENGTH_SCRIPT =
            "() => new TextEncoder().encode(document.documentElement?.outerHTML || '').length"

        private fun totalByteBudget(maxResponseBytes: Long): Long =
            if (maxResponseBytes > Long.MAX_VALUE / 4) Long.MAX_VALUE else maxResponseBytes * 4

        private fun safeBrowserHeaders(headers: Map<String, String>): Map<String, String> = headers.filterKeys {
            !it.equals("Content-Length", true) &&
                !it.equals("Transfer-Encoding", true) &&
                !it.equals("Content-Encoding", true) &&
                !it.equals("Connection", true)
        }

        private fun isAllowedResourceMime(resourceType: String, contentType: String?, status: Int): Boolean {
            if (status in REDIRECT_STATUSES) return true
            val type = contentType ?: return false
            return when (resourceType) {
                "document" -> type == "text/html" || type == "application/xhtml+xml"
                "script" -> type in setOf(
                    "text/javascript", "application/javascript", "text/ecmascript", "application/ecmascript",
                )
                "stylesheet" -> type == "text/css"
                "xhr", "fetch" -> isAcceptedCrawlContentType(type)
                else -> false
            }
        }
    }
}

private fun TransportError.toRendererError(): FetchError = when (this) {
    TransportError.POLICY_REJECTED -> FetchError.POLICY_REJECTED
    TransportError.RESPONSE_TOO_LARGE -> FetchError.RESPONSE_TOO_LARGE
    TransportError.TIMEOUT -> FetchError.TIMEOUT
    TransportError.NETWORK_FAILURE -> FetchError.NETWORK_FAILURE
}
