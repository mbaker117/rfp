package com.rfp.service.crawl

import com.google.common.net.InternetDomainName
import com.google.common.net.InetAddresses
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.Locale

fun interface DnsResolver {
    fun resolve(host: String): List<InetAddress>
}

object SystemDnsResolver : DnsResolver {
    override fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}

enum class PolicyRejection {
    UNSUPPORTED_SCHEME,
    INVALID_HOST,
    HOST_NOT_ALLOWED,
    DNS_RESOLUTION_FAILED,
    NON_PUBLIC_ADDRESS,
}

sealed interface PolicyDecision {
    data class Allowed(val resolved: List<InetAddress>) : PolicyDecision

    data class Rejected(val reason: PolicyRejection) : PolicyDecision
}

class CrawlPolicy(
    private val dnsResolver: DnsResolver = SystemDnsResolver,
) {
    fun validate(
        uri: URI,
        supplierRoot: URI,
        explicitHosts: Set<String>,
    ): PolicyDecision {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            return PolicyDecision.Rejected(PolicyRejection.UNSUPPORTED_SCHEME)
        }
        if (uri.rawUserInfo != null) {
            return PolicyDecision.Rejected(PolicyRejection.INVALID_HOST)
        }
        if (uri.port != -1 && uri.port !in 1..65535) {
            return PolicyDecision.Rejected(PolicyRejection.INVALID_HOST)
        }

        val host = normalizeHost(uri.host)
            ?: return PolicyDecision.Rejected(PolicyRejection.INVALID_HOST)
        val supplierHost = normalizeHost(supplierRoot.host)
            ?: return PolicyDecision.Rejected(PolicyRejection.INVALID_HOST)
        val normalizedExplicitHosts = explicitHosts.mapNotNull(::normalizeHost).toSet()

        if (!isAutomaticallyAllowed(host, supplierHost) && host !in normalizedExplicitHosts) {
            return PolicyDecision.Rejected(PolicyRejection.HOST_NOT_ALLOWED)
        }

        val resolved = runCatching { dnsResolver.resolve(host).toList() }.getOrNull()
            ?: return PolicyDecision.Rejected(PolicyRejection.DNS_RESOLUTION_FAILED)
        if (resolved.isEmpty()) {
            return PolicyDecision.Rejected(PolicyRejection.DNS_RESOLUTION_FAILED)
        }
        if (resolved.any { !isPublicDestination(it) }) {
            return PolicyDecision.Rejected(PolicyRejection.NON_PUBLIC_ADDRESS)
        }

        return PolicyDecision.Allowed(resolved)
    }

    private fun isAutomaticallyAllowed(host: String, supplierHost: String): Boolean {
        val supplierDomain = registrableDomain(supplierHost) ?: return host == supplierHost
        return host == supplierDomain || host.endsWith(".$supplierDomain")
    }

    private fun registrableDomain(host: String): String? = runCatching {
        InternetDomainName.from(host).topPrivateDomain().toString()
    }.getOrNull()

    private fun normalizeHost(host: String?): String? {
        val withoutRootDot = host
            ?.trim()
            ?.trimEnd('.')
            ?.removeSurrounding("[", "]")
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (withoutRootDot.contains(':')) {
            return runCatching {
                InetAddresses.toAddrString(InetAddresses.forString(withoutRootDot))
            }.getOrNull()
        }
        return runCatching {
            IDN.toASCII(withoutRootDot, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        }.getOrNull()
    }

    private fun isPublicDestination(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }

        val bytes = address.address.map(Byte::toInt).map { it and 0xff }
        return when (bytes.size) {
            4 -> isPublicIpv4(bytes)
            16 -> isPublicIpv6(bytes)
            else -> false
        }
    }

    private fun isPublicIpv4(bytes: List<Int>): Boolean {
        val first = bytes[0]
        val second = bytes[1]
        return when {
            first == 0 -> false
            first == 10 -> false
            first == 100 && second in 64..127 -> false
            first == 127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 0 && bytes[2] == 0 -> false
            first == 192 && second == 0 && bytes[2] == 2 -> false
            first == 192 && second == 88 && bytes[2] == 99 -> false
            first == 192 && second == 168 -> false
            first == 198 && second in 18..19 -> false
            first == 198 && second == 51 && bytes[2] == 100 -> false
            first == 203 && second == 0 && bytes[2] == 113 -> false
            first >= 224 -> false
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: List<Int>): Boolean {
        if (bytes[0] and 0xfe == 0xfc) return false
        if (bytes.take(12).all { it == 0 }) return false
        if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) return false
        return true
    }
}
