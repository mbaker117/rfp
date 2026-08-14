package com.rfp.service.crawl

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit

class ValidatedHttpTransportTest {
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
    fun `connects only to validated address while preserving hostname`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("pinned"))
        val validatedAddress = InetAddress.getByName(server.hostName)
        val validator = DestinationValidator { _, _, _ -> PolicyDecision.Allowed(listOf(validatedAddress)) }
        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    throw AssertionError("base DNS must not be used")
            })
            .build()
        val transport = ValidatedHttpTransport(client, validator)
        val uri = URI("http://rebinding.invalid:${server.port}/product")

        val result = transport.execute(
            TransportRequest(uri, CrawlScope(uri, emptySet()), "rfp-crawler", 1024),
        )

        assertThat(result).isInstanceOf(TransportResult.Success::class.java)
        assertThat((result as TransportResult.Success).body.toString(Charsets.UTF_8)).isEqualTo("pinned")
        assertThat(server.takeRequest().getHeader("Host")).isEqualTo("rebinding.invalid:${server.port}")
    }

    @Test
    fun `enforces total call deadline and closes timed out response`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody("late")
                .setBodyDelay(2, TimeUnit.SECONDS),
        )
        val validatedAddress = InetAddress.getByName(server.hostName)
        val transport = ValidatedHttpTransport(
            OkHttpClient(),
            DestinationValidator { _, _, _ -> PolicyDecision.Allowed(listOf(validatedAddress)) },
            callTimeout = Duration.ofMillis(100),
        )
        val uri = URI("http://deadline.invalid:${server.port}/slow")

        val result = transport.execute(
            TransportRequest(uri, CrawlScope(uri, emptySet()), "rfp-crawler", 1024),
        )

        assertThat(result).isEqualTo(TransportResult.Failure(TransportError.TIMEOUT))
    }

    @Test
    fun `absolute deadline includes destination validation before network I O`() {
        val clock = MutableNanoTimeSource()
        val validatedAddress = InetAddress.getByName(server.hostName)
        val transport = ValidatedHttpTransport(
            OkHttpClient(),
            DestinationValidator { _, _, _ ->
                clock.advance(Duration.ofMillis(101))
                PolicyDecision.Allowed(listOf(validatedAddress))
            },
        )
        val uri = URI("http://deadline.invalid:${server.port}/never-requested")
        val deadline = DeadlineBudget.start(Duration.ofMillis(100), clock)

        val result = transport.execute(
            TransportRequest(uri, CrawlScope(uri, emptySet()), "rfp-crawler", 1024, deadline = deadline),
        )

        assertThat(result).isEqualTo(TransportResult.Failure(TransportError.TIMEOUT))
        assertThat(server.requestCount).isZero()
    }

    private class MutableNanoTimeSource : NanoTimeSource {
        private var nanos = 0L
        override fun nanoTime(): Long = nanos
        fun advance(duration: Duration) {
            nanos += duration.toNanos()
        }
    }
}
