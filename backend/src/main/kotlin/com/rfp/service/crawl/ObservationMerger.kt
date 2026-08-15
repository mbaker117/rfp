package com.rfp.service.crawl

import com.rfp.domain.CrawlProductObservation
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

/**
 * The winning field values after merging a group of [CrawlProductObservation]s that share the
 * same identity key.
 */
data class MergedProduct(
    val identityKey: String,
    val productName: String,
    val mpn: String?,
    val productClassName: String?,
    val attributesJson: String,
    val price: BigDecimal?,
    val currency: String?,
    val sourceUrl: String,
    val extractionMethod: String,
    val confidence: Int?,
    val fieldProvenanceJson: String,
    val observedAt: Instant,
)

/**
 * Merges multiple [CrawlProductObservation]s for the same identity key into a single
 * [MergedProduct] by applying the following precedence rules (highest-priority wins):
 *
 * 1. **Extraction method rank**: `JSON_LD > API > PRODUCT_PAGE > LISTING_PAGE > MANUFACTURER_MANUAL`
 * 2. On tie: higher **confidence** wins
 * 3. On tie: later **observedAt** wins
 *
 * A newer, low-confidence observation does NOT overwrite an older, high-confidence one unless
 * its method+confidence combination actually wins.
 */
@Service
class ObservationMerger {

    /**
     * Merge a non-empty list of observations that share the same identity key.
     *
     * @throws IllegalArgumentException if [observations] is empty
     */
    fun merge(observations: List<CrawlProductObservation>): MergedProduct {
        require(observations.isNotEmpty()) { "Cannot merge an empty observations list" }

        val winner = observations.maxWithOrNull(OBSERVATION_COMPARATOR)!!

        return MergedProduct(
            identityKey = winner.identityKey,
            productName = winner.productName,
            mpn = winner.mpn,
            productClassName = winner.productClassName,
            attributesJson = winner.attributesJson,
            price = winner.price,
            currency = winner.currency,
            sourceUrl = winner.sourceUrl,
            extractionMethod = winner.extractionMethod,
            confidence = winner.confidence,
            fieldProvenanceJson = winner.fieldProvenanceJson,
            observedAt = winner.observedAt,
        )
    }

    companion object {
        /**
         * Extraction method ranks — higher value = higher authority.
         *
         * | ExtractionMethod        | Rank |
         * |-------------------------|------|
         * | JSON_LD                 | 5    |
         * | API                     | 4    |
         * | PRODUCT_PAGE            | 3    |
         * | LISTING_PAGE            | 2    |
         * | MANUFACTURER_MANUAL     | 1    |
         * | (unknown / future)      | 0    |
         */
        private val METHOD_RANK: Map<String, Int> = mapOf(
            "JSON_LD" to 5,
            "API" to 4,
            "PRODUCT_PAGE" to 3,
            "LISTING_PAGE" to 2,
            "MANUFACTURER_MANUAL" to 1,
        )

        private val OBSERVATION_COMPARATOR: Comparator<CrawlProductObservation> =
            compareBy(
                { METHOD_RANK[it.extractionMethod] ?: 0 },
                { it.confidence ?: 0 },
                { it.observedAt },
            )
    }
}
