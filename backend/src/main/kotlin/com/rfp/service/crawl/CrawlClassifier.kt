package com.rfp.service.crawl

import com.rfp.service.LlmException
import com.rfp.service.LlmService
import java.util.Locale

typealias PageClassification = com.rfp.dto.PageClassification
typealias PageType = com.rfp.domain.CrawlPageType

class CrawlClassifier(
    private val llmService: LlmService? = null,
    private val maxLlmCandidateCharacters: Int = 2_000,
) {
    init {
        require(maxLlmCandidateCharacters >= 128)
    }

    fun classify(page: ParsedPage): PageClassification {
        val path = page.canonicalUrl.path.orEmpty().lowercase(Locale.ROOT)
        val text = listOfNotNull(page.title, page.visibleText).joinToString(" ")
        val productLinks = page.links.count { link ->
            PRODUCT_PATH.containsMatchIn(link.uri.path.orEmpty()) || PRODUCT_ID.containsMatchIn(link.label)
        }
        val identifierCount = PRODUCT_ID.findAll(text).count()
        val structuredCount = page.jsonLdProducts.size

        if (NON_CATALOG_PATH.containsMatchIn(path) && structuredCount == 0) {
            return PageClassification(PageType.OTHER, 0, false, partition(page, false), 98)
        }
        if (structuredCount == 1) {
            return PageClassification(PageType.PRODUCT, 100, true, partition(page, true), 100)
        }
        if (structuredCount > 1) {
            return PageClassification(PageType.LISTING, 95, true, partition(page, false), 100)
        }
        if (API_PATH.containsMatchIn(path) && page.embeddedJson.isNotEmpty()) {
            return PageClassification(PageType.API, 96, true, partition(page, false), 95)
        }
        if (page.pagination.isNotEmpty() || productLinks >= 4 || identifierCount >= 4) {
            return PageClassification(PageType.LISTING, 85, true, partition(page, false), 90)
        }
        if (PRODUCT_PATH.containsMatchIn(path) && identifierCount > 0) {
            return PageClassification(PageType.PRODUCT, 80, true, partition(page, true), 80)
        }
        if (CATALOG_PATH.containsMatchIn(path) || productLinks > 0) {
            return PageClassification(PageType.CATEGORY, 65, true, partition(page, false), 80)
        }
        return llmFallback(page) ?: PageClassification(PageType.OTHER, 10, false, partition(page, false), 75)
    }

    private fun llmFallback(page: ParsedPage): PageClassification? {
        val service = llmService ?: return null
        val candidate = buildString {
            appendLine("url=${page.canonicalUrl}")
            page.title?.let { appendLine("title=${it.take(300)}") }
            append(page.visibleText)
        }.take(maxLlmCandidateCharacters)
        return try {
            service.classifyCrawlPage(candidate)
        } catch (_: LlmException) {
            null
        }
    }

    private fun partition(page: ParsedPage, productPage: Boolean): String {
        val path = page.canonicalUrl.path.orEmpty().trimEnd('/').ifEmpty { "/" }
        if (!productPage || path == "/") return path
        return path.substringBeforeLast('/', "").ifEmpty { "/" }
    }

    private companion object {
        val NON_CATALOG_PATH = Regex("/(account|login|signin|register|cart|checkout)(/|$)")
        val API_PATH = Regex("/(api|graphql)(/|$)")
        val CATALOG_PATH = Regex("/(catalog|products?|categories?|shop)(/|$)")
        val PRODUCT_PATH = Regex("/(products?|items?|models?)/")
        val PRODUCT_ID = Regex("(?i)\\b(?:mpn|sku|model|product)[:#\\s-]*[a-z0-9][a-z0-9._/-]*|\\b[a-z]{2,}[.-]?\\d{2,}[a-z0-9.-]*\\b")
    }
}
