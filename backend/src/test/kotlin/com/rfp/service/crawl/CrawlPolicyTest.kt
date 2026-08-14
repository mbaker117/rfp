package com.rfp.service.crawl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.Inet6Address
import java.net.URI
import java.net.UnknownHostException

class CrawlPolicyTest {
    private val resolver = FakeDnsResolver()
    private val policy = CrawlPolicy(resolver)

    @Test
    fun `allows supplier registrable domain and its subdomains`() {
        resolver.answers["cdn.example.com"] = listOf(InetAddress.getByName("8.8.8.8"))

        val decision = policy.validate(
            URI("https://cdn.example.com/manual.pdf"),
            URI("https://shop.example.com"),
            emptySet(),
        )

        assertThat(decision).isEqualTo(
            PolicyDecision.Allowed(listOf(InetAddress.getByName("8.8.8.8"))),
        )
    }

    @Test
    fun `uses public suffix list when comparing registrable domains`() {
        resolver.answers["evil.co.uk"] = listOf(InetAddress.getByName("8.8.8.8"))
        resolver.answers["cdn.example.co.uk"] = listOf(InetAddress.getByName("8.8.4.4"))

        assertThat(
            policy.validate(
                URI("https://evil.co.uk/manual.pdf"),
                URI("https://shop.example.co.uk"),
                emptySet(),
            ),
        ).isEqualTo(PolicyDecision.Rejected(PolicyRejection.HOST_NOT_ALLOWED))
        assertThat(
            policy.validate(
                URI("https://cdn.example.co.uk/manual.pdf"),
                URI("https://shop.example.co.uk"),
                emptySet(),
            ),
        ).isEqualTo(
            PolicyDecision.Allowed(listOf(InetAddress.getByName("8.8.4.4"))),
        )
    }

    @Test
    fun `allows subdomain and exact explicit separate documentation host`() {
        resolver.answers["docs.vendor.net"] = listOf(InetAddress.getByName("8.8.8.8"))

        val decision = policy.validate(
            URI("https://docs.vendor.net/m.pdf"),
            URI("https://shop.example.com"),
            setOf("DOCS.VENDOR.NET."),
        )

        assertThat(decision).isInstanceOf(PolicyDecision.Allowed::class.java)
    }

    @Test
    fun `rejects documentation-only address ranges as non-public`() {
        val addresses = listOf(
            "192.0.2.20",
            "198.51.100.20",
            "203.0.113.20",
            "2001:db8::20",
        )

        addresses.forEachIndexed { index, address ->
            val host = "reserved$index.example.com"
            resolver.answers[host] = listOf(InetAddress.getByName(address))

            assertThat(
                policy.validate(
                    URI("https://$host/manual.pdf"),
                    URI("https://example.com"),
                    emptySet(),
                ),
            ).isEqualTo(PolicyDecision.Rejected(PolicyRejection.NON_PUBLIC_ADDRESS))
        }
    }

    @Test
    fun `does not extend explicit host permission to its subdomains`() {
        resolver.answers["sub.docs.vendor.net"] = listOf(InetAddress.getByName("8.8.8.8"))

        val decision = policy.validate(
            URI("https://sub.docs.vendor.net/m.pdf"),
            URI("https://shop.example.com"),
            setOf("docs.vendor.net"),
        )

        assertThat(decision).isEqualTo(PolicyDecision.Rejected(PolicyRejection.HOST_NOT_ALLOWED))
    }

    @Test
    fun `rejects redirect destination resolving to metadata address`() {
        resolver.answers["redirect.example.com"] = listOf(InetAddress.getByName("169.254.169.254"))

        val decision = policy.validate(
            URI("https://redirect.example.com/latest/meta-data"),
            URI("https://example.com"),
            emptySet(),
        )

        assertThat(decision).isEqualTo(
            PolicyDecision.Rejected(PolicyRejection.NON_PUBLIC_ADDRESS),
        )
    }

