package com.rfp.service.crawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.rfp.dto.ClassSchema
import com.rfp.service.LlmClient
import com.rfp.service.LlmService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ProductPageExtractorTest {
    private val observedAt = Instant.parse("2026-08-14T10:15:30Z")
    private val clock = Clock.fixed(observedAt, ZoneOffset.UTC)

    @Test
    fun `JSON-LD product and offer are extracted without an LLM call`() {
        val client = RecordingLlmClient()
        val extractor = extractor(client)

        val observations = extractor.extract(jsonLdPage(currency = "USD"), emptyList())

        with(observations.single()) {
            assertThat(identityHint).isEqualTo("DMM-1000")
            assertThat(mpn).isEqualTo("DMM-1000")
            assertThat(price).isEqualByComparingTo(BigDecimal("129.95"))
            assertThat(currency).isEqualTo("USD")
            assertThat(priceSourceUrl).isEqualTo(URI("https://example.com/products/dmm-1000"))
            assertThat(sourceUrl).isEqualTo(URI("https://example.com/products/dmm-1000"))
            assertThat(method).isEqualTo(ExtractionMethod.JSON_LD)
            assertThat(fieldSources["price"]?.sourceUrl).isEqualTo(priceSourceUrl)
            assertThat(fieldSources["currency"]?.sourceUrl).isEqualTo(priceSourceUrl)
            assertThat(observedAt).isEqualTo(this@ProductPageExtractorTest.observedAt)
        }
        assertThat(client.messages).isEmpty()
    }

    @Test
    fun `price without an ISO currency remains unresolved instead of using a default`() {
        val observation = extractor(RecordingLlmClient())
            .extract(jsonLdPage(currency = null), emptyList())
            .single()

        assertThat(observation.price).isEqualByComparingTo("129.95")
        assertThat(observation.currency).isNull()
    }

    @Test
    fun `embedded API product is extracted deterministically`() {
        val client = RecordingLlmClient()
        val embedded = ObjectMapper().readTree("""
            {"data":{"products":[{"name":"Bench Meter","mpn":"BM-7","description":"Seven digit meter",
              "price":499.0,"currency":"EUR","url":"https://example.com/products/BM-7",
              "priceSourceUrl":"https://example.com/api/prices/BM-7"}]}}
        """.trimIndent())
        val page = emptyPage(
            url = "https://example.com/api/catalog",
            embeddedJson = listOf(embedded),
        )

        val observation = extractor(client).extract(page, emptyList()).single()

        assertThat(observation.mpn).isEqualTo("BM-7")
        assertThat(observation.method).isEqualTo(ExtractionMethod.API)
        assertThat(observation.sourceUrl).isEqualTo(URI("https://example.com/products/BM-7"))
        assertThat(observation.priceSourceUrl).isEqualTo(URI("https://example.com/api/prices/BM-7"))
        assertThat(client.messages).isEmpty()
    }

    @Test
    fun `malformed LLM batch retries with a smaller product block`() {
        val client = RecordingLlmClient(
            "{truncated",
            validLlmJson(sourceUrl = "https://example.com/catalog/meters"),
        )
        val page = emptyPage(
            url = "https://example.com/catalog/meters",
            text = "Product: DMM-200 Digital Multimeter\nMPN: DMM-200\nPrice: 45.00 USD",
        )

        val observations = extractor(client).extract(page, emptyList())

        assertThat(observations).hasSize(1)
        assertThat(observations.single().mpn).isEqualTo("DMM-200")
        assertThat(client.messages).hasSize(2)
        assertThat(client.messages[1].length).isLessThanOrEqualTo(client.messages[0].length)
        assertThat(client.systemPrompts).allMatch { it.contains("schemaVersion") && it.contains("1.0") }
    }

    @Test
    fun `malformed responses become a terminal extraction error after bounded attempts`() {
        val client = RecordingLlmClient("not-json", "still-not-json", "must-not-be-called")
        val page = emptyPage("https://example.com/catalog/meters", "Ambiguous model MTR-10")

        assertThatThrownBy { extractor(client, maxAttempts = 2).extract(page, emptyList()) }
            .isInstanceOf(CrawlExtractionException::class.java)
            .hasMessageContaining("2 attempts")
        assertThat(client.messages).hasSize(2)
    }

    @Test
    fun `manual extraction preserves document page provenance and never invents currency`() {
        val client = RecordingLlmClient(validLlmJson(
            sourceUrl = "https://example.com/manuals/dmm-300.pdf",
            price = null,
            currency = null,
            manualLink = "https://example.com/manuals/dmm-300.pdf",
        ))
        val document = ParsedDocument(
            sourceUrl = URI("https://example.com/manuals/dmm-300.pdf"),
            contentType = "application/pdf",
            fragments = listOf(DocumentFragment(
                "DMM-300 True RMS. Maximum voltage 1000 V.",
                DocumentProvenance(URI("https://example.com/manuals/dmm-300.pdf"), page = 7),
            )),
        )

        val observation = extractor(client).extract(document, emptyList()).single()

        assertThat(observation.method).isEqualTo(ExtractionMethod.MANUFACTURER_MANUAL)
        assertThat(observation.currency).isNull()
        assertThat(observation.attributes["description"]).isEqualTo("True RMS digital multimeter")
        assertThat(observation.attributes["manualLink"])
            .isEqualTo("https://example.com/manuals/dmm-300.pdf")
        assertThat(observation.fieldSources["description"]?.page).isEqualTo(7)
        assertThat(observation.fieldSources["max_voltage"]?.page).isEqualTo(7)
    }

    @Test
    fun `unsafe LLM source and manual URLs are rejected`() {
        val response = validLlmJson(
            sourceUrl = "file:///etc/passwd",
            manualLink = "https://user:secret@example.com/manual.pdf",
        )
        val client = RecordingLlmClient(response, response)

        assertThatThrownBy {
            extractor(client).extract(emptyPage("https://example.com/catalog", "DMM-200"), emptyList())
        }.isInstanceOf(CrawlExtractionException::class.java)
    }

    @Test
    fun `non ISO LLM currency is rejected rather than persisted`() {
        val response = validLlmJson(
            sourceUrl = "https://example.com/catalog/meters",
            currency = "ZZZ",
        )
        val client = RecordingLlmClient(response, response)

        assertThatThrownBy {
            extractor(client).extract(emptyPage("https://example.com/catalog", "DMM-200"), emptyList())
        }.isInstanceOf(CrawlExtractionException::class.java)
            .hasMessageContaining("2 attempts")
    }

    private fun extractor(client: LlmClient, maxAttempts: Int = 2) = ProductPageExtractor(
        llmService = LlmService(client),
        canonicalizer = UrlCanonicalizer(),
        clock = clock,
        maxLlmAttempts = maxAttempts,
        maxPromptCharacters = 2_000,
    )

    private fun jsonLdPage(currency: String?): ParsedPage {
        val pageUrl = URI("https://example.com/products/dmm-1000")
        val product = JsonLdProduct(
            name = "DMM-1000 Digital Multimeter",
            description = "True RMS digital multimeter",
            mpn = "DMM-1000",
            sku = "1000",
            brand = "Acme",
            offers = listOf(ProductOffer("129.95", currency, "InStock", pageUrl)),
            source = ObjectMapper().createObjectNode(),
        )
        return emptyPage(pageUrl.toString(), products = listOf(product))
    }

    private fun emptyPage(
        url: String,
        text: String = "",
        products: List<JsonLdProduct> = emptyList(),
        embeddedJson: List<com.fasterxml.jackson.databind.JsonNode> = emptyList(),
    ) = ParsedPage(
        title = null,
        canonicalUrl = URI(url),
        visibleText = text,
        links = emptyList(),
        jsonLdProducts = products,
        embeddedJson = embeddedJson,
        pagination = emptyList(),
        documents = emptyList(),
        signals = PageSignals(false, products.isNotEmpty(), 0),
    )

    private fun validLlmJson(
        sourceUrl: String,
        price: String? = "45.00",
        currency: String? = "USD",
        manualLink: String? = "https://example.com/manuals/dmm-200.pdf",
    ): String = """
        {"schemaVersion":"1.0","products":[{"identityHint":"DMM-200","name":"DMM-200 Digital Multimeter",
        "mpn":"DMM-200","className":"Multimeter","attributes":{"description":"True RMS digital multimeter",
        "manualLink":${manualLink?.let { "\"$it\"" } ?: "null"},"max_voltage":1000},
        "price":${price ?: "null"},"currency":${currency?.let { "\"$it\"" } ?: "null"},
        "priceSourceUrl":${if (price != null) "\"$sourceUrl\"" else "null"},"sourceUrl":"$sourceUrl",
        "confidence":82}]}
    """.trimIndent()

    private class RecordingLlmClient(vararg responses: String) : LlmClient {
        private val remaining = ArrayDeque(responses.toList())
        val systemPrompts = mutableListOf<String>()
        val messages = mutableListOf<String>()

        override fun call(systemPrompt: String, userMessage: String): String {
            systemPrompts += systemPrompt
            messages += userMessage
            return remaining.removeFirstOrNull() ?: error("Unexpected LLM call")
        }
    }
}
