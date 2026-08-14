package com.rfp.service.crawl

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RobotsPolicyServiceTest {
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

    @Test
    fun `applies user-agent rules and caches them by origin`() {
        server.dispatcher = pathDispatcher(
            mapOf(
                "/robots.txt" to MockResponse().setBody(
                    "User-agent: rfp-crawler\nDisallow: /private/\n\nUser-agent: *\nAllow: /",
                ),
            ),
        )
        val service = service()

        assertThat(service.canFetch(server.url("/private/item").toUri(), "rfp-crawler"))
            .isEqualTo(RobotsDecision.Disallowed)
        assertThat(service.canFetch(server.url("/public/item").toUri(), "rfp-crawler"))
            .isEqualTo(RobotsDecision.Allowed)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `cached origin applies rules for each requesting user-agent`() {
        server.enqueue(
            MockResponse().setBody(
                "User-agent: first-crawler\nDisallow: /first-only/\n\n" +
                    "User-agent: second-crawler\nDisallow: /second-only/",
            ),
        )
        val service = service()

        assertThat(service.canFetch(server.url("/first-only/item").toUri(), "first-crawler"))
            .isEqualTo(RobotsDecision.Disallowed)
        assertThat(service.canFetch(server.url("/first-only/item").toUri(), "second-crawler"))
            .isEqualTo(RobotsDecision.Allowed)
        assertThat(service.canFetch(server.url("/second-only/item").toUri(), "second-crawler"))
            .isEqualTo(RobotsDecision.Disallowed)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `fails closed after bounded robots retrieval attempts`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        val service = service(maxAttempts = 2)

        assertThat(service.canFetch(server.url("/product").toUri(), "rfp-crawler"))
            .isEqualTo(RobotsDecision.Unavailable(retryable = true, retryAfter = null))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `refreshes cached rules after expiry`() {
        val clock = MutableClock(Instant.parse("2026-08-14T00:00:00Z"))
        server.enqueue(MockResponse().setBody("User-agent: *\nAllow: /"))
        server.enqueue(MockResponse().setBody("User-agent: *\nDisallow: /"))
        val service = service(cacheTtl = Duration.ofMinutes(5), clock = clock)
        val uri = server.url("/product").toUri()

        assertThat(service.canFetch(uri, "rfp-crawler")).isEqualTo(RobotsDecision.Allowed)
        clock.instant = clock.instant.plus(Duration.ofMinutes(6))

        assertThat(service.canFetch(uri, "rfp-crawler")).isEqualTo(RobotsDecision.Disallowed)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `follows robots redirect manually and caches under requesting origin`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/policy/robots.txt"))
        server.enqueue(MockResponse().setBody("User-agent: *\nDisallow: /private/"))
        val service = service()

        assertThat(service.canFetch(server.url("/private/item").toUri(), "rfp-crawler"))
            .isEqualTo(RobotsDecision.Disallowed)
        assertThat(service.canFetch(server.url("/public/item").toUri(), "rfp-crawler"))
            .isEqualTo(RobotsDecision.Allowed)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `429 remains retryable and preserves retry-after without allow-all caching`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        val service = service(maxAttempts = 2)

        val decision = service.canFetch(server.url("/product").toUri(), "rfp-crawler")

        assertThat(decision).isEqualTo(
            RobotsDecision.Unavailable(retryable = true, retryAfter = Duration.ofSeconds(7)),
        )
        assertThat(server.requestCount).isEqualTo(2)
    }

    private fun service(
        maxAttempts: Int = 2,
        cacheTtl: Duration = Duration.ofHours(1),
        clock: Clock = Clock.systemUTC(),
    ) = RobotsPolicyService(
        client = OkHttpClient(),
        destinationValidator = DestinationValidator { _, _, _ ->
            PolicyDecision.Allowed(listOf(java.net.InetAddress.getByName(server.hostName)))
        },
        maxAttempts = maxAttempts,
        cacheTtl = cacheTtl,
        clock = clock,
        sleeper = RetrySleeper { },
        jitterMillis = { 0 },
    )

    private fun pathDispatcher(responses: Map<String, MockResponse>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            responses[request.path] ?: MockResponse().setResponseCode(404)
    }

    private class MutableClock(var instant: Instant) : Clock() {
        override fun instant(): Instant = instant

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock = this
    }
}
