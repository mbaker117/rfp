package com.rfp.service.crawl

import com.rfp.domain.CrawlPageType
import com.rfp.service.LlmClient
import com.rfp.service.LlmService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

class CrawlClassifierTest {
    private val classifier = CrawlClassifier()

    @Test
    fun `structured product page is classified as a high priority product`() {
        val result = classifier.classify(page(
            url = "https://example.com/products/dmm-1000",
            products = listOf(product("DMM-1000")),
        ))

        assertThat(result.type).isEqualTo(CrawlPageType.PRODUCT)
        assertThat(result.priority).isGreaterThanOrEqualTo(90)
        assertThat(result.shouldCrawl).isTrue()
        assertThat(result.partitionKey).isEqualTo("/products")
        assertThat(result.confidence).isGreaterThanOrEqualTo(95)
    }

    @Test
    fun `pagination and product density identify a listing without structured data`() {
        val links = (1..8).map {
            DiscoveredLink(URI("https://example.com/catalog/model-$it"), "Model-$it")
        }
        val result = classifier.classify(page(
            url = "https://example.com/catalog/meters?page=2",
            text = links.joinToString(" ") { "${it.label} SKU MTR-${it.label.substringAfterLast('-')}" },
            links = links,
            pagination = listOf(DiscoveredLink(URI("https://example.com/catalog/meters?page=3"), "Next")),
        ))

        assertThat(result.type).isEqualTo(CrawlPageType.LISTING)
        assertThat(result.shouldCrawl).isTrue()
        assertThat(result.partitionKey).isEqualTo("/catalog/meters")
    }

    @Test
    fun `non catalog account page is not crawled`() {
        val result = classifier.classify(page(
            url = "https://example.com/account/login",
            text = "Sign in to your account Password Forgot password",
        ))

        assertThat(result.type).isEqualTo(CrawlPageType.OTHER)
        assertThat(result.shouldCrawl).isFalse()
    }

    @Test
    fun `product URL with an identifier takes precedence over category pattern`() {
        val result = classifier.classify(page(
            url = "https://example.com/products/dmm-9000",
            text = "DMM-9000 bench multimeter",
        ))

        assertThat(result.type).isEqualTo(CrawlPageType.PRODUCT)
        assertThat(result.partitionKey).isEqualTo("/products")
    }

    @Test
    fun `ambiguous page uses bounded LLM classification fallback`() {
        val client = RecordingClient("""
            {"schemaVersion":"1.0","type":"CATEGORY","priority":55,"shouldCrawl":true,
             "partitionKey":"/measurement","confidence":72}
        """.trimIndent())
        val fallback = CrawlClassifier(LlmService(client), maxLlmCandidateCharacters = 256)

        val result = fallback.classify(page(
            url = "https://example.com/measurement",
            text = "precision equipment ".repeat(100),
        ))

        assertThat(result.type).isEqualTo(CrawlPageType.CATEGORY)
        assertThat(client.userMessage.length).isLessThanOrEqualTo(256)
    }

    private fun page(
        url: String,
        text: String = "DMM-1000 Digital Multimeter",
        links: List<DiscoveredLink> = emptyList(),
        products: List<JsonLdProduct> = emptyList(),
        pagination: List<DiscoveredLink> = emptyList(),
    ) = ParsedPage(
        title = null,
        canonicalUrl = URI(url),
        visibleText = text,
        links = links,
        jsonLdProducts = products,
        embeddedJson = emptyList(),
        pagination = pagination,
        documents = emptyList(),
        signals = PageSignals(false, products.isNotEmpty(), 0),
    )

    private fun product(mpn: String) = JsonLdProduct(
        name = "Digital Multimeter",
        description = "True RMS meter",
        mpn = mpn,
        sku = null,
        brand = "Acme",
        offers = emptyList(),
        source = com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
    )

    private class RecordingClient(private val response: String) : LlmClient {
        var userMessage: String = ""
        override fun call(systemPrompt: String, userMessage: String): String {
            this.userMessage = userMessage
            return response
        }
    }
}