    @Test
    fun `rejects destination when any DNS answer is non-public`() {
        resolver.answers["mixed.example.com"] = listOf(
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("10.0.0.7"),
        )

        val decision = policy.validate(
            URI("https://mixed.example.com/catalog"),
            URI("https://example.com"),
            emptySet(),
        )

        assertThat(decision).isEqualTo(
            PolicyDecision.Rejected(PolicyRejection.NON_PUBLIC_ADDRESS),
        )
    }

    @Test
    fun `rejects unique-local IPv6 answer`() {
        resolver.answers["ipv6.example.com"] = listOf(InetAddress.getByName("fd00::1"))

        val decision = policy.validate(
            URI("https://ipv6.example.com/catalog"),
            URI("https://example.com"),
            emptySet(),
        )

        assertThat(decision).isEqualTo(
            PolicyDecision.Rejected(PolicyRejection.NON_PUBLIC_ADDRESS),
        )
    }

    @Test
    fun `allows public IPv6 literal for matching supplier root`() {
        resolver.answers["2001:4860:4860::8888"] = listOf(InetAddress.getByName("2001:4860:4860::8888"))

        val decision = policy.validate(
            URI("https://[2001:4860:4860::8888]/catalog"),
            URI("https://[2001:4860:4860::8888]"),
            emptySet(),
        )

        assertThat(decision).isInstanceOf(PolicyDecision.Allowed::class.java)
    }

    @Test
    fun `rejects IPv4-mapped IPv6 loopback private and metadata addresses`() {
        val answers = listOf(
            mappedIpv6(127, 0, 0, 1),
            mappedIpv6(10, 0, 0, 7),
            mappedIpv6(169, 254, 169, 254),
        )

        val decisions = answers.mapIndexed { index, answer ->
            val host = "mapped$index.example.com"
            resolver.answers[host] = listOf(answer)
            policy.validate(URI("https://$host/catalog"), URI("https://example.com"), emptySet())
        }

        assertThat(decisions).containsOnly(PolicyDecision.Rejected(PolicyRejection.NON_PUBLIC_ADDRESS))
    }

    @Test
    fun `rejects IPv4 embedding transition ranges`() {
        val answers = listOf(
            InetAddress.getByName("64:ff9b::7f00:1"),
            InetAddress.getByName("2002:7f00:1::"),
            InetAddress.getByName("2001:0:4136:e378:8000:63bf:3fff:fdd2"),
            InetAddress.getByName("2001:4860:1:2:0:5efe:7f00:1"),
            InetAddress.getByName("0:0:0:0:ffff:0:7f00:1"),
        )

        val decisions = answers.mapIndexed { index, answer ->
            val host = "transition$index.example.com"
            resolver.answers[host] = listOf(answer)
            policy.validate(URI("https://$host/catalog"), URI("https://example.com"), emptySet())
        }

        assertThat(decisions).containsOnly(PolicyDecision.Rejected(PolicyRejection.NON_PUBLIC_ADDRESS))
    }

    @Test
    fun `rejects representative non-global IANA IPv6 ranges`() {
        val answers = listOf(
            InetAddress.getByName("100::1"),
            InetAddress.getByName("2001:2::1"),
            InetAddress.getByName("3fff::1"),
            InetAddress.getByName("5f00::1"),
        )

        val decisions = answers.mapIndexed { index, answer ->
            val host = "special$index.example.com"
            resolver.answers[host] = listOf(answer)
            policy.validate(URI("https://$host/catalog"), URI("https://example.com"), emptySet())
        }

        assertThat(decisions).containsOnly(PolicyDecision.Rejected(PolicyRejection.NON_PUBLIC_ADDRESS))
    }

    @Test
    fun `Unicode explicit host cannot authorize legacy ASCII mapping collision`() {
        resolver.answers["fass.de"] = listOf(InetAddress.getByName("8.8.8.8"))

        val decision = policy.validate(
            URI("https://fass.de/manual.pdf"),
            URI("https://example.com"),
            setOf("faß.de"),
        )

        assertThat(decision).isEqualTo(PolicyDecision.Rejected(PolicyRejection.HOST_NOT_ALLOWED))
        assertThat(resolver.requestedHosts).isEmpty()
    }

