package com.rfp.service.crawl

import crawlercommons.robots.SimpleRobotRules
import crawlercommons.robots.SimpleRobotRulesParser
import okhttp3.OkHttpClient
import okio.BufferedSource
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

sealed interface RobotsDecision {
    data object Allowed : RobotsDecision
    data object Disallowed : RobotsDecision
    data class Unavailable(
        val retryable: Boolean,
        val retryAfter: Duration?,
    ) : RobotsDecision
}

fun interface RetrySleeper {
    fun sleep(duration: Duration)
}

class RobotsPolicyService(
    client: OkHttpClient,
    destinationValidator: DestinationValidator = CrawlPolicy(),
    private val canonicalizer: UrlCanonicalizer = UrlCanonicalizer(),
    private val cacheTtl: Duration = Duration.ofHours(1),
    private val negativeCacheTtl: Duration = Duration.ofSeconds(15),
    private val maxAttempts: Int = 2,
    private val maxRedirects: Int = 3,
    private val maxRobotsBytes: Long = 512 * 1024,
    private val baseBackoff: Duration = Duration.ofMillis(100),
    private val maxBackoff: Duration = Duration.ofSeconds(2),
    private val clock: Clock = Clock.systemUTC(),
    private val sleeper: RetrySleeper = RetrySleeper { Thread.sleep(it.toMillis()) },
    private val jitterMillis: (Long) -> Long = { bound -> if (bound <= 0) 0 else kotlin.random.Random.nextLong(bound + 1) },
) {
    private val transport = ValidatedHttpTransport(client, destinationValidator)
    private val cache = ConcurrentHashMap<CacheKey, CachedValue>()
    private val originLocks = ConcurrentHashMap<CacheKey, Any>()

    init {
        require(!cacheTtl.isNegative && !cacheTtl.isZero) { "cacheTtl must be positive" }
        require(!negativeCacheTtl.isNegative && !negativeCacheTtl.isZero) { "negativeCacheTtl must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(maxRedirects >= 0) { "maxRedirects must not be negative" }
        require(maxRobotsBytes > 0) { "maxRobotsBytes must be positive" }
    }

    fun canFetch(uri: URI, userAgent: String): RobotsDecision {
        val origin = originOf(uri) ?: return unavailable(retryable = false)
        return canFetch(uri, userAgent, CrawlScope(origin, emptySet()))
    }

    fun canFetch(uri: URI, userAgent: String, scope: CrawlScope): RobotsDecision {
        val requestingOrigin = originOf(uri) ?: return unavailable(retryable = false)
        val key = CacheKey(
            requestingOrigin = requestingOrigin,
            supplierRoot = scope.supplierRoot,
            explicitHosts = scope.explicitHosts.map { it.lowercase(Locale.ROOT) }.sorted(),
        )
        val now = clock.instant()
        cache[key]?.takeIf { now.isBefore(it.expiresAt) }?.let { return it.value.decisionFor(uri, userAgent) }

        val lock = originLocks.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            val refreshedNow = clock.instant()
            cache[key]?.takeIf { refreshedNow.isBefore(it.expiresAt) }
                ?.let { return@synchronized it.value.decisionFor(uri, userAgent) }

            val value = retrievePolicy(requestingOrigin, scope, userAgent)
            val ttl = if (value is CachedPolicy) cacheTtl else negativeCacheTtl
            cache[key] = CachedValue(value, clock.instant().plus(ttl))
            value.decisionFor(uri, userAgent)
        }
    }

    private fun retrievePolicy(origin: URI, scope: CrawlScope, userAgent: String): CacheableRobotsValue {
        var preservedRetryAfter: Duration? = null
        repeat(maxAttempts) { attempt ->
            when (val result = retrieveAttempt(origin.resolve("/robots.txt"), scope, userAgent)) {
                is RetrievalResult.Policy -> return CachedPolicy(result.policy)
                is RetrievalResult.TerminalFailure -> return CachedFailure(unavailable(false, result.retryAfter))
                is RetrievalResult.RetryableFailure -> {
                    preservedRetryAfter = maxDuration(preservedRetryAfter, result.retryAfter)
                    if (attempt + 1 < maxAttempts) {
                        sleeper.sleep(backoffFor(attempt, result.retryAfter))
                    }
                }
            }
        }
        return CachedFailure(unavailable(true, preservedRetryAfter))
    }

    private fun retrieveAttempt(initialUri: URI, scope: CrawlScope, userAgent: String): RetrievalResult {
        var current = initialUri
        val visited = mutableSetOf<URI>()
        for (redirectCount in 0..maxRedirects) {
            if (!visited.add(current)) return RetrievalResult.TerminalFailure(null)
            when (val response = transport.execute(
                TransportRequest(current, scope, userAgent, maxRobotsBytes),
            )) {
                is TransportResult.Failure -> return when (response.error) {
                    TransportError.POLICY_REJECTED -> RetrievalResult.TerminalFailure(null)
                    TransportError.RESPONSE_TOO_LARGE -> RetrievalResult.TerminalFailure(null)
                    TransportError.TIMEOUT, TransportError.NETWORK_FAILURE -> RetrievalResult.RetryableFailure(null)
                }

                is TransportResult.Success -> {
                    if (response.status in REDIRECT_STATUSES) {
                        if (redirectCount >= maxRedirects) return RetrievalResult.TerminalFailure(null)
                        val location = response.header("Location")
                            ?: return RetrievalResult.TerminalFailure(null)
                        current = canonicalizer.resolveAndNormalize(current, location)
                            ?: return RetrievalResult.TerminalFailure(null)
                        continue
                    }
                    if (response.status in 200..299) {
                        return RetrievalResult.Policy(
                            RobotsDocument(current, response.body, response.contentType ?: "text/plain"),
                        )
                    }
                    val retryAfter = parseRetryAfter(response.header("Retry-After"))
                    if (response.status == 408 || response.status == 429 || response.status in 500..599) {
                        return RetrievalResult.RetryableFailure(retryAfter)
                    }
                    if (response.status in 400..499) {
                        return RetrievalResult.Policy(FailedFetchPolicy(response.status))
                    }
                    return RetrievalResult.TerminalFailure(retryAfter)
                }
            }
        }
        return RetrievalResult.TerminalFailure(null)
    }

    private fun backoffFor(attempt: Int, retryAfter: Duration?): Duration {
        if (retryAfter != null) return minDuration(retryAfter, maxBackoff)
        val multiplier = 1L shl attempt.coerceAtMost(20)
        val baseMillis = (baseBackoff.toMillis() * multiplier).coerceAtMost(maxBackoff.toMillis())
        val jitter = jitterMillis((baseMillis / 2).coerceAtLeast(0))
        return Duration.ofMillis((baseMillis + jitter).coerceAtMost(maxBackoff.toMillis()))
    }

    private fun parseRetryAfter(value: String?): Duration? {
        value ?: return null
        value.trim().toLongOrNull()?.let { return Duration.ofSeconds(it.coerceAtLeast(0)) }
        return runCatching {
            val retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            Duration.between(clock.instant(), retryAt).coerceAtLeast(Duration.ZERO)
        }.getOrNull()
    }

    private sealed interface CacheableRobotsValue {
        fun decisionFor(uri: URI, userAgent: String): RobotsDecision
    }

    private data class CachedPolicy(val policy: RobotsRulesSource) : CacheableRobotsValue {
        override fun decisionFor(uri: URI, userAgent: String): RobotsDecision {
            val token = userAgent.trim().substringBefore('/').substringBefore(' ').lowercase(Locale.ROOT)
                .ifBlank { "rfp-crawler" }
            return if (policy.rulesFor(token).isAllowed(uri.toString())) {
                RobotsDecision.Allowed
            } else {
                RobotsDecision.Disallowed
            }
        }
    }

    private data class CachedFailure(val decision: RobotsDecision.Unavailable) : CacheableRobotsValue {
        override fun decisionFor(uri: URI, userAgent: String): RobotsDecision = decision
    }

    private sealed interface RobotsRulesSource {
        fun rulesFor(productToken: String): SimpleRobotRules
    }

    private data class RobotsDocument(
        val uri: URI,
        val bytes: ByteArray,
        val contentType: String,
    ) : RobotsRulesSource {
        override fun rulesFor(productToken: String): SimpleRobotRules = SimpleRobotRulesParser().parseContent(
            uri.toString(), bytes, contentType, listOf(productToken),
        )
    }

    private data class FailedFetchPolicy(val status: Int) : RobotsRulesSource {
        override fun rulesFor(productToken: String): SimpleRobotRules = SimpleRobotRulesParser().failedFetch(status)
    }

    private sealed interface RetrievalResult {
        data class Policy(val policy: RobotsRulesSource) : RetrievalResult
        data class RetryableFailure(val retryAfter: Duration?) : RetrievalResult
        data class TerminalFailure(val retryAfter: Duration?) : RetrievalResult
    }

    private data class CacheKey(
        val requestingOrigin: URI,
        val supplierRoot: URI,
        val explicitHosts: List<String>,
    )

    private data class CachedValue(val value: CacheableRobotsValue, val expiresAt: Instant)

    companion object {
        private val REDIRECT_STATUSES = setOf(300, 301, 302, 303, 307, 308)

        private fun unavailable(retryable: Boolean, retryAfter: Duration? = null) =
            RobotsDecision.Unavailable(retryable, retryAfter)

        private fun minDuration(first: Duration, second: Duration): Duration = if (first <= second) first else second
        private fun maxDuration(first: Duration?, second: Duration?): Duration? = when {
            first == null -> second
            second == null -> first
            first >= second -> first
            else -> second
        }
    }
}

internal fun readBounded(source: BufferedSource, declaredLength: Long, maxBytes: Long): ByteArray? {
    if (declaredLength > maxBytes) return null
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192L).toInt())
    val chunk = ByteArray(8192)
    var total = 0L
    while (true) {
        val remainingWithSentinel = maxBytes - total + 1
        val read = source.read(chunk, 0, minOf(chunk.size.toLong(), remainingWithSentinel).toInt())
        if (read == -1) break
        total += read
        if (total > maxBytes) return null
        output.write(chunk, 0, read)
    }
    return output.toByteArray()
}

private fun Duration.coerceAtLeast(minimum: Duration): Duration = if (this < minimum) minimum else this
