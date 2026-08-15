package com.rfp.dto

import com.rfp.domain.CrawlPageType
import java.math.BigDecimal
import java.net.URI
import java.time.Instant

data class PageClassification(
    val type: CrawlPageType,
    val priority: Int,
    val shouldCrawl: Boolean,
    val partitionKey: String,
    val confidence: Int,
)

enum class ExtractionMethod {
    API,
    JSON_LD,
    PRODUCT_PAGE,
    MANUFACTURER_MANUAL,
    LISTING_PAGE,
}

data class FieldSource(
    val sourceUrl: URI,
    val method: ExtractionMethod,
    val page: Int? = null,
    val sheet: String? = null,
    val section: String? = null,
)

data class ExtractedObservation(
    val identityHint: String,
    val name: String,
    val mpn: String?,
    val className: String?,
    val attributes: Map<String, Any?>,
    val price: BigDecimal?,
    val currency: String?,
    val priceSourceUrl: URI?,
    val sourceUrl: URI,
    val method: ExtractionMethod,
    val confidence: Int,
    val fieldSources: Map<String, FieldSource>,
    val observedAt: Instant,
)

data class CrawlLlmProduct(
    val identityHint: String?,
    val name: String?,
    val mpn: String?,
    val className: String?,
    val attributes: Map<String, Any?>,
    val price: BigDecimal?,
    val currency: String?,
    val priceSourceUrl: String?,
    val sourceUrl: String?,
    val confidence: Int?,
)

data class CrawlAllowedDocument(
    val url: String,
    val label: String,
)

data class CrawlExtractionContext(
    val sourceUrl: String,
    val allowedDocuments: List<CrawlAllowedDocument> = emptyList(),
    val page: Int? = null,
    val sheet: String? = null,
    val section: String? = null,
)
