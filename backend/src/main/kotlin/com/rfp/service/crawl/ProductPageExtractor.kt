package com.rfp.service.crawl

import com.fasterxml.jackson.databind.JsonNode
import com.rfp.dto.ClassSchema
import com.rfp.dto.CrawlLlmProduct
import com.rfp.service.LlmException
import com.rfp.service.LlmService
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.util.Currency
import java.util.Locale

typealias ExtractedObservation = com.rfp.dto.ExtractedObservation
typealias ExtractionMethod = com.rfp.dto.ExtractionMethod
typealias FieldSource = com.rfp.dto.FieldSource

class CrawlExtractionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Service
class ProductPageExtractor(
    private val llmService: LlmService,
    private val canonicalizer: UrlCanonicalizer = UrlCanonicalizer(),
    private val clock: Clock = Clock.systemUTC(),
    private val maxLlmAttempts: Int = 2,
    private val maxPromptCharacters: Int = 12_000,
) {
    init {
        require(maxLlmAttempts > 0)
        require(maxPromptCharacters >= 256)
    }

    fun extract(page: ParsedPage, classes: List<ClassSchema>): List<ExtractedObservation> {
        val deterministic = buildList {
            page.jsonLdProducts.forEach { add(jsonLdObservation(page, it, classes)) }
            page.embeddedJson.flatMap(::productObjects).forEach { node ->
                embeddedObservation(page, node, classes)?.let(::add)
            }
        }.distinctBy { it.identityHint.lowercase(Locale.ROOT) to it.sourceUrl }
        if (deterministic.isNotEmpty()) return deterministic
        if (page.visibleText.isBlank()) return emptyList()

        val method = if (CrawlClassifier().classify(page).type == com.rfp.domain.CrawlPageType.PRODUCT) {
            ExtractionMethod.PRODUCT_PAGE
        } else {
            ExtractionMethod.LISTING_PAGE
        }
        return extractWithRetries(
            candidate = pageCandidate(page),
            sourceUrl = page.canonicalUrl,
            classes = classes,
            method = method,
            provenance = null,
        )
    }

    fun extract(document: ParsedDocument, classes: List<ClassSchema>): List<ExtractedObservation> =
        document.fragments.flatMap { fragment ->
            extractWithRetries(
                candidate = documentCandidate(document, fragment),
                sourceUrl = document.sourceUrl,
                classes = classes,
                method = ExtractionMethod.MANUFACTURER_MANUAL,
                provenance = fragment.provenance,
            )
        }

    private fun jsonLdObservation(
        page: ParsedPage,
        product: JsonLdProduct,
        classes: List<ClassSchema>,
    ): ExtractedObservation {
        val name = product.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw CrawlExtractionException("JSON-LD product missing name")
        val source = requireSafeAbsolute(page.canonicalUrl.toString(), "JSON-LD source URL")
        val offer = product.offers.firstOrNull { parsePrice(it.price) != null }
        val price = parsePrice(offer?.price)
        val priceSource = offer?.uri?.let { requireSafeAbsolute(it.toString(), "JSON-LD price source URL") }
            ?: price?.let { source }
        val currency = validateOptionalCurrency(offer?.priceCurrency, rejectInvalid = false)
        val attributes = linkedMapOf<String, Any?>()
        product.description?.takeIf { it.isNotBlank() }?.let { attributes["description"] = it.trim() }
        product.brand?.takeIf { it.isNotBlank() }?.let { attributes["brand"] = it.trim() }
        product.sku?.takeIf { it.isNotBlank() }?.let { attributes["sku"] = it.trim() }
        page.documents.firstOrNull()?.uri?.let { attributes["manualLink"] = it.toString() }
        val method = ExtractionMethod.JSON_LD
        val fieldSource = FieldSource(source, method)
        val sources = mutableMapOf<String, FieldSource>()
        listOf("name", "mpn", "className").forEach { sources[it] = fieldSource }
        attributes.keys.forEach { sources[it] = fieldSource }
        if (price != null && priceSource != null) sources["price"] = FieldSource(priceSource, method)
        if (currency != null && priceSource != null) sources["currency"] = FieldSource(priceSource, method)
        return ExtractedObservation(
            identityHint = product.mpn?.trim()?.takeIf { it.isNotEmpty() }
                ?: product.sku?.trim()?.takeIf { it.isNotEmpty() } ?: name,
            name = name,
            mpn = product.mpn?.trim()?.takeIf { it.isNotEmpty() },
            className = matchClass(name, classes),
            attributes = attributes,
            price = price,
            currency = currency,
            priceSourceUrl = priceSource,
            sourceUrl = source,
            method = method,
            confidence = 100,
            fieldSources = sources,
            observedAt = clock.instant(),
        )
    }

    private fun embeddedObservation(
        page: ParsedPage,
        node: JsonNode,
        classes: List<ClassSchema>,
    ): ExtractedObservation? {
        val name = node.firstText("name", "title", "productName") ?: return null
        val mpn = node.firstText("mpn", "model", "productId", "sku")
        val source = node.firstText("url", "sourceUrl", "link")
            ?.let { requireSafeAbsolute(it, "embedded product source URL") }
            ?: requireSafeAbsolute(page.canonicalUrl.toString(), "page source URL")
        val priceNode = node.firstNode("price", "amount", "currentPrice")
            ?: node.path("offers").takeIf { it.isObject }?.firstNode("price", "amount")
        val price = parsePrice(priceNode?.asText())
        val priceSource = price?.let {
            node.firstText("priceSourceUrl", "offerUrl")
                ?.let { raw -> requireSafeAbsolute(raw, "embedded price source URL") }
                ?: source
        }
        val currencyRaw = node.firstText("currency", "priceCurrency")
            ?: node.path("offers").takeIf { it.isObject }?.firstText("currency", "priceCurrency")
        val currency = validateOptionalCurrency(currencyRaw, rejectInvalid = false)
        val attributes = linkedMapOf<String, Any?>()
        node.firstText("description", "summary")?.let { attributes["description"] = it }
        node.firstText("manualLink", "manualUrl", "datasheet")?.let {
            attributes["manualLink"] = requireSafeAbsolute(it, "embedded manual URL").toString()
        }
        val method = ExtractionMethod.API
        val fieldSource = FieldSource(source, method)
        val sources = mutableMapOf<String, FieldSource>()
        listOf("name", "mpn", "className").forEach { sources[it] = fieldSource }
        attributes.keys.forEach { sources[it] = fieldSource }
        if (priceSource != null) sources["price"] = FieldSource(priceSource, method)
        if (currency != null && priceSource != null) sources["currency"] = FieldSource(priceSource, method)
        return ExtractedObservation(
            identityHint = mpn ?: name,
            name = name,
            mpn = mpn,
            className = node.firstText("className", "category") ?: matchClass(name, classes),
            attributes = attributes,
            price = price,
            currency = currency,
            priceSourceUrl = priceSource,
            sourceUrl = source,
            method = method,
            confidence = 98,
            fieldSources = sources,
            observedAt = clock.instant(),
        )
    }

    private fun extractWithRetries(
        candidate: String,
        sourceUrl: URI,
        classes: List<ClassSchema>,
        method: ExtractionMethod,
        provenance: DocumentProvenance?,
    ): List<ExtractedObservation> {
        var input = candidate.take(maxPromptCharacters)
        var lastFailure: Exception? = null
        repeat(maxLlmAttempts) { attempt ->
            try {
                return llmService.extractCrawlProducts(input, classes).map {
                    validateLlmProduct(it, sourceUrl, method, provenance)
                }
            } catch (failure: LlmException) {
                lastFailure = failure
            } catch (failure: CrawlExtractionException) {
                lastFailure = failure
            }
            input = smallerCandidate(input, attempt)
        }
        throw CrawlExtractionException(
            "Terminal product extraction error after $maxLlmAttempts attempts for $sourceUrl",
            lastFailure,
        )
    }

    private fun validateLlmProduct(
        product: CrawlLlmProduct,
        expectedSource: URI,
        method: ExtractionMethod,
        provenance: DocumentProvenance?,
    ): ExtractedObservation {
        val name = product.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw CrawlExtractionException("LLM product missing name")
        val source = requireSafeAbsolute(product.sourceUrl, "LLM source URL")
        val canonicalExpectedSource = requireSafeAbsolute(expectedSource.toString(), "expected source URL")
        if (method == ExtractionMethod.MANUFACTURER_MANUAL && source != canonicalExpectedSource) {
            throw CrawlExtractionException("Manual observation source does not match parsed document")
        }
        val identity = product.identityHint?.trim()?.takeIf { it.isNotEmpty() }
            ?: product.mpn?.trim()?.takeIf { it.isNotEmpty() } ?: name
        val attributes = product.attributes.toMutableMap()
        val description = attributes["description"] as? String
        if (description.isNullOrBlank()) throw CrawlExtractionException("LLM product missing description")
        val manualLink = attributes["manualLink"]
        if (manualLink != null) {
            if (manualLink !is String) throw CrawlExtractionException("Manual link must be a string")
            attributes["manualLink"] = requireSafeAbsolute(manualLink, "LLM manual URL").toString()
        }
        val confidence = product.confidence?.takeIf { it in 0..100 }
            ?: throw CrawlExtractionException("Invalid extraction confidence")
        val currency = validateOptionalCurrency(product.currency, rejectInvalid = true)
        val priceSource = when {
            product.price == null && product.priceSourceUrl == null -> null
            product.price == null -> throw CrawlExtractionException("Price source supplied without a price")
            else -> requireSafeAbsolute(product.priceSourceUrl, "LLM price source URL")
        }
        val fieldSource = FieldSource(
            sourceUrl = source,
            method = method,
            page = provenance?.page,
            sheet = provenance?.sheet,
            section = provenance?.section,
        )
        val sources = mutableMapOf<String, FieldSource>()
        listOf("name", "mpn", "className").forEach { sources[it] = fieldSource }
        attributes.keys.forEach { sources[it] = fieldSource }
        if (product.price != null && priceSource != null) {
            sources["price"] = fieldSource.copy(sourceUrl = priceSource)
            if (currency != null) sources["currency"] = fieldSource.copy(sourceUrl = priceSource)
        }
        return ExtractedObservation(
            identityHint = identity,
            name = name,
            mpn = product.mpn?.trim()?.takeIf { it.isNotEmpty() },
            className = product.className?.trim()?.takeIf { it.isNotEmpty() },
            attributes = attributes.toMap(),
            price = product.price,
            currency = currency,
            priceSourceUrl = priceSource,
            sourceUrl = source,
            method = method,
            confidence = confidence,
            fieldSources = sources,
            observedAt = clock.instant(),
        )
    }

    private fun pageCandidate(page: ParsedPage): String = buildString {
        appendLine("sourceUrl=${page.canonicalUrl}")
        page.title?.let { appendLine("title=${it.take(500)}") }
        append(page.visibleText)
    }.take(maxPromptCharacters)

    private fun documentCandidate(document: ParsedDocument, fragment: DocumentFragment): String = buildString {
        appendLine("sourceUrl=${document.sourceUrl}")
        appendLine("documentPage=${fragment.provenance.page ?: ""}")
        appendLine("documentSheet=${fragment.provenance.sheet ?: ""}")
        appendLine("documentSection=${fragment.provenance.section ?: ""}")
        append(fragment.text)
    }.take(maxPromptCharacters)

    private fun smallerCandidate(input: String, attempt: Int): String {
        val blocks = input.split(Regex("\\n\\s*\\n|(?=\\n(?:Product|Model|SKU|MPN)[:#\\s])", RegexOption.IGNORE_CASE))
            .map(String::trim)
            .filter(String::isNotEmpty)
        return if (blocks.size > 1) {
            blocks[attempt.coerceAtMost(blocks.lastIndex)].take(maxPromptCharacters / 2)
        } else {
            input.take((input.length / 2).coerceAtLeast(1))
        }
    }

    private fun productObjects(root: JsonNode): List<JsonNode> = buildList {
        if (root.isArray) root.forEach { addAll(productObjects(it)) }
        if (root.isObject) {
            val hasName = root.firstText("name", "title", "productName") != null
            val hasIdentity = root.firstText("mpn", "model", "productId", "sku") != null
            if (hasName && hasIdentity) add(root)
            root.elements().forEachRemaining { addAll(productObjects(it)) }
        }
    }

    private fun JsonNode.firstText(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        path(name).takeIf { it.isTextual }?.asText()?.trim()?.ifEmpty { null }
    }

    private fun JsonNode.firstNode(vararg names: String): JsonNode? = names.firstNotNullOfOrNull { name ->
        path(name).takeUnless { it.isMissingNode || it.isNull }
    }

    private fun parsePrice(raw: String?): BigDecimal? = raw?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { BigDecimal(it) }.getOrNull()?.takeIf { amount -> amount.signum() >= 0 }
    }

    private fun validateOptionalCurrency(raw: String?, rejectInvalid: Boolean): String? {
        val candidate = raw?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val valid = candidate.length == 3 && runCatching { Currency.getInstance(candidate) }.isSuccess
        if (!valid && rejectInvalid) throw CrawlExtractionException("Invalid ISO currency: $candidate")
        return candidate.takeIf { valid }
    }

    private fun requireSafeAbsolute(raw: String?, label: String): URI {
        val parsed = raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { URI(it) }.getOrNull() }
            ?: throw CrawlExtractionException("$label is missing or malformed")
        if (!parsed.isAbsolute || parsed.rawAuthority == null) {
            throw CrawlExtractionException("$label must be absolute")
        }
        return canonicalizer.resolveAndNormalize(parsed, parsed.toString())
            ?: throw CrawlExtractionException("$label is unsafe")
    }

    private fun matchClass(name: String, classes: List<ClassSchema>): String? = classes
        .firstOrNull { name.contains(it.name, ignoreCase = true) }
        ?.name
}