    @Test
    fun `rejects Unicode target and supplier hostnames before DNS`() {
        val unicodeTarget = URI("https://faß.de/manual.pdf")
        val unicodeSupplier = URI("https://faß.de/")
        resolver.answers["catalog.fass.de"] = listOf(InetAddress.getByName("8.8.8.8"))

        assertThat(
            policy.validate(unicodeTarget, URI("https://example.com"), emptySet()),
        ).isEqualTo(PolicyDecision.Rejected(PolicyRejection.INVALID_HOST))
        assertThat(
            policy.validate(URI("https://catalog.fass.de"), unicodeSupplier, emptySet()),
        ).isEqualTo(PolicyDecision.Rejected(PolicyRejection.INVALID_HOST))
        assertThat(resolver.requestedHosts).isEmpty()
    }

    @Test
    fun `accepts exact prevalidated ASCII A-label explicit host`() {
        resolver.answers["xn--fa-hia.de"] = listOf(InetAddress.getByName("8.8.8.8"))

        val decision = policy.validate(
            URI("https://xn--fa-hia.de/manual.pdf"),
            URI("https://example.com"),
            setOf("XN--FA-HIA.DE"),
        )

        assertThat(decision).isInstanceOf(PolicyDecision.Allowed::class.java)
    }

    @Test
    fun `rejects unsupported scheme before DNS resolution`() {
        val decision = policy.validate(
            URI("ftp://example.com/catalog"),
            URI("https://example.com"),
            emptySet(),
        )

        assertThat(decision).isEqualTo(
            PolicyDecision.Rejected(PolicyRejection.UNSUPPORTED_SCHEME),
        )
        assertThat(resolver.requestedHosts).isEmpty()
    }

    @Test
    fun `rejects invalid network port before DNS resolution`() {
        val decision = policy.validate(
            URI("https://example.com:70000/catalog"),
            URI("https://example.com"),
            emptySet(),
        )

        assertThat(decision).isEqualTo(
            PolicyDecision.Rejected(PolicyRejection.INVALID_HOST),
        )
        assertThat(resolver.requestedHosts).isEmpty()
    }

    @Test
    fun `rejects missing and failed DNS answers`() {
        resolver.answers["empty.example.com"] = emptyList()
        resolver.failures += "failed.example.com"

        assertThat(
            policy.validate(
                URI("https://empty.example.com/catalog"),
                URI("https://example.com"),
                emptySet(),
            ),
        ).isEqualTo(PolicyDecision.Rejected(PolicyRejection.DNS_RESOLUTION_FAILED))
        assertThat(
            policy.validate(
                URI("https://failed.example.com/catalog"),
                URI("https://example.com"),
                emptySet(),
            ),
        ).isEqualTo(PolicyDecision.Rejected(PolicyRejection.DNS_RESOLUTION_FAILED))
    }

    private class FakeDnsResolver : DnsResolver {
        val answers = mutableMapOf<String, List<InetAddress>>()
        val failures = mutableSetOf<String>()
        val requestedHosts = mutableListOf<String>()

        override fun resolve(host: String): List<InetAddress> {
            requestedHosts += host
            if (host in failures) throw UnknownHostException(host)
            return answers[host].orEmpty()
        }
    }

    private fun mappedIpv6(a: Int, b: Int, c: Int, d: Int): Inet6Address {
        val bytes = ByteArray(16)
        bytes[10] = 0xff.toByte()
        bytes[11] = 0xff.toByte()
        bytes[12] = a.toByte()
        bytes[13] = b.toByte()
        bytes[14] = c.toByte()
        bytes[15] = d.toByte()
        return Inet6Address.getByAddress(null, bytes, -1)
    }
}
