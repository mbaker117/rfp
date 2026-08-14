package com.rfp.service.crawl

import crawlercommons.robots.SimpleRobotRules
import crawlercommons.robots.SimpleRobotRulesParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class RobotsDecision {
    Allowed,
    Disallowed,
    Unavailable,
}

class RobotsPolicyService(
    client: OkHttpClient,
    private val cacheTtl: Duration = Duration.ofHours(1),
    private val maxAttempts: Int = 2,
    private val maxRobotsBytes: Long = 512 * 1024,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val cache = ConcurrentHashMap<URI, CachedRules>()

    init {
        require(!cacheTtl.isNegative && !cacheTtl.isZero) { "cacheTtl must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(maxRobotsBytes > 0) { "maxRobotsBytes must be positive" }
    }

    fun canFetch(uri: URI, userAgent: String): RobotsDecision {
        val origin = originOf(uri) ?: return RobotsDecision.Unavailable
        val now = clock.instant()
        val cached = cache[origin]
        val policy = if (cached != null && now.isBefore(cached.expiresAt)) {
            cached.policy
        } else {
            synchronized(cache) {
                val refreshed = cache[origin]
                if (refreshed != null && now.isBefore(refreshed.expiresAt)) {
                    refreshed.policy
                } else {
                    retrieveRules(origin, userAgent)?.also {
                        cache[origin] = CachedRules(it, now.plus(cacheTtl))
                    }
                }
            } ?: return RobotsDecision.Unavailable
        }
        val rules = policy.rulesFor(productToken(userAgent))

        return if (rules.isAllowed(uri.toString())) RobotsDecision.Allowed else RobotsDecision.Disallowed
    }

    private fun retrieveRules(origin: URI, userAgent: String): CachedPolicy? {
        val robotsUri = origin.resolve("/robots.txt")
        repeat(maxAttempts) {
            val policy = runCatching {
                client.newCall(
                    Request.Builder()
                        .url(robotsUri.toString())
                        .header("User-Agent", userAgent)
                        .get()
                        .build(),
                ).execute().use { response ->
                    when {
                        response.code in 200..299 -> {
                            val body = response.body ?: return@use null
                            val bytes = readBounded(body.source(), body.contentLength(), maxRobotsBytes)
                                ?: return@use null
                            RobotsDocument(
                                uri = robotsUri,
                                bytes = bytes,
                                contentType = response.header("Content-Type") ?: "text/plain",
                            )
                        }

                        response.code in 400..499 -> FailedFetchPolicy(response.code)
                        else -> null
                    }
                }
            }.getOrNull()
            if (policy != null) return policy
        }
        return null
    }

    private fun originOf(uri: URI): URI? {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: return null
        val host = normalizeAsciiHost(uri.host) ?: return null
        val port = when {
            uri.port == -1 -> -1
            scheme == "http" && uri.port == 80 -> -1
            scheme == "https" && uri.port == 443 -> -1
            uri.port in 1..65535 -> uri.port
            else -> return null
        }
        return URI(scheme, null, host, port, null, null, null)
    }

    private fun productToken(userAgent: String): String = userAgent
        .trim()
        .substringBefore('/')
        .substringBefore(' ')
        .lowercase(Locale.ROOT)
        .ifBlank { "rfp-crawler" }

    private data class CachedRules(
        val policy: CachedPolicy,
        val expiresAt: Instant,
    )

    private sealed interface CachedPolicy {
        fun rulesFor(productToken: String): SimpleRobotRules
    }

    private data class RobotsDocument(
        val uri: URI,
        val bytes: ByteArray,
        val contentType: String,
    ) : CachedPolicy {
        override fun rulesFor(productToken: String): SimpleRobotRules = SimpleRobotRulesParser().parseContent(
            uri.toString(),
            bytes,
            contentType,
            listOf(productToken),
        )
    }

    private data class FailedFetchPolicy(val status: Int) : CachedPolicy {
        override fun rulesFor(productToken: String): SimpleRobotRules = SimpleRobotRulesParser().failedFetch(status)
    }
}

internal fun readBounded(
    source: okio.BufferedSource,
    declaredLength: Long,
    maxBytes: Long,
): ByteArray? {
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
