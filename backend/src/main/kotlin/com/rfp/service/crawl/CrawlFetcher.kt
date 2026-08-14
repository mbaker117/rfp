package com.rfp.service.crawl

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.security.MessageDigest

enum class FetchError {
    POLICY_REJECTED,
    ROBOTS_DISALLOWED,
    ROBOTS_UNAVAILABLE,
    INVALID_REDIRECT,
    TOO_MANY_REDIRECTS,
    RESPONSE_TOO_LARGE,
    UNSUPPORTED_CONTENT_TYPE,
    HTTP_FAILURE,
    NETWORK_FAILURE,
}

enum class FetchMethod {
    HTTP,
    PLAYWRIGHT,
}

data class StoredFetchContent(
    val body: ByteArray,
    val contentType: String,
    val etag: String?,
    val lastModified: String?,
    val contentHash: String,
)

data class CrawlFetchRequest(
    val url: URI,
    val supplierRoot: URI,
    val explicitHosts: Set<String> = emptySet(),
    val userAgent: String = "rfp-crawler",
    val maxResponseBytes: Long = 5 * 1024 * 1024,
    val maxRedirects: Int = 5,
    val previous: StoredFetchContent? = null,
)

sealed interface FetchResult {
    data class Success(
        val url: URI,
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val etag: String?,
        val lastModified: String?,
        val method: FetchMethod,
        val contentHash: String,
    ) : FetchResult

    data class Rejected(val error: FetchError) : FetchResult
}

class CrawlFetcher(
    client: OkHttpClient,
    private val crawlPolicy: CrawlPolicy,
    private val canonicalizer: UrlCanonicalizer,
    private val robotsPolicy: RobotsPolicyService,
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    fun fetch(request: CrawlFetchRequest): FetchResult {
        if (request.maxResponseBytes <= 0 || request.maxRedirects < 0) {
            return FetchResult.Rejected(FetchError.HTTP_FAILURE)
        }

        var current = request.url
        var redirects = 0
        while (true) {
            if (crawlPolicy.validate(current, request.supplierRoot, request.explicitHosts) !is PolicyDecision.Allowed) {
                return FetchResult.Rejected(FetchError.POLICY_REJECTED)
            }
            when (robotsPolicy.canFetch(current, request.userAgent)) {
                RobotsDecision.Disallowed -> return FetchResult.Rejected(FetchError.ROBOTS_DISALLOWED)
                RobotsDecision.Unavailable -> return FetchResult.Rejected(FetchError.ROBOTS_UNAVAILABLE)
                RobotsDecision.Allowed -> Unit
            }

            val response = runCatching { execute(current, request) }.getOrElse {
                return FetchResult.Rejected(FetchError.NETWORK_FAILURE)
            }
            if (response.isRedirect) {
                response.use {
                    if (redirects >= request.maxRedirects) {
                        return FetchResult.Rejected(FetchError.TOO_MANY_REDIRECTS)
                    }
                    val location = it.header("Location")
                        ?: return FetchResult.Rejected(FetchError.INVALID_REDIRECT)
                    val destination = canonicalizer.resolveAndNormalize(current, location)
                        ?: return FetchResult.Rejected(FetchError.INVALID_REDIRECT)
                    if (crawlPolicy.validate(destination, request.supplierRoot, request.explicitHosts) !is PolicyDecision.Allowed) {
                        return FetchResult.Rejected(FetchError.POLICY_REJECTED)
                    }
                    current = destination
                    redirects++
                }
                continue
            }
            response.use {
                if (it.code == 304) return reuseStored(current, it, request.previous)
                if (it.code !in 200..299) return FetchResult.Rejected(FetchError.HTTP_FAILURE)

                val contentType = normalizedContentType(it.header("Content-Type"))
                    ?: return FetchResult.Rejected(FetchError.UNSUPPORTED_CONTENT_TYPE)
                if (contentType !in ACCEPTED_CONTENT_TYPES && !contentType.startsWith("text/")) {
                    return FetchResult.Rejected(FetchError.UNSUPPORTED_CONTENT_TYPE)
                }

                val responseBody = it.body ?: return FetchResult.Rejected(FetchError.HTTP_FAILURE)
                val bytes = readBounded(responseBody.source(), responseBody.contentLength(), request.maxResponseBytes)
                    ?: return FetchResult.Rejected(FetchError.RESPONSE_TOO_LARGE)
                return FetchResult.Success(
                    url = current,
                    status = it.code,
                    contentType = contentType,
                    body = bytes,
                    etag = it.header("ETag"),
                    lastModified = it.header("Last-Modified"),
                    method = FetchMethod.HTTP,
                    contentHash = sha256(bytes),
                )
            }
        }
    }

    private fun execute(uri: URI, request: CrawlFetchRequest): Response {
        val builder = Request.Builder()
            .url(uri.toString())
            .header("User-Agent", request.userAgent)
            .get()
        request.previous?.etag?.let { builder.header("If-None-Match", it) }
        request.previous?.lastModified?.let { builder.header("If-Modified-Since", it) }
        return client.newCall(builder.build()).execute()
    }

    private fun reuseStored(uri: URI, response: Response, previous: StoredFetchContent?): FetchResult {
        previous ?: return FetchResult.Rejected(FetchError.HTTP_FAILURE)
        return FetchResult.Success(
            url = uri,
            status = response.code,
            contentType = normalizedContentType(response.header("Content-Type")) ?: previous.contentType,
            body = previous.body,
            etag = response.header("ETag") ?: previous.etag,
            lastModified = response.header("Last-Modified") ?: previous.lastModified,
            method = FetchMethod.HTTP,
            contentHash = previous.contentHash,
        )
    }

    companion object {
        private val ACCEPTED_CONTENT_TYPES = setOf(
            "application/json",
            "application/ld+json",
            "application/xml",
            "application/xhtml+xml",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )
    }
}

internal fun normalizedContentType(header: String?): String? = header
    ?.substringBefore(';')
    ?.trim()
    ?.lowercase()
    ?.takeIf { it.isNotEmpty() }

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
