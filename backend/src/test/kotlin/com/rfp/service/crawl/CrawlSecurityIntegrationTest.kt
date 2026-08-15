package com.rfp.service.crawl

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.URI

/**
 * Security tests for the URL safety layer.
 *
 * Tests that [CrawlPolicy] and [CrawlFetcher] correctly block:
 * - Private/non-public IP addresses (SSRF via DNS rebinding)
 * - SSRF via HTTP redirect to an internal address
 * - Paths disallowed by the target site's robots.txt
 *
 * These tests use [MockWebServer] for HTTP fixture (no real internet), a permissive
 * [DestinationValidator] for MockWebServer's localhost address, and the real [CrawlPolicy]
 * for validating that private IP addresses are rejected.
 */
class CrawlSecurityIntegrationTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // -------------------------------------------------------------------------
    // Test 1: Private IP rejected by CrawlPolicy
    // -------------------------------------------------------------------------

    @Test
    fun `private IP address is rejected before fetch`() {
        // Simulate DNS resolving a public-looking hostname to a private RFC-1918 address.
        // This represents a DNS-rebinding / SSRF attack vector.
        val privateIp = InetAddress.getByName("192.168.1.1")
        val policy = CrawlPolicy(dnsResolver = { listOf(privateIp) })
        val supplierRoot = URI("https://example.com")

        val result = policy.validate(
            URI("https://example.com/products"),
            supplierRoot,
            emptySet(),
        )

        assertThat(result).isInstanceOf(PolicyDecision.Rejected::class.java)
        assertThat((result as PolicyDecision.Rejected).reason)
            .isEqualTo(PolicyRejection.NON_PUBLIC_ADDRESS)
    }

    // -------------------------------------------------------------------------
    // Test 2: SSRF via redirect to internal address
    // -------------------------------------------------------------------------

    @Test
    fun `SSRF via redirect to internal address is blocked`() {
        // Setup: MockWebServer serves a 301 redirect to an internal address.
        // The CrawlFetcher must re-validate the redirect target through the policy before
        // following it; since 127.0.0.1 resolves to a non-public address, the fetch must fail.
        val serverAddress = InetAddress.getByName(server.hostName)

        // Custom policy: allow the MockWebServer host, block 127.0.0.1 as a redirect target.
        // This mirrors what CrawlPolicy does for redirect targets that resolve to loopback
        // (isLoopbackAddress → isPublicDestination returns false → NON_PUBLIC_ADDRESS).
        val policy = DestinationValidator { uri, _, _ ->
            when (uri.host) {
                "127.0.0.1" -> PolicyDecision.Rejected(PolicyRejection.NON_PUBLIC_ADDRESS)
                else -> PolicyDecision.Allowed(listOf(serverAddress))
            }
        }

        // Enqueue: first request is robots.txt (fetched by RobotsPolicyService), second is /start
        server.enqueue(MockResponse().setBody("User-agent: *\nAllow: /"))
        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .setHeader("Location", "http://127.0.0.1/internal"),
        )

        val fetcher = buildFetcher(policy)
        val result = fetcher.fetch(
            CrawlFetchRequest(
                url = server.url("/start").toUri(),
                supplierRoot = server.url("/").toUri(),
                maxResponseBytes = 1024 * 1024,
                maximumDuration = java.time.Duration.ofSeconds(10),
            )
        )

        // The redirect target (127.0.0.1) is blocked by policy — fetch is rejected
        assertThat(result).isInstanceOf(FetchResult.Rejected::class.java)
        assertThat((result as FetchResult.Rejected).error).isEqualTo(FetchError.POLICY_REJECTED)
    }

    // -------------------------------------------------------------------------
    // Test 3: robots.txt disallow blocks page fetch
    // -------------------------------------------------------------------------

    @Test
    fun `robots_txt disallow blocks page fetch for disallowed path`() {
        // Setup: robots.txt disallows the /admin/ path. The fetcher should check robots
        // before issuing any request to /admin/products and return ROBOTS_DISALLOWED.
        val serverAddress = InetAddress.getByName(server.hostName)
        val permissivePolicy = DestinationValidator { _, _, _ ->
            PolicyDecision.Allowed(listOf(serverAddress))
        }

        // The only request the server receives should be the robots.txt probe
        server.enqueue(MockResponse().setBody("User-agent: *\nDisallow: /admin/"))

        val fetcher = buildFetcher(permissivePolicy)
        val result = fetcher.fetch(
            CrawlFetchRequest(
                url = server.url("/admin/products").toUri(),
                supplierRoot = server.url("/").toUri(),
                maxResponseBytes = 1024 * 1024,
                maximumDuration = java.time.Duration.ofSeconds(10),
            )
        )

        assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.ROBOTS_DISALLOWED))
        // Only robots.txt was requested — /admin/products was never fetched
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(server.takeRequest().path).isEqualTo("/robots.txt")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildFetcher(policy: DestinationValidator): CrawlFetcher {
        val client = OkHttpClient()
        val robotsPolicy = RobotsPolicyService(
            client = client,
            destinationValidator = policy,
            maxAttempts = 2,
            sleeper = RetrySleeper { },
            jitterMillis = { 0 },
        )
        return CrawlFetcher(
            client = client,
            crawlPolicy = policy,
            canonicalizer = UrlCanonicalizer(),
            robotsPolicy = robotsPolicy,
        )
    }
}
