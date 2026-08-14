package com.rfp.service.crawl

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Response
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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

class CrawlFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var policy: CrawlPolicy

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        policy = CrawlPolicy(DnsResolver { listOf(InetAddress.getByName("8.8.8.8")) })
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
        policy = CrawlPolicy(DnsResolver { host ->
            if (host == "metadata.example.com") {
                listOf(InetAddress.getByName("169.254.169.254"))
            } else {
                listOf(InetAddress.getByName("8.8.8.8"))
            }
        })

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
    fun `reuses stored body and metadata on not-modified response`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /"),
                "/cached" to MockResponse().setResponseCode(304),
            ),
        )
        val previous = StoredFetchContent(
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
    fun `does not fetch target when robots policy cannot be established`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))

        val result = fetcher().fetch(request("/product"))

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.ROBOTS_UNAVAILABLE))
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
        val renderer = PlaywrightRenderer(policy, RobotsPolicyService(client)) {
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
        val renderer = PlaywrightRenderer(policy, RobotsPolicyService(client)) {
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
            mapOf("/robots.txt" to MockResponse().setBody("User-agent: *\nAllow: /")),
        )
        val playwright = mockk<Playwright>()
        val browserType = mockk<BrowserType>()
        val browser = mockk<Browser>()
        val firstContext = mockContext("<html><body>first rendered product</body></html>")
        val secondContext = mockContext("<html><body>second rendered product</body></html>")
        every { playwright.chromium() } returns browserType
        every { browserType.launch(any()) } returns browser
        every { browser.newContext(any()) } returnsMany listOf(firstContext, secondContext)
        every { browser.close() } just Runs
        every { playwright.close() } just Runs
        val renderer = PlaywrightRenderer(policy, RobotsPolicyService(client)) { playwright }
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
    }

    private fun fetcher() = CrawlFetcher(
        client = client,
        crawlPolicy = policy,
        canonicalizer = UrlCanonicalizer(),
        robotsPolicy = RobotsPolicyService(client, maxAttempts = 2),
    )

    private fun request(
        path: String,
        maxResponseBytes: Long = 1024,
        previous: StoredFetchContent? = null,
    ): CrawlFetchRequest {
        val root = server.url("/").toUri()
        return CrawlFetchRequest(
            url = server.url(path).toUri(),
            supplierRoot = root,
            explicitHosts = emptySet(),
            userAgent = "rfp-crawler",
            maxResponseBytes = maxResponseBytes,
            previous = previous,
        )
    }

    private fun pathDispatcher(responses: Map<String, MockResponse>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            responses[request.path] ?: MockResponse().setResponseCode(404)
    }

    private fun mockContext(html: String): BrowserContext {
        val context = mockk<BrowserContext>()
        val page = mockk<Page>()
        val response = mockk<Response>()
        every { context.route("**/*", any()) } just Runs
        every { context.newPage() } returns page
        every { context.close() } just Runs
        every { page.navigate(any(), any()) } returns response
        every { page.content() } returns html
        every { page.waitForTimeout(any()) } just Runs
        every { page.url() } returns server.url("/product").toString()
        every { response.status() } returns 200
        return context
    }
}
