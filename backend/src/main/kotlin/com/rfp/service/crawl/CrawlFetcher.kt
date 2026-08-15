package com.rfp.service.crawl

import okhttp3.OkHttpClient
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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
    TIMEOUT,
}

enum class FetchMethod { HTTP, PLAYWRIGHT }

data class StoredFetchContent(
    val effectiveUrl: URI,
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
    val maximumDuration: Duration = Duration.ofSeconds(20),
    val previous: StoredFetchContent? = null,
    /** Minimum milliseconds between successive requests to the same host (0 = no throttle). */
    val throttleMs: Long = 0L,
    /** Maximum concurrent requests to the same host (0 = unlimited). */
    val maxConcurrency: Int = 0,
) {
    val scope: CrawlScope get() = CrawlScope(supplierRoot, explicitHosts)
}

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

    data class Rejected(
        val error: FetchError,
        val retryable: Boolean = false,
        val retryAfter: Duration? = null,
    ) : FetchResult
}

class CrawlFetcher(
    client: OkHttpClient,
    private val crawlPolicy: DestinationValidator,
    private val canonicalizer: UrlCanonicalizer,
    private val robotsPolicy: RobotsPolicyService,
    callTimeout: Duration = Duration.ofSeconds(20),
    private val nanoTimeSource: NanoTimeSource = SystemNanoTimeSource,
) {
    private val transport = ValidatedHttpTransport(client, crawlPolicy, callTimeout)

    /** Nanoseconds (from [nanoTimeSource]) at the last completed fetch per host. */
    private val lastFetchNanoByHost = ConcurrentHashMap<String, Long>()

    /** Per-host semaphore to cap concurrent in-flight requests. Created lazily on first use. */
    private val hostSemaphores = ConcurrentHashMap<String, Semaphore>()

    fun fetch(request: CrawlFetchRequest): FetchResult {
        if (
            request.maxResponseBytes <= 0 ||
            request.maxRedirects < 0 ||
            request.maximumDuration.isNegative ||
            request.maximumDuration.isZero
        ) {
            return FetchResult.Rejected(FetchError.HTTP_FAILURE)
        }
        val deadline = DeadlineBudget.start(request.maximumDuration, nanoTimeSource)
        val host = request.url.host

        // Enforce per-host concurrency ceiling before entering the expensive fetch path.
        // A per-host Semaphore is created with the first observed maxConcurrency value.
        val semaphore: Semaphore? = if (host != null && request.maxConcurrency > 0) {
            hostSemaphores.computeIfAbsent(host) { Semaphore(request.maxConcurrency) }
        } else null

        if (semaphore != null) {
            val waitMs = deadline.remaining().toMillis().coerceAtLeast(0)
            if (!semaphore.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
                return timeoutRejection()
            }
        }

        try {
            // Enforce per-host throttle: sleep for the remaining gap since the last fetch.
            if (host != null && request.throttleMs > 0) {
                val lastNano = lastFetchNanoByHost[host]
                if (lastNano != null) {
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(nanoTimeSource.nanoTime() - lastNano)
                    val sleepMs = request.throttleMs - elapsedMs
                    if (sleepMs > 0) {
                        Thread.sleep(minOf(sleepMs, deadline.remaining().toMillis().coerceAtLeast(0)))
                        if (deadline.isExpired()) return timeoutRejection()
                    }
                }
            }

            var current = canonicalizer.resolveAndNormalize(request.url, request.url.toString())
                ?: return FetchResult.Rejected(FetchError.POLICY_REJECTED)
        val visited = mutableSetOf<URI>()
        var redirects = 0
        while (true) {
            if (deadline.isExpired()) return timeoutRejection()
            if (!visited.add(current)) return FetchResult.Rejected(FetchError.TOO_MANY_REDIRECTS)
            transport.validateDestination(current, request.scope, deadline)?.let {
                return it.error.toFetchRejection()
            }
            when (val robotsDecision = robotsPolicy.canFetch(current, request.userAgent, request.scope, deadline)) {
                RobotsDecision.Allowed -> Unit
                RobotsDecision.Disallowed -> return FetchResult.Rejected(FetchError.ROBOTS_DISALLOWED)
                is RobotsDecision.Unavailable -> return FetchResult.Rejected(
                    FetchError.ROBOTS_UNAVAILABLE,
                    retryable = robotsDecision.retryable,
                    retryAfter = robotsDecision.retryAfter,
                )
            }
            if (deadline.isExpired()) return timeoutRejection()

            val sameStoredIdentity = request.previous?.effectiveUrl == current
            val headers = buildMap {
                if (sameStoredIdentity) {
                    request.previous?.etag?.let { put("If-None-Match", it) }
                    request.previous?.lastModified?.let { put("If-Modified-Since", it) }
                }
            }
            when (val response = transport.execute(
                TransportRequest(
                    current,
                    request.scope,
                    request.userAgent,
                    request.maxResponseBytes,
                    headers,
                    deadline,
                ),
            )) {
                is TransportResult.Failure -> return response.error.toFetchRejection()
                is TransportResult.Success -> {
                    if (response.status in REDIRECT_STATUSES) {
                        if (redirects >= request.maxRedirects) {
                            return FetchResult.Rejected(FetchError.TOO_MANY_REDIRECTS)
                        }
                        val location = response.header("Location")
                            ?: return FetchResult.Rejected(FetchError.INVALID_REDIRECT)
                        current = canonicalizer.resolveAndNormalize(current, location)
                            ?: return FetchResult.Rejected(FetchError.INVALID_REDIRECT)
                        redirects++
                        continue
                    }
                    if (response.status == 304) {
                        return reuseStored(current, response, request.previous, request.maxResponseBytes, deadline)
                    }
                    if (response.status !in 200..299) {
                        val retryable = response.status == 408 || response.status == 429 || response.status in 500..599
                        return FetchResult.Rejected(
                            FetchError.HTTP_FAILURE,
                            retryable = retryable,
                            retryAfter = if (retryable) parseRetryAfter(response.header("Retry-After")) else null,
                        )
                    }
                    val contentType = response.contentType
                        ?: return FetchResult.Rejected(FetchError.UNSUPPORTED_CONTENT_TYPE)
                    if (!isAcceptedCrawlContentType(contentType)) {
                        return FetchResult.Rejected(FetchError.UNSUPPORTED_CONTENT_TYPE)
                    }
                    val contentHash = sha256(response.body)
                    if (deadline.isExpired()) return timeoutRejection()
                    return FetchResult.Success(
                        url = current,
                        status = response.status,
                        contentType = contentType,
                        body = response.body,
                        etag = response.header("ETag"),
                        lastModified = response.header("Last-Modified"),
                        method = FetchMethod.HTTP,
                        contentHash = contentHash,
                    )
                }
            }
        }
        } finally {
            semaphore?.release()
            if (host != null && request.throttleMs > 0) {
                lastFetchNanoByHost[host] = nanoTimeSource.nanoTime()
            }
        }
    }

    private fun reuseStored(
        uri: URI,
        response: TransportResult.Success,
        previous: StoredFetchContent?,
        maxResponseBytes: Long,
        deadline: DeadlineBudget,
    ): FetchResult {
        if (deadline.isExpired()) return timeoutRejection()
        if (previous == null || previous.effectiveUrl != uri) {
            return FetchResult.Rejected(FetchError.HTTP_FAILURE)
        }
        if (previous.body.size.toLong() > maxResponseBytes) {
            return FetchResult.Rejected(FetchError.RESPONSE_TOO_LARGE)
        }
        val contentType = response.contentType ?: normalizedContentType(previous.contentType)
            ?: return FetchResult.Rejected(FetchError.UNSUPPORTED_CONTENT_TYPE)
        if (!isAcceptedCrawlContentType(contentType)) {
            return FetchResult.Rejected(FetchError.UNSUPPORTED_CONTENT_TYPE)
        }
        if (deadline.isExpired()) return timeoutRejection()
        return FetchResult.Success(
            url = uri,
            status = response.status,
            contentType = contentType,
            body = previous.body,
            etag = response.header("ETag") ?: previous.etag,
            lastModified = response.header("Last-Modified") ?: previous.lastModified,
            method = FetchMethod.HTTP,
            contentHash = previous.contentHash,
        )
    }

    companion object {
        private val REDIRECT_STATUSES = setOf(300, 301, 302, 303, 307, 308)
    }
}

