package com.rfp.service.crawl

import com.rfp.domain.CrawlProductObservation
import org.springframework.stereotype.Service
import java.security.MessageDigest

/**
 * Computes a normalized identity key for a crawled product observation.
 *
 * Key format:
 * - MPN-based:  `"mpn:<UPPERCASED-MPN-WITHOUT-HYPHENS-SPACES-DOTS>"`
 * - Fallback:   `"fallback:<sha256(normalizedName|className|sourceUrl)>"`
 *
 * The key does **not** encode the supplier ID; supplier scoping is enforced at the
 * product-lookup level (querying `WHERE supplier_id = X AND identity_key = Y`).
 * This means two suppliers with the same MPN will produce the same key string but
 * resolve to different [com.rfp.domain.Product] rows.
 */
@Service
class ProductIdentityService {

    /**
     * Compute the identity key from a stored [CrawlProductObservation].
     * The [supplierId] parameter is accepted for interface consistency but is not
     * encoded in the returned key string.
     */
    fun identity(supplierId: Long, obs: CrawlProductObservation): String =
        identity(obs.mpn, obs.productName, obs.productClassName, obs.sourceUrl)

    /**
     * Core computation — exposed directly so callers that already hold the raw fields
     * (e.g. the reconciler building a back-fill key) can skip constructing an entity.
     *
     * @param mpn            raw manufacturer part number; blank/null triggers fallback
     * @param productName    product name used in fallback fingerprint
     * @param productClassName optional product class (included in fallback fingerprint)
     * @param sourceUrl      canonical URL of the page where the product was observed;
     *                       included in fallback fingerprint to disambiguate same-named
     *                       products across different pages
     */
    fun identity(
        mpn: String?,
        productName: String,
        productClassName: String?,
        sourceUrl: String,
    ): String {
        val normalizedMpn = mpn?.replace(MPN_STRIP_REGEX, "")?.uppercase()
        if (!normalizedMpn.isNullOrBlank()) {
            return "mpn:$normalizedMpn"
        }
        val normalizedName = productName.trim().lowercase()
        val fingerprint = "$normalizedName|${productClassName.orEmpty()}|$sourceUrl"
        return "fallback:${sha256(fingerprint)}"
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Characters stripped from MPN before normalization: hyphens, spaces, dots. */
        private val MPN_STRIP_REGEX = Regex("[\\-\\s.]")
    }
}
