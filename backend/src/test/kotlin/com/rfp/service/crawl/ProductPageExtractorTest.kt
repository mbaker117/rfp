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
    fun `JSON-LD offer without an ISO currency is not emitted as a price`() {
        val observation = extractor(RecordingLlmClient())
            .extract(jsonLdPage(currency = null), emptyList())
            .single()

        assertThat(observation.price).isNull()
        assertThat(observation.currency).isNull()
    }

    @Test
    fun `JSON-LD selects the first deterministic offer with nonnegative price and ISO currency`() {
        val page = jsonLdPage(currency = "ZZZ").let { original ->
            original.copy(jsonLdProducts = listOf(original.jsonLdProducts.single().copy(offers = listOf(
                ProductOffer("129.95", "ZZZ", null, URI("https://example.com/offers/z")),
                ProductOffer("-1.00", "USD", null, URI("https://example.com/offers/a")),
                ProductOffer("130.25", "USD", null, URI("https://example.com/offers/b")),
            ))))
        }

        val observation = extractor(RecordingLlmClient()).extract(page, emptyList()).single()

        assertThat(observation.price).isEqualByComparingTo("130.25")
        assertThat(observation.currency).isEqualTo("USD")
        assertThat(observation.priceSourceUrl).isEqualTo(URI("https://example.com/offers/b"))
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
    fun `all bounded product groups are processed and their observations are unioned`() {
        val client = RecordingLlmClient(
            validLlmJson(sourceUrl = "https://example.com/catalog", identity = "MTR-A"),
            validLlmJson(sourceUrl = "https://example.com/catalog", identity = "MTR-B"),
        )
        val text = "Product: MTR-A\nMPN: MTR-A\n" + "A".repeat(180) +
            "\n\nProduct: MTR-B\nMPN: MTR-B\n" + "B".repeat(180)

        val observations = extractor(client, maxPromptCharacters = 256)
            .extract(emptyPage("https://example.com/catalog", text), emptyList())

        assertThat(observations.map { it.mpn }).containsExactly("MTR-A", "MTR-B")
        assertThat(client.messages).hasSize(2).allMatch {
            ObjectMapper().readTree(it).path("candidateData").asText().length <= 256
        }
    }

    @Test
    fun `a malformed group retry does not discard observations from other groups`() {
        val client = RecordingLlmClient(
            "{truncated",
            validLlmJson(sourceUrl = "https://example.com/catalog", identity = "MTR-A"),
            validLlmJson(sourceUrl = "https://example.com/catalog", identity = "MTR-B"),
        )
        val page = emptyPage(
            "https://example.com/catalog",
            "Product: MTR-A\nMPN: MTR-A\n\nProduct: MTR-B\nMPN: MTR-B",
        )

        val observations = extractor(client, maxPromptCharacters = 256).extract(page, emptyList())

        assertThat(observations.map { it.mpn }).containsExactly("MTR-A", "MTR-B")
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
    fun `manual field provenance uses fragment source coordinates`() {
        val fragmentSource = URI("https://cdn.example.com/manuals/dmm-300-page-7.pdf")
        val client = RecordingLlmClient(validLlmJson(
            sourceUrl = "https://attacker.example/spoofed.pdf",
            price = null,
            currency = null,
            manualLink = fragmentSource.toString(),
        ))
        val document = ParsedDocument(
            sourceUrl = URI("https://example.com/manuals/dmm-300.pdf"),
            contentType = "application/pdf",
            fragments = listOf(DocumentFragment(
                "DMM-300 maximum voltage 1000 V",
                DocumentProvenance(fragmentSource, page = 7),
            )),
        )

        val observation = extractor(client).extract(document, emptyList()).single()

        assertThat(observation.sourceUrl).isEqualTo(fragmentSource)
        assertThat(observation.fieldSources["max_voltage"]?.sourceUrl).isEqualTo(fragmentSource)
        assertThat(observation.fieldSources["max_voltage"]?.page).isEqualTo(7)
    }

    @Test
    fun `model source and price URLs are bound to page and undiscovered manuals are removed`() {
        val response = validLlmJson(
            sourceUrl = "https://attacker.example/product",
            manualLink = "https://attacker.example/manual.pdf",
        )
        val client = RecordingLlmClient(response, response)

        val observation = extractor(client)
            .extract(emptyPage("https://example.com/catalog", "DMM-200"), emptyList())
            .single()

        assertThat(observation.sourceUrl).isEqualTo(URI("https://example.com/catalog"))
        assertThat(observation.priceSourceUrl).isEqualTo(URI("https://example.com/catalog"))
        assertThat(observation.attributes["manualLink"]).isNull()
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

    @Test
    fun `generic inline JSON is not labeled API and does not suppress page extraction`() {
        val embedded = ObjectMapper().readTree("""
            {"props":{"product":{"name":"Hydrated meter","mpn":"HYD-1","price":10,"currency":"USD"}}}
        """.trimIndent())
        val client = RecordingLlmClient(validLlmJson(
            sourceUrl = "https://example.com/catalog",
            identity = "PAGE-1",
        ))
        val page = emptyPage(
            url = "https://example.com/catalog",
            text = "Product: PAGE-1 MPN PAGE-1",
            embeddedJson = listOf(embedded),
        )

        val observations = extractor(client).extract(page, emptyList())

        assertThat(observations).hasSize(1)
        assertThat(observations.single().mpn).isEqualTo("PAGE-1")
        assertThat(observations.single().method).isEqualTo(ExtractionMethod.LISTING_PAGE)
        assertThat(client.messages).hasSize(1)
    }

    @Test
    fun `manual is associated only with matching JSON-LD product identity`() {
        val source = URI("https://example.com/catalog")
        val products = listOf("DMM-A", "DMM-B").map { identity ->
            JsonLdProduct(identity, "description", identity, null, null, emptyList(), ObjectMapper().createObjectNode())
        }
        val page = emptyPage(source.toString(), products = products, documents = listOf(
            DiscoveredDocument(URI("https://example.com/manuals/DMM-A.pdf"), "DMM-A manual", "application/pdf"),
        ))

        val observations = extractor(RecordingLlmClient()).extract(page, emptyList())

        assertThat(observations).hasSize(2)
        assertThat(observations.single { it.mpn == "DMM-A" }.attributes["manualLink"])
            .isEqualTo("https://example.com/manuals/DMM-A.pdf")
        assertThat(observations.single { it.mpn == "DMM-B" }.attributes["manualLink"]).isNull()
    }

    @Test
    fun `complementary structured observations with same identity are preserved for later merge`() {
        val source = URI("https://example.com/api/catalog")
        val embedded = listOf(
            ObjectMapper().readTree("""{"name":"Meter","mpn":"M-1","description":"first"}"""),
            ObjectMapper().readTree("""{"name":"Meter","mpn":"M-1","description":"second"}"""),
        )

        val observations = extractor(RecordingLlmClient()).extract(
            emptyPage(source.toString(), embeddedJson = embedded),
            emptyList(),
        )

        assertThat(observations).hasSize(2)
        assertThat(observations.map { it.attributes["description"] }).containsExactly("first", "second")
    }

    @Test
    fun `negative embedded API price is unresolved`() {
        val embedded = ObjectMapper().readTree("""
            {"products":[{"name":"Meter","mpn":"M-NEG","price":"-0.01","currency":"USD"}]}
        """.trimIndent())

        val observation = extractor(RecordingLlmClient()).extract(
            emptyPage("https://example.com/api/catalog", embeddedJson = listOf(embedded)),
            emptyList(),
        ).single()

        assertThat(observation.price).isNull()
        assertThat(observation.currency).isNull()
        assertThat(observation.priceSourceUrl).isNull()
    }

    @Test
    fun `invalid LLM sibling does not discard valid observation`() {
        val valid = validLlmJson(sourceUrl = "https://example.com/catalog", identity = "GOOD-1")
            .substringAfter("[{").substringBeforeLast("}]}")
        val invalid = validLlmJson(
            sourceUrl = "https://example.com/catalog",
            identity = "BAD-1",
            price = "-1.00",
        ).substringAfter("[{").substringBeforeLast("}]}")
        val response = """{"schemaVersion":"1.0","products":[{$valid},{$invalid}]}"""
        val client = RecordingLlmClient(response)

        val observations = extractor(client).extract(
            emptyPage("https://example.com/catalog", "Product: GOOD-1 and BAD-1"),
            emptyList(),
        )

        assertThat(observations.map { it.mpn }).containsExactly("GOOD-1")
        assertThat(client.messages).hasSize(1)
    }

    @Test
    fun `page observation ceiling is enforced across bounded groups`() {
        val client = RecordingLlmClient(
            validLlmJson(sourceUrl = "https://example.com/catalog", identity = "MTR-A"),
            validLlmJson(sourceUrl = "https://example.com/catalog", identity = "MTR-B"),
        )
        val extractor = ProductPageExtractor(
            llmService = LlmService(client),
            canonicalizer = UrlCanonicalizer(),
            clock = clock,
            maxLlmAttempts = 2,
            maxPromptCharacters = 256,
            maxExtractedObservations = 1,
        )

        assertThatThrownBy {
            extractor.extract(
                emptyPage("https://example.com/catalog", "Product: MTR-A\n\nProduct: MTR-B"),
                emptyList(),
            )
        }.isInstanceOf(CrawlExtractionException::class.java)
            .hasMessageContaining("observation limit")
    }

    @Test
    fun `structured observations cannot bypass page observation ceiling`() {
        val products = listOf("MTR-A", "MTR-B").map { identity ->
            JsonLdProduct(identity, "description", identity, null, null, emptyList(), ObjectMapper().createObjectNode())
        }
        val extractor = ProductPageExtractor(
            llmService = LlmService(RecordingLlmClient()),
            canonicalizer = UrlCanonicalizer(),
            clock = clock,
            maxExtractedObservations = 1,
        )

        assertThatThrownBy {
            extractor.extract(emptyPage("https://example.com/catalog", products = products), emptyList())
        }.isInstanceOf(CrawlExtractionException::class.java)
            .hasMessageContaining("observation limit")
    }

    private fun extractor(
        client: LlmClient,
        maxAttempts: Int = 2,
        maxPromptCharacters: Int = 2_000,
    ) = ProductPageExtractor(
        llmService = LlmService(client),
        canonicalizer = UrlCanonicalizer(),
        clock = clock,
        maxLlmAttempts = maxAttempts,
        maxPromptCharacters = maxPromptCharacters,
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
        documents: List<DiscoveredDocument> = emptyList(),
    ) = ParsedPage(
        title = null,
        canonicalUrl = URI(url),
        visibleText = text,
        links = emptyList(),
        jsonLdProducts = products,
        embeddedJson = embeddedJson,
        pagination = emptyList(),
        documents = documents,
        signals = PageSignals(false, products.isNotEmpty(), 0),
    )

    private fun validLlmJson(
        sourceUrl: String,
        price: String? = "45.00",
        currency: String? = "USD",
        manualLink: String? = "https://example.com/manuals/dmm-200.pdf",
        identity: String = "DMM-200",
    ): String = """
        {"schemaVersion":"1.0","products":[{"identityHint":"$identity","name":"$identity Digital Multimeter",
        "mpn":"$identity","className":"Multimeter","attributes":{"description":"True RMS digital multimeter",
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
