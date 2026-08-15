package com.rfp.service.crawl

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.net.URI

data class DiscoveredLink(
    val uri: URI,
    val label: String,
)

data class DiscoveredDocument(
    val uri: URI,
    val label: String,
    val mediaType: String? = null,
)

data class ProductOffer(
    val price: String?,
    val priceCurrency: String?,
    val availability: String?,
    val uri: URI?,
)

data class JsonLdProduct(
    val name: String?,
    val description: String?,
    val mpn: String?,
    val sku: String?,
    val brand: String?,
    val offers: List<ProductOffer>,
    val source: JsonNode,
)

data class PageSignals(
    val hasArabicText: Boolean,
    val hasProductStructuredData: Boolean,
    val skippedEmbeddedJson: Int,
)

data class ParsedPage(
    val title: String?,
    val canonicalUrl: URI,
    val visibleText: String,
    val links: List<DiscoveredLink>,
    val jsonLdProducts: List<JsonLdProduct>,
    val embeddedJson: List<JsonNode>,
    val pagination: List<DiscoveredLink>,
    val documents: List<DiscoveredDocument>,
    val signals: PageSignals,
)

data class SitemapReference(
    val uri: URI,
    val lastModified: String?,
)

data class SitemapUrl(
    val uri: URI,
    val lastModified: String?,
    val changeFrequency: String?,
    val priority: BigDecimal?,
)

sealed interface SitemapResult {
    data class Index(val sitemaps: List<SitemapReference>) : SitemapResult
    data class Urls(val urls: List<SitemapUrl>) : SitemapResult
}

data class DocumentProvenance(
    val sourceUrl: URI,
    val page: Int? = null,
    val sheet: String? = null,
    val section: String? = null,
)

data class DocumentFragment(
    val text: String,
    val provenance: DocumentProvenance,
)

data class ParsedDocument(
    val sourceUrl: URI,
    val contentType: String,
    val fragments: List<DocumentFragment>,
) {
    val text: String get() = fragments.joinToString("\n") { it.text }
    val sections: List<DocumentFragment> get() = fragments
}

enum class DocumentRejectionReason {
    ENCRYPTED,
    OVERSIZED,
    UNSUPPORTED,
    PAGE_LIMIT_EXCEEDED,
    SHEET_LIMIT_EXCEEDED,
    SECTION_LIMIT_EXCEEDED,
    RESOURCE_LIMIT_EXCEEDED,
    TIME_LIMIT_EXCEEDED,
    TYPE_MISMATCH,
    MALFORMED,
    WORKER_INFRASTRUCTURE_FAILURE,
}

class CatalogDocumentRejectedException(
    val reason: DocumentRejectionReason,
    cause: Throwable? = null,
) : RuntimeException("Catalog document rejected: $reason", cause)

class SitemapParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
