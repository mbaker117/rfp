package com.rfp.service.crawl

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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

    private val objectMapper = ObjectMapper(
        JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(maxJsonDepth).build())
            .build(),
    ).registerKotlinModule()

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
            root.productNodes().map { it.toProduct(referenceBase) }
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
                    DiscoveredDocument(it, anchor.text().normalizedWhitespace(), anchor.attr("type").ifBlank { null })
                }
            }
            .distinctBy { it.uri }
        val visibleText = document.body().text().normalizedWhitespace()

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
        )
    }

    private fun parseBoundedJson(json: String): Result<JsonNode> {
        if (json.toByteArray(Charsets.UTF_8).size > maxEmbeddedJsonBytes) {
            return Result.failure(IllegalArgumentException("embedded JSON exceeds size limit"))
        }
        return runCatching { objectMapper.readTree(json) }
    }

    private fun JsonNode.toProduct(pageUrl: URI): JsonLdProduct {
        val offerNodes = when {
            path("offers").isArray -> path("offers").toList()
            path("offers").isObject -> listOf(path("offers"))
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
            offers = offerNodes.map { offer ->
                ProductOffer(
                    price = offer.textValue("price"),
                    priceCurrency = offer.textValue("priceCurrency"),
                    availability = offer.textValue("availability"),
                    uri = offer.textValue("url")?.let { canonicalizer.resolveAndNormalize(pageUrl, it) },
                )
            },
            source = this,
        )
    }

    private fun JsonNode.productNodes(): List<JsonNode> = buildList {
        if (isArray) forEach { addAll(it.productNodes()) }
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

    private fun String.normalizedWhitespace(): String = trim().split(Regex("\\s+")).joinToString(" ")

}
