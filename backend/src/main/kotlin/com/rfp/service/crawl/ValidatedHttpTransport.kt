package com.rfp.service.crawl

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CrawlScope(
    val supplierRoot: URI,
    val explicitHosts: Set<String>,
)

data class TransportRequest(
    val uri: URI,
    val scope: CrawlScope,
    val userAgent: String,
    val maxResponseBytes: Long,
    val headers: Map<String, String> = emptyMap(),
)

enum class TransportError {
    POLICY_REJECTED,
    RESPONSE_TOO_LARGE,
    TIMEOUT,
    NETWORK_FAILURE,
}

sealed interface TransportResult {
    data class Success(
        val status: Int,
        val contentType: String?,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) : TransportResult

    data class Failure(val error: TransportError) : TransportResult
}

/**
 * Executes exactly one HTTP hop. The URI hostname remains unchanged for Host/SNI, while OkHttp's
 * resolver is replaced with the precise address set returned by CrawlPolicy for this hop.
 */
class ValidatedHttpTransport(
    private val baseClient: OkHttpClient,
    private val destinationValidator: DestinationValidator,
    private val callTimeout: Duration = Duration.ofSeconds(20),
) {
    init {
        require(!callTimeout.isNegative && !callTimeout.isZero) { "callTimeout must be positive" }
    }

    fun execute(request: TransportRequest): TransportResult {
        if (request.maxResponseBytes <= 0) return TransportResult.Failure(TransportError.RESPONSE_TOO_LARGE)
        val decision = destinationValidator.validate(
            request.uri,
            request.scope.supplierRoot,
            request.scope.explicitHosts,
        )
        if (decision !is PolicyDecision.Allowed) {
            return TransportResult.Failure(TransportError.POLICY_REJECTED)
        }

        val expectedHost = normalizeAsciiHost(request.uri.host)
            ?: return TransportResult.Failure(TransportError.POLICY_REJECTED)
        val pinnedAddresses = decision.resolved.toList()
        if (pinnedAddresses.isEmpty()) return TransportResult.Failure(TransportError.POLICY_REJECTED)
        val pinnedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (normalizeAsciiHost(hostname) != expectedHost) {
                    throw java.net.UnknownHostException("Unvalidated hostname: $hostname")
                }
                return pinnedAddresses
            }
        }
        val pool = ConnectionPool(0, 1, TimeUnit.MILLISECONDS)
        val client = baseClient.newBuilder()
            .dns(pinnedDns)
            .proxy(Proxy.NO_PROXY)
            .connectionPool(pool)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(callTimeout)
            .build()
        val httpRequest = Request.Builder()
            .url(request.uri.toString())
            .header("User-Agent", request.userAgent.ifBlank { "rfp-crawler" })
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()

        return try {
            client.newCall(httpRequest).execute().use { response ->
                val body = response.body
                val bytes = if (body == null || response.code == 304 || response.isRedirect) {
                    byteArrayOf()
                } else {
                    readBounded(body.source(), body.contentLength(), request.maxResponseBytes)
                        ?: return TransportResult.Failure(TransportError.RESPONSE_TOO_LARGE)
                }
                TransportResult.Success(
                    status = response.code,
                    contentType = normalizedContentType(response.header("Content-Type")),
                    headers = response.headers.names().associateWith { response.header(it).orEmpty() },
                    body = bytes,
                )
            }
        } catch (_: InterruptedIOException) {
            TransportResult.Failure(TransportError.TIMEOUT)
        } catch (_: Exception) {
            TransportResult.Failure(TransportError.NETWORK_FAILURE)
        } finally {
            pool.evictAll()
        }
    }
}

internal fun normalizedContentType(header: String?): String? = header
    ?.substringBefore(';')
    ?.trim()
    ?.lowercase(Locale.ROOT)
    ?.takeIf { it.isNotEmpty() }

internal fun originOf(uri: URI): URI? {
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
