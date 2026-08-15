package com.rfp.service.crawl

import com.rfp.domain.CrawlPageType
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
}
