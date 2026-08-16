package com.rfp.service.crawl

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Frame
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Response
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

class CrawlFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var policy: DestinationValidator

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        val serverAddress = InetAddress.getByName(server.hostName)
        policy = DestinationValidator { _, _, _ -> PolicyDecision.Allowed(listOf(serverAddress)) }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `does not fetch a robots-disallowed product page`() {
        server.dispatcher = pathDispatcher(
            mapOf("/robots.txt" to MockResponse().setBody("User-agent: *\nDisallow: /private/")),
        )

        val result = fetcher().fetch(request("/private/product-1"))

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.ROBOTS_DISALLOWED))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(server.takeRequest().path).isEqualTo("/robots.txt")
    }

    @Test
    fun `stops streaming when response exceeds configured byte ceiling`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/large" to MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setChunkedBody("123456789", 2),
            ),
        )

        val result = fetcher().fetch(request("/large", maxResponseBytes = 8))

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.RESPONSE_TOO_LARGE))
    }

    @Test
    fun `revalidates redirect destination before issuing redirected request`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/start" to MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "http://metadata.example.com/latest/meta-data"),
            ),
        )
        policy = DestinationValidator { uri, _, _ ->
            if (uri.host == "metadata.example.com") {
                PolicyDecision.Rejected(PolicyRejection.NON_PUBLIC_ADDRESS)
            } else {
                PolicyDecision.Allowed(listOf(InetAddress.getByName(server.hostName)))
            }
        }

        val result = fetcher().fetch(request("/start"))

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.POLICY_REJECTED))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `follows an allowed redirect manually after policy and robots checks`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/start" to MockResponse().setResponseCode(302).setHeader("Location", "/final"),
                "/final" to MockResponse().setHeader("Content-Type", "text/html").setBody("<h1>Product</h1>"),
            ),
        )

        val result = fetcher().fetch(request("/start"))

        assertThat(result).isInstanceOf(FetchResult.Success::class.java)
        result as FetchResult.Success
        assertThat(result.url).isEqualTo(server.url("/final").toUri())
        assertThat(result.body.toString(Charsets.UTF_8)).isEqualTo("<h1>Product</h1>")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `rejects redirect loop without issuing repeated loop requests`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/loop" to MockResponse().setResponseCode(302).setHeader("Location", "/loop"),
            ),
        )

        val result = fetcher().fetch(request("/loop", maxRedirects = 5))

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.TOO_MANY_REDIRECTS))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `reuses stored body and metadata on not-modified response`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/cached" to MockResponse().setResponseCode(304),
            ),
        )
        val previous = StoredFetchContent(
            effectiveUrl = server.url("/cached").toUri(),
            body = "cached product".toByteArray(),
            contentType = "text/html",
            etag = "\"v1\"",
            lastModified = "Wed, 13 Aug 2026 12:00:00 GMT",
            contentHash = "stored-hash",
        )

        val result = fetcher().fetch(request("/cached", previous = previous))

        assertThat(result).isEqualTo(
            FetchResult.Success(
                url = server.url("/cached").toUri(),
                status = 304,
                contentType = "text/html",
                body = previous.body,
                etag = "\"v1\"",
                lastModified = "Wed, 13 Aug 2026 12:00:00 GMT",
                method = FetchMethod.HTTP,
                contentHash = "stored-hash",
            ),
        )
        val requests = (1..2).map { server.takeRequest() }
        assertThat(requests.last().getHeader("If-None-Match")).isEqualTo("\"v1\"")
        assertThat(requests.last().getHeader("If-Modified-Since")).isEqualTo("Wed, 13 Aug 2026 12:00:00 GMT")
    }

    @Test
    fun `rejects unsupported response content type before reading body`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/binary" to MockResponse().setHeader("Content-Type", "application/zip").setBody("archive"),
            ),
        )

        val result = fetcher().fetch(request("/binary"))

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.UNSUPPORTED_CONTENT_TYPE))
    }

    @Test
    fun `accepts registered structured XML and JSON media suffixes locale independently`() {
        listOf("application/problem+json", "application/rss+xml").forEachIndexed { index, contentType ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                    "/structured-$index" to MockResponse().setHeader("Content-Type", contentType).setBody("{}"),
                ),
            )

            assertThat(fetcher().fetch(request("/structured-$index")))
                .isInstanceOf(FetchResult.Success::class.java)
        }
    }

    @Test
    fun `does not send or reuse validators across effective URL identity changes`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/start" to MockResponse().setResponseCode(302).setHeader("Location", "/other"),
                "/other" to MockResponse().setResponseCode(304),
            ),
        )
        val previous = StoredFetchContent(
            effectiveUrl = server.url("/start").toUri(),
            body = "old".toByteArray(),
            contentType = "text/html",
            etag = "\"old\"",
            lastModified = null,
            contentHash = "old-hash",
        )

        val result = fetcher().fetch(request("/start", previous = previous))

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.HTTP_FAILURE))
        val requests = (1..3).map { server.takeRequest() }
        assertThat(requests.last().getHeader("If-None-Match")).isNull()
    }

    @Test
    fun `rejects cached representation that exceeds current limit on 304`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/cached-large" to MockResponse().setResponseCode(304),
            ),
        )
        val previous = StoredFetchContent(
            effectiveUrl = server.url("/cached-large").toUri(),
            body = "123456789".toByteArray(),
            contentType = "text/html",
            etag = "\"v1\"",
            lastModified = null,
            contentHash = "hash",
        )

        assertThat(fetcher().fetch(request("/cached-large", maxResponseBytes = 8, previous = previous)))
            .isEqualTo(FetchResult.Rejected(FetchError.RESPONSE_TOO_LARGE))
    }

    @Test
    fun `does not fetch target when robots policy cannot be established`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))

        val result = fetcher().fetch(request("/product"))

        assertThat(result).isEqualTo(
            FetchResult.Rejected(FetchError.ROBOTS_UNAVAILABLE, retryable = true),
        )
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `preserves retryability and retry-after for throttled target`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/throttled" to MockResponse().setResponseCode(429).setHeader("Retry-After", "9"),
            ),
        )

        val result = fetcher().fetch(request("/throttled"))

        assertThat(result).isEqualTo(
            FetchResult.Rejected(
                error = FetchError.HTTP_FAILURE,
                retryable = true,
                retryAfter = Duration.ofSeconds(9),
            ),
        )
    }

    @Test
    fun `fetch redirects share one deadline including policy and robots work`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/start" to MockResponse().setResponseCode(302).setHeader("Location", "/final"),
                "/final" to MockResponse().setHeader("Content-Type", "text/html").setBody("too late"),
            ),
        )
        val clock = MutableNanoTimeSource()
        val serverAddress = InetAddress.getByName(server.hostName)
        policy = DestinationValidator { _, _, _ ->
            clock.advance(Duration.ofMillis(25))
            PolicyDecision.Allowed(listOf(serverAddress))
        }
        val fetcher = CrawlFetcher(
            client, policy, UrlCanonicalizer(), robotsService(), nanoTimeSource = clock,
        )

        val result = fetcher.fetch(request("/start", maximumDuration = Duration.ofMillis(100)))

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.TIMEOUT, retryable = true))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `does not start browser when HTTP response already has meaningful content`() {
        val fetch = FetchResult.Success(
            url = server.url("/product").toUri(),
            status = 200,
            contentType = "text/html",
            body = "<p>Meaningful product details</p>".toByteArray(),
            etag = null,
            lastModified = null,
            method = FetchMethod.HTTP,
            contentHash = "hash",
        )
        var factoryCalls = 0
        val renderer = PlaywrightRenderer(client, policy, robotsService()) {
            factoryCalls++
            error("browser must not start")
        }

        val result = renderer.renderIfNeeded(
            fetch,
            request("/product"),
            parserRequiresJavaScript = false,
            meaningfulContentThreshold = 10,
        )

        assertThat(result).isSameAs(fetch)
        assertThat(factoryCalls).isZero()
    }

    @Test
    fun `does not render a supported document even when its body is small`() {
        val fetch = FetchResult.Success(
            url = server.url("/manual.pdf").toUri(),
            status = 200,
            contentType = "application/pdf",
            body = byteArrayOf(1, 2, 3),
            etag = null,
            lastModified = null,
            method = FetchMethod.HTTP,
            contentHash = "hash",
        )
        var factoryCalls = 0
        val renderer = PlaywrightRenderer(client, policy, robotsService()) {
            factoryCalls++
            error("documents must not start a browser")
        }

        val result = renderer.renderIfNeeded(
            fetch,
            request("/manual.pdf"),
            parserRequiresJavaScript = false,
            meaningfulContentThreshold = 100,
        )

        assertThat(result).isSameAs(fetch)
        assertThat(factoryCalls).isZero()
    }

    @Test
    fun `reuses shared browser with isolated contexts and closes lifecycle resources`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/product" to MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<html><body>network product</body></html>"),
            ),
        )
        val playwright = mockk<Playwright>()
        val browserType = mockk<BrowserType>()
        val browser = mockk<Browser>()
        val firstContext = mockContext("<html><body>first rendered product</body></html>")
        val secondContext = mockContext("<html><body>second rendered product</body></html>")
        val launchOptions = slot<BrowserType.LaunchOptions>()
        every { playwright.chromium() } returns browserType
        every { browserType.launch(capture(launchOptions)) } returns browser
        every { browser.newContext(any()) } returnsMany listOf(firstContext, secondContext)
        every { browser.close() } just Runs
        every { playwright.close() } just Runs
        val renderer = PlaywrightRenderer(client, policy, robotsService()) { playwright }
        val sparseFetch = FetchResult.Success(
            url = server.url("/product").toUri(),
            status = 200,
            contentType = "text/html",
            body = "<div></div>".toByteArray(),
            etag = null,
            lastModified = null,
            method = FetchMethod.HTTP,
            contentHash = "hash",
        )

        repeat(2) {
            assertThat(
                renderer.renderIfNeeded(
                    sparseFetch,
                    request("/product"),
                    parserRequiresJavaScript = false,
                    meaningfulContentThreshold = 10,
                    maximumWait = Duration.ofSeconds(1),
                ),
            ).isInstanceOf(FetchResult.Success::class.java)
        }
        renderer.close()

        verify(exactly = 1) { browserType.launch(any()) }
        verify(exactly = 2) { browser.newContext(any()) }
        verify(exactly = 1) { firstContext.close() }
        verify(exactly = 1) { secondContext.close() }
        verify(exactly = 1) { browser.close() }
        verify(exactly = 1) { playwright.close() }
        assertThat(launchOptions.captured.args).contains("--proxy-server=http://127.0.0.1:9")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `browser route aborts robots-disallowed subresource before fetching it`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nDisallow: /blocked.js"),
                "/product" to MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<html><script src='/blocked.js'></script></html>"),
            ),
        )
        val playwright = mockk<Playwright>()
        val browserType = mockk<BrowserType>()
        val browser = mockk<Browser>()
        val context = mockContext(
            "<html><script src='/blocked.js'></script></html>",
            listOf(TestRoute("/product", "document"), TestRoute("/blocked.js", "script")),
        )
        every { playwright.chromium() } returns browserType
        every { browserType.launch(any()) } returns browser
        every { browser.newContext(any()) } returns context
        every { browser.close() } just Runs
        every { playwright.close() } just Runs
        val renderer = PlaywrightRenderer(client, policy, robotsService()) { playwright }
        val sparseFetch = FetchResult.Success(
            server.url("/product").toUri(), 200, "text/html", "<div></div>".toByteArray(),
            null, null, FetchMethod.HTTP, "hash",
        )

        val result = renderer.renderIfNeeded(
            sparseFetch,
            request("/product"),
            parserRequiresJavaScript = true,
            maximumWait = Duration.ofSeconds(1),
        )

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.ROBOTS_DISALLOWED))
        assertThat(server.requestCount).isEqualTo(2)
        renderer.close()
    }

    @Test
    fun `renderer ignores intentionally aborted optional image font and media resources`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/product" to MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<html><body>product</body></html>"),
            ),
        )
        val harness = rendererHarness(
            mockContext(
                "<html><body>rendered product</body></html>",
                listOf(
                    TestRoute("/product", "document"),
                    TestRoute("/photo.png", "image"),
                    TestRoute("/font.woff2", "font"),
                    TestRoute("/video.mp4", "media"),
                ),
            ),
        )

        val result = harness.renderer.renderIfNeeded(
            sparseFetch(), request("/product"), parserRequiresJavaScript = true, maximumWait = Duration.ofSeconds(1),
        )

        assertThat(result).isInstanceOf(FetchResult.Success::class.java)
        assertThat(server.requestCount).isEqualTo(2)
        harness.close()
    }

    @Test
    fun `renderer keeps main-frame response metadata when an iframe loads later`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/product" to MockResponse().setHeader("Content-Type", "text/html")
                    .setHeader("ETag", "main-etag").setBody("<html><iframe></iframe></html>"),
                "/frame" to MockResponse().setHeader("Content-Type", "text/html")
                    .setHeader("ETag", "iframe-etag").setBody("<html>iframe</html>"),
            ),
        )
        val harness = rendererHarness(
            mockContext(
                "<html><iframe></iframe></html>",
                listOf(TestRoute("/product", "document"), TestRoute("/frame", "document", mainFrame = false)),
            ),
        )

        val result = harness.renderer.renderIfNeeded(
            sparseFetch(), request("/product"), parserRequiresJavaScript = true, maximumWait = Duration.ofSeconds(1),
        )

        assertThat(result).isInstanceOf(FetchResult.Success::class.java)
        assertThat((result as FetchResult.Success).etag).isEqualTo("main-etag")
        harness.close()
    }

    @Test
    fun `renderer observes a late route rejection before returning success`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nDisallow: /late.js"),
                "/product" to MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<html><body>product</body></html>"),
            ),
        )
        val harness = rendererHarness(
            mockContext(
                "<html><body>rendered product</body></html>",
                lateRoutedResources = listOf(TestRoute("/late.js", "script")),
            ),
        )

        val result = harness.renderer.renderIfNeeded(
            sparseFetch(), request("/product"), parserRequiresJavaScript = true, maximumWait = Duration.ofSeconds(1),
        )

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.ROBOTS_DISALLOWED))
        assertThat(server.requestCount).isEqualTo(2)
        harness.close()
    }

    @Test
    fun `renderer route validation and robots share the maximum wait deadline`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/product" to MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<html><body>too late</body></html>"),
            ),
        )
        val clock = MutableNanoTimeSource()
        val serverAddress = InetAddress.getByName(server.hostName)
        policy = DestinationValidator { _, _, _ ->
            clock.advance(Duration.ofMillis(60))
            PolicyDecision.Allowed(listOf(serverAddress))
        }
        val harness = rendererHarness(
            mockContext("<html><body>must not succeed</body></html>"),
            nanoTimeSource = clock,
        )

        val result = harness.renderer.renderIfNeeded(
            sparseFetch(), request("/product"), parserRequiresJavaScript = true,
            maximumWait = Duration.ofMillis(100),
        )

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.TIMEOUT))
        assertThat(server.requestCount).isEqualTo(1)
        harness.close()
    }

    @Test
    fun `renderer maximum wait includes time queued behind another render`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/product" to MockResponse().setHeader("Content-Type", "text/html").setBody("<html></html>"),
            ),
        )
        val enteredNavigation = CountDownLatch(1)
        val releaseNavigation = CountDownLatch(1)
        val firstContext = mockContext("<html>first</html>") {
            enteredNavigation.countDown()
            releaseNavigation.await(1, TimeUnit.SECONDS)
        }
        val secondContext = mockContext("<html>second</html>")
        val harness = rendererHarness(firstContext, additionalContexts = listOf(secondContext))
        val executor = Executors.newSingleThreadExecutor()
        val first = executor.submit<FetchResult> {
            harness.renderer.renderIfNeeded(
                sparseFetch(), request("/product"), parserRequiresJavaScript = true,
                maximumWait = Duration.ofSeconds(1),
            )
        }
        assertThat(enteredNavigation.await(1, TimeUnit.SECONDS)).isTrue()
        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(releaseNavigation::countDown)

        val second = harness.renderer.renderIfNeeded(
            sparseFetch(), request("/product"), parserRequiresJavaScript = true,
            maximumWait = Duration.ofMillis(50),
        )

        assertThat(second).isEqualTo(FetchResult.Rejected(FetchError.TIMEOUT))
        releaseNavigation.countDown()
        first.get(1, TimeUnit.SECONDS)
        executor.shutdownNow()
        harness.close()
    }

    @Test
    fun `renderer reports timeout when navigation exhausts the absolute deadline`() {
        val clock = MutableNanoTimeSource()
        val context = mockContext("<html>never reached</html>") {
            clock.advance(Duration.ofMillis(101))
            throw IllegalStateException("simulated Playwright navigation timeout")
        }
        val harness = rendererHarness(context, nanoTimeSource = clock)

        val result = harness.renderer.renderIfNeeded(
            sparseFetch(), request("/product"), parserRequiresJavaScript = true,
            maximumWait = Duration.ofMillis(100),
        )

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.TIMEOUT))
        harness.close()
    }

    @Test
    fun `renderer watchdog bounds a blocking DOM evaluate and closes its context`() {
        assertBlockingRendererCallIsBounded("evaluate")
    }

    @Test
    fun `renderer watchdog bounds blocking page content and closes its context`() {
        assertBlockingRendererCallIsBounded("content")
    }

    @Test
    fun `timed out Playwright creation keeps unrelated process alive and blocks replacement generation`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/product" to MockResponse().setHeader("Content-Type", "text/html").setBody("<html></html>"),
            ),
        )
        val firstFactoryEntered = CountDownLatch(1)
        val releaseFirstFactory = CountDownLatch(1)
        val firstPlaywrightClosed = CountDownLatch(1)
        val factoryCalls = AtomicInteger()
        val firstPlaywright = mockk<Playwright>()
        every { firstPlaywright.close() } answers { firstPlaywrightClosed.countDown() }
        val replacementContext = mockContext("<html>replacement</html>")
        val replacementPlaywright = mockPlaywright(replacementContext)
        val renderer = PlaywrightRenderer(client, policy, robotsService()) {
            if (factoryCalls.incrementAndGet() == 1) {
                firstFactoryEntered.countDown()
                awaitUninterruptibly(releaseFirstFactory)
                firstPlaywright
            } else {
                replacementPlaywright
            }
        }
        val renderExecutor = Executors.newSingleThreadExecutor()
        val firstRender = renderExecutor.submit<FetchResult> {
            renderer.renderIfNeeded(
                sparseFetch(), request("/product"), parserRequiresJavaScript = true,
                maximumWait = Duration.ofMillis(800),
            )
        }
        assertThat(firstFactoryEntered.await(1, TimeUnit.SECONDS)).isTrue()
        var unrelatedProcess: Process? = null

        try {
            val process = startUnrelatedPlaywrightNamedProcess()
            unrelatedProcess = process
            assertThat(process.inputStream.bufferedReader().readLine()).isEqualTo("READY")
            assertThat(process.isAlive).isTrue()
            val firstResult = firstRender.get(2, TimeUnit.SECONDS)
            val secondResult = renderer.renderIfNeeded(
                sparseFetch(), request("/product"), parserRequiresJavaScript = true,
                maximumWait = Duration.ofSeconds(1),
            )
            val unrelatedExitedAfterTimeout = process.waitFor(250, TimeUnit.MILLISECONDS)

            assertThat(firstResult).isEqualTo(FetchResult.Rejected(FetchError.TIMEOUT))
            assertThat(unrelatedExitedAfterTimeout).isFalse()
            assertThat(secondResult).isEqualTo(FetchResult.Rejected(FetchError.TIMEOUT))
            assertThat(factoryCalls.get()).isEqualTo(1)
        } finally {
            releaseFirstFactory.countDown()
            assertThat(firstPlaywrightClosed.await(1, TimeUnit.SECONDS)).isTrue()
            renderer.close()
            renderExecutor.shutdownNow()
            unrelatedProcess?.destroyForcibly()
            unrelatedProcess?.waitFor(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `forced renderer close poisons active worker and closes lifecycle on owner thread`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/product" to MockResponse().setHeader("Content-Type", "text/html").setBody("<html></html>"),
            ),
        )
        val context = mockContext("<html>rendered</html>")
        val page = renderedPages.last()
        val blockingCallEntered = CountDownLatch(1)
        val releaseBlockingCall = CountDownLatch(1)
        val browserClosed = CountDownLatch(1)
        val playwrightClosed = CountDownLatch(1)
        val renderingThread = AtomicReference<Thread>()
        val browserCloseThread = AtomicReference<Thread>()
        val playwrightCloseThread = AtomicReference<Thread>()
        every { page.content() } answers {
            renderingThread.set(Thread.currentThread())
            blockingCallEntered.countDown()
            awaitUninterruptibly(releaseBlockingCall)
            "<html>rendered</html>"
        }
        val harness = rendererHarness(context, closeTimeout = Duration.ofMillis(100))
        every { harness.browser.close() } answers {
            browserCloseThread.set(Thread.currentThread())
            browserClosed.countDown()
        }
        every { harness.playwright.close() } answers {
            playwrightCloseThread.set(Thread.currentThread())
            playwrightClosed.countDown()
        }
        val renderExecutor = Executors.newSingleThreadExecutor()
        val render = renderExecutor.submit<FetchResult> {
            harness.renderer.renderIfNeeded(
                sparseFetch(), request("/product"), parserRequiresJavaScript = true,
                maximumWait = Duration.ofSeconds(3),
            )
        }
        assertThat(blockingCallEntered.await(1, TimeUnit.SECONDS)).isTrue()

        try {
            val closeStartedAt = System.nanoTime()
            harness.close()
            val closeElapsed = Duration.ofNanos(System.nanoTime() - closeStartedAt)

            assertThat(closeElapsed).isLessThan(Duration.ofSeconds(1))
            assertThat(browserClosed.count).isEqualTo(1)
            assertThat(playwrightClosed.count).isEqualTo(1)
            releaseBlockingCall.countDown()
            render.get(2, TimeUnit.SECONDS)
            assertThat(browserClosed.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(playwrightClosed.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(browserCloseThread.get()).isSameAs(renderingThread.get())
            assertThat(playwrightCloseThread.get()).isSameAs(renderingThread.get())
        } finally {
            releaseBlockingCall.countDown()
            renderExecutor.shutdownNow()
        }
    }

    @Test
    fun `renderer rejects an invalid script MIME from a captured route`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/product" to MockResponse().setHeader("Content-Type", "text/html").setBody("<html></html>"),
                "/bad.js" to MockResponse().setHeader("Content-Type", "text/plain").setBody("alert(1)"),
            ),
        )
        val harness = rendererHarness(
            mockContext(
                "<html><script></script></html>",
                listOf(TestRoute("/product", "document"), TestRoute("/bad.js", "script")),
            ),
        )

        val result = harness.renderer.renderIfNeeded(
            sparseFetch(), request("/product"), parserRequiresJavaScript = true, maximumWait = Duration.ofSeconds(1),
        )

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.UNSUPPORTED_CONTENT_TYPE))
        harness.close()
    }

    @Test
    fun `renderer enforces the per-response subresource byte cap`() {
        val oversizedScript = "x".repeat(1024 * 1024 + 1)
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/product" to MockResponse().setHeader("Content-Type", "text/html").setBody("<html></html>"),
                "/large.js" to MockResponse().setHeader("Content-Type", "application/javascript")
                    .setChunkedBody(oversizedScript, 8192),
            ),
        )
        val harness = rendererHarness(
            mockContext(
                "<html><script></script></html>",
                listOf(TestRoute("/product", "document"), TestRoute("/large.js", "script")),
            ),
        )

        val result = harness.renderer.renderIfNeeded(
            sparseFetch(), request("/product", maxResponseBytes = 2L * 1024 * 1024),
            parserRequiresJavaScript = true, maximumWait = Duration.ofSeconds(2),
        )

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.RESPONSE_TOO_LARGE))
        harness.close()
    }

    @Test
    fun `renderer enforces the aggregate routed response byte cap`() {
        val responses = mutableMapOf<String, MockResponse>(
            "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
            "/product" to MockResponse().setHeader("Content-Type", "text/html").setBody("<html></html>"),
        )
        val routes = mutableListOf(TestRoute("/product", "document"))
        repeat(5) { index ->
            responses["/part-$index.js"] = MockResponse().setHeader("Content-Type", "application/javascript")
                .setBody("x".repeat(900))
            routes += TestRoute("/part-$index.js", "script")
        }
        server.dispatcher = pathDispatcher(responses)
        val harness = rendererHarness(mockContext("<html><body>small DOM</body></html>", routes))

        val result = harness.renderer.renderIfNeeded(
            sparseFetch(), request("/product", maxResponseBytes = 1024),
            parserRequiresJavaScript = true, maximumWait = Duration.ofSeconds(2),
        )

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.RESPONSE_TOO_LARGE))
        harness.close()
    }

    @Test
    fun `renderer rejects oversized DOM before allocating page content in JVM`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/product" to MockResponse().setHeader("Content-Type", "text/html").setBody("<div></div>"),
            ),
        )
        val playwright = mockk<Playwright>()
        val browserType = mockk<BrowserType>()
        val browser = mockk<Browser>()
        val context = mockContext("must not allocate", domByteLength = 2048)
        every { playwright.chromium() } returns browserType
        every { browserType.launch(any()) } returns browser
        every { browser.newContext(any()) } returns context
        every { browser.close() } just Runs
        every { playwright.close() } just Runs
        val renderer = PlaywrightRenderer(client, policy, robotsService()) { playwright }
        val sparseFetch = FetchResult.Success(
            server.url("/product").toUri(), 200, "text/html", "<div></div>".toByteArray(),
            null, null, FetchMethod.HTTP, "hash",
        )

        val result = renderer.renderIfNeeded(
            sparseFetch,
            request("/product", maxResponseBytes = 1024),
            parserRequiresJavaScript = true,
            maximumWait = Duration.ofSeconds(1),
        )

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.RESPONSE_TOO_LARGE))
        verify(exactly = 0) { renderedPages.last().content() }
        renderer.close()
    }

    private fun fetcher() = CrawlFetcher(
        client = client,
        crawlPolicy = policy,
        canonicalizer = UrlCanonicalizer(),
        robotsPolicy = robotsService(),
    )

    private fun robotsService() = RobotsPolicyService(
        client = client,
        destinationValidator = policy,
        maxAttempts = 2,
        sleeper = RetrySleeper { },
        jitterMillis = { 0 },
    )

    private fun request(
        path: String,
        maxResponseBytes: Long = 1024,
        previous: StoredFetchContent? = null,
        maxRedirects: Int = 5,
        maximumDuration: Duration = Duration.ofSeconds(20),
    ): CrawlFetchRequest {
        val root = server.url("/").toUri()
        return CrawlFetchRequest(
            url = server.url(path).toUri(),
            supplierRoot = root,
            explicitHosts = emptySet(),
            userAgent = "rfp-crawler",
            maxResponseBytes = maxResponseBytes,
            maxRedirects = maxRedirects,
            maximumDuration = maximumDuration,
            previous = previous,
        )
    }

    private fun pathDispatcher(responses: Map<String, MockResponse>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            responses[request.path] ?: MockResponse().setResponseCode(404)
    }

    private fun mockContext(
        html: String,
        routedResources: List<TestRoute> = listOf(TestRoute("/product", "document")),
        lateRoutedResources: List<TestRoute> = emptyList(),
        domByteLength: Int = html.toByteArray().size,
        beforeNavigate: () -> Unit = {},
    ): BrowserContext {
        val context = mockk<BrowserContext>()
        val page = mockk<Page>()
        val response = mockk<Response>()
        val mainFrame = mockk<Frame>()
        val childFrame = mockk<Frame>()
        renderedPages += page
        fun routeFor(spec: TestRoute): com.microsoft.playwright.Route {
            val route = mockk<com.microsoft.playwright.Route>()
            val browserRequest = mockk<com.microsoft.playwright.Request>()
            every { route.request() } returns browserRequest
            every { route.fulfill(any()) } just Runs
            every { route.abort() } just Runs
            every { browserRequest.url() } returns server.url(spec.path).toString()
            every { browserRequest.resourceType() } returns spec.resourceType
            every { browserRequest.isNavigationRequest() } returns (spec.resourceType == "document")
            every { browserRequest.frame() } returns if (spec.mainFrame) mainFrame else childFrame
            return route
        }
        val routes = routedResources.map(::routeFor)
        val lateRoutes = lateRoutedResources.map(::routeFor)
        val handler = slot<Consumer<com.microsoft.playwright.Route>>()
        every { context.route("**/*", capture(handler)) } just Runs
        every { context.newPage() } returns page
        every { context.close() } just Runs
        every { page.mainFrame() } returns mainFrame
        every { page.addInitScript(any<String>()) } just Runs
        every { page.navigate(any(), any()) } answers {
            beforeNavigate()
            routes.forEach(handler.captured::accept)
            response
        }
        every { page.content() } returns html
        every { page.evaluate(match<String> { it.contains("TextEncoder") }) } returns
            domByteLength
        every { page.evaluate(match<String> { it.contains("__rfpMutationCount") }) } returns "0:${html.length}"
        var lateRoutesDelivered = false
        every { page.waitForTimeout(any()) } answers {
            if (!lateRoutesDelivered) {
                lateRoutesDelivered = true
                lateRoutes.forEach(handler.captured::accept)
            }
        }
        every { page.url() } returns server.url("/product").toString()
        every { response.status() } returns 200
        return context
    }

    private fun sparseFetch() = FetchResult.Success(
        server.url("/product").toUri(), 200, "text/html", "<div></div>".toByteArray(),
        null, null, FetchMethod.HTTP, "hash",
    )

    private fun assertBlockingRendererCallIsBounded(blockingCall: String) {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/product" to MockResponse().setHeader("Content-Type", "text/html").setBody("<html></html>"),
            ),
        )
        val context = mockContext("<html>rendered</html>")
        val page = renderedPages.last()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val contextClosed = CountDownLatch(1)
        every { context.close() } answers { contextClosed.countDown() }
        if (blockingCall == "evaluate") {
            every { page.evaluate(match<String> { it.contains("TextEncoder") }) } answers {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                32
            }
        } else {
            every { page.content() } answers {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                "<html>rendered</html>"
            }
        }
        val harness = rendererHarness(context)
        CompletableFuture.delayedExecutor(1200, TimeUnit.MILLISECONDS).execute(release::countDown)

        val startedAt = System.nanoTime()
        val result = harness.renderer.renderIfNeeded(
            sparseFetch(), request("/product"), parserRequiresJavaScript = true,
            maximumWait = Duration.ofMillis(500),
        )
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        assertThat(entered.await(100, TimeUnit.MILLISECONDS)).isTrue()
        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.TIMEOUT))
        assertThat(elapsed).isLessThan(Duration.ofMillis(850))
        assertThat(contextClosed.await(500, TimeUnit.MILLISECONDS)).isTrue()
        verify(timeout = 500) { harness.browser.close() }
        verify(timeout = 500) { harness.playwright.close() }
        release.countDown()
        harness.close()
    }

    private fun rendererHarness(
        context: BrowserContext,
        nanoTimeSource: NanoTimeSource = SystemNanoTimeSource,
        additionalContexts: List<BrowserContext> = emptyList(),
        closeTimeout: Duration = Duration.ofSeconds(5),
    ): RendererHarness {
        val playwright = mockk<Playwright>()
        val browserType = mockk<BrowserType>()
        val browser = mockk<Browser>()
        every { playwright.chromium() } returns browserType
        every { browserType.launch(any()) } returns browser
        every { browser.newContext(any()) } returnsMany (listOf(context) + additionalContexts)
        every { browser.close() } just Runs
        every { playwright.close() } just Runs
        return RendererHarness(
            renderer = PlaywrightRenderer(
                client, policy, robotsService(), nanoTimeSource = nanoTimeSource, closeTimeout = closeTimeout,
            ) {
                playwright
            },
            browser = browser,
            playwright = playwright,
        )
    }

    private fun mockPlaywright(context: BrowserContext): Playwright {
        val playwright = mockk<Playwright>()
        val browserType = mockk<BrowserType>()
        val browser = mockk<Browser>()
        every { playwright.chromium() } returns browserType
        every { browserType.launch(any()) } returns browser
        every { browser.newContext(any()) } returns context
        every { browser.close() } just Runs
        every { playwright.close() } just Runs
        return playwright
    }

    private fun startUnrelatedPlaywrightNamedProcess(): Process = ProcessBuilder(
        ProcessHandle.current().info().command().orElseThrow(),
        "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        PlaywrightUnrelatedProcess::class.java.name,
    )
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private data class TestRoute(val path: String, val resourceType: String, val mainFrame: Boolean = true)

    private data class RendererHarness(
        val renderer: PlaywrightRenderer,
        val browser: Browser,
        val playwright: Playwright,
    ) {
        fun close() = renderer.close()
    }

    private class MutableNanoTimeSource : NanoTimeSource {
        private var nanos = 0L
        override fun nanoTime(): Long = nanos
        fun advance(duration: Duration) {
            nanos += duration.toNanos()
        }
    }

    private val renderedPages = mutableListOf<Page>()
}

object PlaywrightUnrelatedProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        println("READY")
        System.out.flush()
        Thread.sleep(TimeUnit.SECONDS.toMillis(30))
    }
}