private fun timeoutRejection() = FetchResult.Rejected(FetchError.TIMEOUT, retryable = true)

internal fun isAcceptedCrawlContentType(contentType: String): Boolean {
    val type = normalizedContentType(contentType) ?: return false
    return type.startsWith("text/") ||
        type.endsWith("+json") ||
        type.endsWith("+xml") ||
        type in setOf(
            "application/json",
            "application/xml",
            "application/xhtml+xml",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

internal fun TransportResult.Success.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun TransportError.toFetchRejection(): FetchResult.Rejected = when (this) {
    TransportError.POLICY_REJECTED -> FetchResult.Rejected(FetchError.POLICY_REJECTED)
    TransportError.RESPONSE_TOO_LARGE -> FetchResult.Rejected(FetchError.RESPONSE_TOO_LARGE)
    TransportError.TIMEOUT -> FetchResult.Rejected(FetchError.TIMEOUT, retryable = true)
    TransportError.NETWORK_FAILURE -> FetchResult.Rejected(FetchError.NETWORK_FAILURE, retryable = true)
}

internal fun parseRetryAfter(value: String?, now: Instant = Instant.now()): Duration? {
    value ?: return null
    value.trim().toLongOrNull()?.let { return Duration.ofSeconds(it.coerceAtLeast(0)) }
    return runCatching {
        val retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        Duration.between(now, retryAt).let { if (it.isNegative) Duration.ZERO else it }
    }.getOrNull()
}
