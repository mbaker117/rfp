package com.rfp.service.crawl

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

class PageParser(
    private val canonicalizer: UrlCanonicalizer,
    private val maxEmbeddedJsonBytes: Int = 256 * 1024,
    maxJsonDepth: Int = 40,
) {
    init {
        require(maxEmbeddedJsonBytes > 0)
        require(maxJsonDepth > 0)
    }

    private val objectMapper: ObjectMapper = JsonMapper.builder(
        JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(maxJsonDepth).build())
            .build(),
    )
        .addModule(KotlinModule.Builder().build())
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
        .build()

    fun parse(fetch: FetchResult.Success): ParsedPage {
        val html = fetch.body.toString(Charsets.UTF_8)
        val document = Jsoup.parse(html, fetch.url.toString())
        val referenceBase = document.select("base[href]")
            .mapNotNull { canonicalizer.resolveAndNormalize(fetch.url, it.attr("href")) }
            .firstOrNull()
            ?: fetch.url
        val canonicalUrl = document.selectFirst("link[rel~=canonical][href]")
            ?.attr("href")
            ?.let { canonicalizer.resolveAndNormalize(referenceBase, it) }
            ?: fetch.url

        val links = document.select("a[href]").mapNotNull { anchor ->
            canonicalizer.resolveAndNormalize(referenceBase, anchor.attr("href"))?.let {
                DiscoveredLink(it, anchor.text().normalizedWhitespace())
            }
        }.distinctBy { it.uri }

        val jsonLdRoots = mutableListOf<JsonNode>()
        var skippedJson = 0
        document.select("script[type=application/ld+json]").forEach { script ->
            parseBoundedJson(script.data()).fold(
                onSuccess = jsonLdRoots::add,
                onFailure = { skippedJson++ },
            )
        }
        val embeddedJson = mutableListOf<JsonNode>()
        document.select("script[type=application/json]").forEach { script ->
            parseBoundedJson(script.data()).fold(
                onSuccess = embeddedJson::add,
                onFailure = { skippedJson++ },
            )
        }
        val products = jsonLdRoots.flatMap { root ->
            val byId = root.allObjects().mapNotNull { node ->
                node.textValue("@id")?.let { it to node }
            }.groupBy({ it.first }, { it.second }).mapValues { (_, definitions) ->
                definitions.maxWith(compareBy<JsonNode> { it.size() }.thenBy { it.toString() })
            }
            root.productNodes().map { it.toProduct(referenceBase, byId) }
        }
        val pagination = document.select("a[href]")
            .filter(::isPaginationLink)
            .mapNotNull { anchor ->
                canonicalizer.resolveAndNormalize(referenceBase, anchor.attr("href"))?.let {
                    DiscoveredLink(it, anchor.text().normalizedWhitespace())
                }
            }
            .distinctBy { it.uri }
        val documents = document.select("a[href]")
            .filter(::isDocumentLink)
            .mapNotNull { anchor ->
                canonicalizer.resolveAndNormalize(referenceBase, anchor.attr("href"))?.let {
                    DiscoveredDocument(
                        it,
                        anchor.text().normalizedWhitespace(),
                        anchor.attr("type").ifBlank { null },
                        linkedProductIdentity(anchor),
                    )
                }
            }
            .distinctBy { it.uri }
        val visibleText = document.body().text().normalizedWhitespace()
        val productBlocks = document.select(PRODUCT_BLOCK_SELECTOR)
        val textBlocks = productBlocks
            .filter { candidate ->
                productBlocks.none { nested -> nested !== candidate && nested.parents().contains(candidate) }
            }
            .map { it.text().normalizedWhitespace() }
            .filter(String::isNotEmpty)
            .distinct()
            .ifEmpty { listOfNotNull(visibleText.takeIf(String::isNotEmpty)) }

        return ParsedPage(
            title = document.title().trim().ifEmpty { null },
            canonicalUrl = canonicalUrl,
            visibleText = visibleText,
            links = links,
            jsonLdProducts = products,
            embeddedJson = embeddedJson,
            pagination = pagination,
            documents = documents,
            signals = PageSignals(
                hasArabicText = visibleText.any { it.code in 0x0600..0x06ff },
                hasProductStructuredData = products.isNotEmpty(),
                skippedEmbeddedJson = skippedJson,
            ),
            textBlocks = textBlocks,
        )
    }

    private fun parseBoundedJson(json: String): Result<JsonNode> {
        if (json.toByteArray(Charsets.UTF_8).size > maxEmbeddedJsonBytes) {
            return Result.failure(IllegalArgumentException("embedded JSON exceeds size limit"))
        }
        return runCatching { objectMapper.readTree(json) }
    }

    private fun JsonNode.toProduct(pageUrl: URI, nodesById: Map<String, JsonNode>): JsonLdProduct {
        val offerNodes = when {
            path("offers").isArray -> path("offers").toList().map { it.resolveReference(nodesById) }
            path("offers").isObject -> listOf(path("offers").resolveReference(nodesById))
            path("offers").isTextual -> listOfNotNull(nodesById[path("offers").asText()])
            else -> emptyList()
        }
        return JsonLdProduct(
            name = textValue("name"),
            description = textValue("description"),
            mpn = textValue("mpn"),
            sku = textValue("sku"),
            brand = path("brand").let { brand ->
                when {
                    brand.isTextual -> brand.textValue()
                    brand.isObject -> brand.textValue("name")
                    else -> null
                }
            },
            offers = offerNodes.flatMap { it.toOffers(pageUrl, nodesById) },
            source = this,
        )
    }

    private fun JsonNode.toOffers(pageUrl: URI, nodesById: Map<String, JsonNode>): List<ProductOffer> {
        val offer = resolveReference(nodesById)
        val specifications = offer.path("priceSpecification").let { raw ->
            when {
                raw.isArray -> raw.toList().map { it.resolveReference(nodesById) }
                raw.isObject -> listOf(raw.resolveReference(nodesById))
                raw.isTextual -> listOfNotNull(nodesById[raw.asText()])
                else -> emptyList()
            }
        }
        if (specifications.isNotEmpty()) return specifications.map { specification ->
            ProductOffer(
                price = specification.textValue("price") ?: offer.textValue("price") ?: offer.textValue("lowPrice"),
                priceCurrency = specification.textValue("priceCurrency") ?: offer.textValue("priceCurrency"),
                availability = offer.textValue("availability"),
                uri = (specification.textValue("url") ?: offer.textValue("url"))
                    ?.let { canonicalizer.resolveAndNormalize(pageUrl, it) },
            )
        }
        return listOf(ProductOffer(
            price = offer.textValue("price") ?: offer.textValue("lowPrice"),
            priceCurrency = offer.textValue("priceCurrency"),
            availability = offer.textValue("availability"),
            uri = offer.textValue("url")?.let { canonicalizer.resolveAndNormalize(pageUrl, it) },
        ))
    }

    private fun JsonNode.resolveReference(nodesById: Map<String, JsonNode>): JsonNode =
        textValue("@id")?.let(nodesById::get) ?: this

    private fun JsonNode.allObjects(): List<JsonNode> = buildList {
        if (isObject) add(this@allObjects)
        if (isContainerNode) elements().forEachRemaining { addAll(it.allObjects()) }
    }

    private fun JsonNode.productNodes(): List<JsonNode> = buildList {
        if (isArray) this@productNodes.forEach { addAll(it.productNodes()) }
        if (isObject) {
            val types = path("@type").let { type ->
                when {
                    type.isArray -> type.mapNotNull(JsonNode::textValue)
                    type.isTextual -> listOf(type.textValue())
                    else -> emptyList()
                }
            }
            if (types.any { it.equals("Product", ignoreCase = true) }) add(this@productNodes)
            path("@graph").takeIf { !it.isMissingNode }?.let { addAll(it.productNodes()) }
        }
    }

    private fun JsonNode.textValue(field: String): String? = path(field)
        .takeIf(JsonNode::isValueNode)
        ?.asText()
        ?.trim()
        ?.ifEmpty { null }

    private fun isPaginationLink(anchor: Element): Boolean {
        val rel = anchor.attr("rel").lowercase(Locale.ROOT).split(Regex("\\s+"))
        return rel.any { it == "next" || it == "prev" } ||
            anchor.parents().any { parent ->
                parent.classNames().any { it.contains("pag", ignoreCase = true) } ||
                    parent.attr("aria-label").contains("pag", ignoreCase = true)
            }
    }

    private fun isDocumentLink(anchor: Element): Boolean {
        val uri = runCatching { URI(anchor.attr("href")) }.getOrNull() ?: return false
        val hints = listOf(anchor.attr("href"), anchor.text(), anchor.className(), anchor.attr("rel"))
            .joinToString(" ")
        return CatalogDocumentFormatDetector.isDiscoverable(uri, anchor.attr("type").ifBlank { null }, hints)
    }

    private fun linkedProductIdentity(anchor: Element): String? {
        val container = anchor.parents().firstOrNull { it.`is`(PRODUCT_BLOCK_SELECTOR) } ?: return null
        return listOf("data-product-id", "data-sku", "data-mpn")
            .firstNotNullOfOrNull { attribute -> container.attr(attribute).trim().takeIf(String::isNotEmpty) }
            ?.take(256)
    }

    private fun String.normalizedWhitespace(): String = trim().split(Regex("\\s+")).joinToString(" ")

    private companion object {
        const val PRODUCT_BLOCK_SELECTOR =
            "article, [itemtype*=Product], [data-product-id], [data-sku], .product, .product-card, .product-item"
    }

}
