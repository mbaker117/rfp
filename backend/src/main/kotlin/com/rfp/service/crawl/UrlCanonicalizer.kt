package com.rfp.service.crawl

import com.google.common.net.InetAddresses
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class UrlCanonicalizer {
    fun resolveAndNormalize(pageUrl: URI, reference: String): URI? {
        val candidate = reference.trim()
        if (candidate.isEmpty()) return null

        val parsedReference = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (parsedReference.rawFragment != null &&
            parsedReference.scheme == null &&
            parsedReference.rawAuthority == null &&
            parsedReference.rawPath.isEmpty() &&
            parsedReference.rawQuery == null
        ) {
            return null
        }

        val resolved = runCatching { pageUrl.resolve(parsedReference).normalize() }.getOrNull() ?: return null
        val scheme = resolved.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (resolved.rawUserInfo != null) return null

        val host = normalizeAsciiHost(resolved.host) ?: return null
        if (resolved.port != -1 && resolved.port !in 1..65535) return null
        val port = when {
            scheme == "http" && resolved.port == 80 -> -1
            scheme == "https" && resolved.port == 443 -> -1
            else -> resolved.port
        }
        val authorityHost = if (host.contains(':')) "[$host]" else host
        val authority = if (port == -1) authorityHost else "$authorityHost:$port"
        val query = removeTrackingParameters(resolved.rawQuery)
        val path = resolved.rawPath.ifEmpty { "" }

        return runCatching {
            URI("$scheme://$authority$path${query?.let { "?$it" } ?: ""}")
        }.getOrNull()
    }

    private fun removeTrackingParameters(rawQuery: String?): String? {
        if (rawQuery == null) return null

        return rawQuery
            .split('&')
            .filterNot(::isTrackingParameter)
            .joinToString("&")
            .ifEmpty { null }
    }

    private fun isTrackingParameter(parameter: String): Boolean {
        val rawKey = parameter.substringBefore('=')
        val key = runCatching {
            URLDecoder.decode(rawKey, StandardCharsets.UTF_8).lowercase(Locale.ROOT)
        }.getOrElse { rawKey.lowercase(Locale.ROOT) }
        return key.startsWith("utm_") || key == "gclid" || key == "fbclid"
    }

}

internal fun normalizeAsciiHost(host: String?): String? {
    val unbracketed = host
        ?.trim()
        ?.trimEnd('.')
        ?.removeSurrounding("[", "]")
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    if (unbracketed.any { it.code > 0x7f }) return null

    if (unbracketed.contains(':')) {
        return runCatching {
            InetAddresses.toAddrString(InetAddresses.forString(unbracketed))
        }.getOrNull()
    }

    val normalized = unbracketed.lowercase(Locale.ROOT)
    if (normalized.length > 253) return null
    val labels = normalized.split('.')
    if (labels.any { label ->
            label.isEmpty() ||
                label.length > 63 ||
                label.first() == '-' ||
                label.last() == '-' ||
                label.any { character -> !character.isLetterOrDigit() && character != '-' }
        }
    ) {
        return null
    }
    return normalized
}
