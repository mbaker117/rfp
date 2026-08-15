package com.rfp.service.crawl

import com.fasterxml.jackson.databind.JsonNode
import com.rfp.dto.ClassSchema
import com.rfp.dto.CrawlAllowedDocument
import com.rfp.dto.CrawlExtractionContext
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
    private val maxExtractedObservations: Int = 500,
) {
    init {
        require(maxLlmAttempts > 0)
        require(maxPromptCharacters >= 256)
        require(maxExtractedObservations > 0)
    }

    fun extract(page: ParsedPage, classes: List<ClassSchema>): List<ExtractedObservation> {
        val deterministic = buildList {
            page.jsonLdProducts.forEach { add(jsonLdObservation(page, it, classes)) }
            page.embeddedJson.flatMap(::productObjects).filter { hasApiEvidence(page, it) }.forEach { node ->
                embeddedObservation(page, node, classes)?.let(::add)
            }
        }
        enforceObservationLimit(deterministic.size)
        if (page.visibleText.isBlank()) return deterministic

        val pageType = CrawlClassifier().classify(page).type
        if (pageType == com.rfp.domain.CrawlPageType.PRODUCT && page.jsonLdProducts.size == 1) {
            return deterministic
        }
        if (pageType == com.rfp.domain.CrawlPageType.API && deterministic.isNotEmpty()) return deterministic
        val method = if (pageType == com.rfp.domain.CrawlPageType.PRODUCT) {
            ExtractionMethod.PRODUCT_PAGE
        } else {
            ExtractionMethod.LISTING_PAGE
        }
        val result = deterministic.toMutableList()
        enforceObservationLimit(result.size)
        candidateGroups(page.visibleText).forEach { candidate ->
            val group = extractWithRetries(
                candidate = candidate,
                sourceUrl = page.canonicalUrl,
                classes = classes,
                method = method,
                provenance = null,
                allowedDocuments = page.documents,
            )
            enforceObservationLimit(result.size + group.size)
            result += group
        }
        return result
    }

    fun extract(document: ParsedDocument, classes: List<ClassSchema>): List<ExtractedObservation> {
        val result = mutableListOf<ExtractedObservation>()
        document.fragments.forEach { fragment ->
            candidateGroups(fragment.text).flatMap { candidate ->
                extractWithRetries(
                    candidate = candidate,
                    sourceUrl = fragment.provenance.sourceUrl,
                    classes = classes,
                    method = ExtractionMethod.MANUFACTURER_MANUAL,
                    provenance = fragment.provenance,
                    allowedDocuments = listOf(DiscoveredDocument(fragment.provenance.sourceUrl, "manufacturer manual")),
                )
            }.forEach { observation ->
                enforceObservationLimit(result.size + 1)
                result += observation
            }
        }
        return result
    }

    private fun jsonLdObservation(
        page: ParsedPage,
        product: JsonLdProduct,
        classes: List<ClassSchema>,
    ): ExtractedObservation {
        val name = product.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw CrawlExtractionException("JSON-LD product missing name")
        val source = requireSafeAbsolute(page.canonicalUrl.toString(), "JSON-LD source URL")
        val offer = product.offers.firstNotNullOfOrNull { candidate ->
            val amount = parsePrice(candidate.price) ?: return@firstNotNullOfOrNull null
            val currency = validateOptionalCurrency(candidate.priceCurrency, rejectInvalid = false)
                ?: return@firstNotNullOfOrNull null
            ValidOffer(candidate, amount, currency)
        }
        val price = offer?.price
        val priceSource = offer?.uri?.let { requireSafeAbsolute(it.toString(), "JSON-LD price source URL") }
            ?: price?.let { source }
        val currency = offer?.currency
        val attributes = linkedMapOf<String, Any?>()
        product.description?.takeIf { it.isNotBlank() }?.let { attributes["description"] = it.trim() }
        product.brand?.takeIf { it.isNotBlank() }?.let { attributes["brand"] = it.trim() }
        product.sku?.takeIf { it.isNotBlank() }?.let { attributes["sku"] = it.trim() }
        associatedManual(page.documents, product.mpn ?: product.sku ?: name)?.let {
            attributes["manualLink"] = it.uri.toString()
        }
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
        val parsedPrice = parsePrice(priceNode?.asText())
        val currencyRaw = node.firstText("currency", "priceCurrency")
            ?: node.path("offers").takeIf { it.isObject }?.firstText("currency", "priceCurrency")
        val parsedCurrency = validateOptionalCurrency(currencyRaw, rejectInvalid = false)
        val price = parsedPrice.takeIf { parsedCurrency != null }
        val currency = parsedCurrency.takeIf { price != null }
        val priceSource = price?.let {
            node.firstText("priceSourceUrl", "offerUrl")
                ?.let { raw -> requireSafeAbsolute(raw, "embedded price source URL") }
                ?: source
        }
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
        allowedDocuments: List<DiscoveredDocument>,
    ): List<ExtractedObservation> {
        var input = candidate.take(maxPromptCharacters)
        var lastFailure: Exception? = null
        repeat(maxLlmAttempts) { attempt ->
            try {
                val context = CrawlExtractionContext(
                    sourceUrl = sourceUrl.toString(),
                    allowedDocuments = allowedDocuments.map { CrawlAllowedDocument(it.uri.toString(), it.label) },
                    page = provenance?.page,
                    sheet = provenance?.sheet,
                    section = provenance?.section,
                )
                val products = llmService.extractCrawlProducts(input, classes, context)
                val validated = products.mapNotNull {
                    runCatching { validateLlmProduct(it, sourceUrl, method, provenance, allowedDocuments) }.getOrNull()
                }
                if (products.isNotEmpty() && validated.isEmpty()) throw CrawlExtractionException("No valid observations")
                return validated
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
        allowedDocuments: List<DiscoveredDocument>,
    ): ExtractedObservation {
        val name = product.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw CrawlExtractionException("LLM product missing name")
        val source = requireSafeAbsolute(expectedSource.toString(), "expected source URL")
        val identity = product.identityHint?.trim()?.takeIf { it.isNotEmpty() }
            ?: product.mpn?.trim()?.takeIf { it.isNotEmpty() } ?: name
        val attributes = product.attributes.toMutableMap()
        val description = attributes["description"] as? String
        if (description.isNullOrBlank()) throw CrawlExtractionException("LLM product missing description")
        val manualLink = attributes["manualLink"]
        if (manualLink != null) {
            if (manualLink !is String) throw CrawlExtractionException("Manual link must be a string")
            val boundManual = if (method == ExtractionMethod.MANUFACTURER_MANUAL) {
                allowedDocuments.singleOrNull()
            } else {
                val requested = runCatching { requireSafeAbsolute(manualLink, "LLM manual URL") }.getOrNull()
                allowedDocuments.firstOrNull { it.uri == requested && matchesIdentity(it, identity) }
            }
            if (boundManual == null) attributes.remove("manualLink")
            else attributes["manualLink"] = boundManual.uri.toString()
        }
        val confidence = product.confidence?.takeIf { it in 0..100 }
            ?: throw CrawlExtractionException("Invalid extraction confidence")
        if (product.price != null && product.price.signum() < 0) throw CrawlExtractionException("Negative price")
        val currency = validateOptionalCurrency(product.currency, rejectInvalid = true)
        if (product.price != null && currency == null) throw CrawlExtractionException("Priced observation missing currency")
        val priceSource = when {
            product.price == null && product.priceSourceUrl == null -> null
            product.price == null -> throw CrawlExtractionException("Price source supplied without a price")
            else -> source
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

    private fun candidateGroups(text: String): List<String> {
        val logicalBlocks = text.split(PRODUCT_BOUNDARY).map(String::trim).filter(String::isNotEmpty)
        return logicalBlocks.flatMap { block ->
            if (block.length <= maxPromptCharacters) listOf(block)
            else block.chunked(maxPromptCharacters)
        }
    }

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

    private fun hasApiEvidence(page: ParsedPage, node: JsonNode): Boolean {
        if (API_PATH.containsMatchIn(page.canonicalUrl.path.orEmpty())) return true
        return listOf("url", "sourceUrl", "priceSourceUrl", "offerUrl").any { field ->
            node.firstText(field)?.let { value ->
                runCatching { URI(value).path.orEmpty() }.getOrNull()?.let(API_PATH::containsMatchIn) == true
            } == true
        }
    }

    private fun associatedManual(documents: List<DiscoveredDocument>, identity: String): DiscoveredDocument? =
        documents.firstOrNull { matchesIdentity(it, identity) }

    private fun matchesIdentity(document: DiscoveredDocument, identity: String): Boolean {
        val token = identity.lowercase(Locale.ROOT).replace(NON_IDENTITY, "")
        if (token.length < 3) return false
        val documentHint = "${document.label} ${document.uri.path}".lowercase(Locale.ROOT).replace(NON_IDENTITY, "")
        return documentHint.contains(token)
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

    private fun enforceObservationLimit(count: Int) {
        if (count > maxExtractedObservations) throw CrawlExtractionException("Page observation limit exceeded")
    }

    private fun matchClass(name: String, classes: List<ClassSchema>): String? = classes
        .firstOrNull { name.contains(it.name, ignoreCase = true) }
        ?.name

    private data class ValidOffer(val offer: ProductOffer, val price: BigDecimal, val currency: String) {
        val uri: URI? get() = offer.uri
    }

    private companion object {
        val PRODUCT_BOUNDARY = Regex("\\n\\s*\\n|(?=\\n(?:Product|Model)[:#\\s])", RegexOption.IGNORE_CASE)
        val API_PATH = Regex("/(?:api|graphql)(?:/|$)", RegexOption.IGNORE_CASE)
        val NON_IDENTITY = Regex("[^a-z0-9]")
    }
}
