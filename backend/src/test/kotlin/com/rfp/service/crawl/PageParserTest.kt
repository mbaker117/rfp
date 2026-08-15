package com.rfp.service.crawl

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

class PageParserTest {
    private val parser = PageParser(UrlCanonicalizer())

    @Test
    fun `extracts structured product manual pagination and nested anchor label`() {
        val page = parser.parse(successFixture("product-page.html"))

        assertThat(page.title).isEqualTo("DMM-1000 Digital Multimeter | Example Instruments")
        assertThat(page.canonicalUrl).isEqualTo(URI("https://example.com/products/dmm-1000"))
        assertThat(page.visibleText).contains("CAT III 600 V", "جهاز قياس رقمي")
        assertThat(page.jsonLdProducts.single().mpn).isEqualTo("DMM-1000")
        assertThat(page.jsonLdProducts.single().offers.single().priceCurrency).isEqualTo("JOD")
        assertThat(page.documents.map { it.uri.toString() }).containsExactlyInAnyOrder(
            "https://example.com/manuals/DMM-1000-manual.pdf",
            "https://example.com/manuals/DMM-1000-online-guide.html",
            "https://example.com/documents/DMM-1000-specifications.xlsx",
        )
        assertThat(page.pagination.map { it.uri.toString() }).contains(
            "https://example.com/products?page=2",
            "https://example.com/products?page=3",
        )
        assertThat(page.links).anySatisfy { assertThat(it.label).isEqualTo("Digital Multimeters") }
        assertThat(page.links).noneSatisfy { assertThat(it.uri.scheme).isEqualTo("mailto") }
        assertThat(page.embeddedJson.single().path("props").path("product").path("model").asText())
            .isEqualTo("DMM-1000")
        assertThat(page.signals.hasArabicText).isTrue()
        assertThat(page.signals.hasProductStructuredData).isTrue()
    }

    @Test
    fun `skips embedded JSON beyond configured size or depth and records bounded parse signals`() {
        val oversized = "x".repeat(129)
        val html = """
            <html><body>
              <script type="application/json">{"value":"$oversized"}</script>
              <script type="application/json">{"a":{"b":{"c":1}}}</script>
              <script type="application/json">{"valid":true}</script>
            </body></html>
        """.trimIndent()
        val bounded = PageParser(UrlCanonicalizer(), maxEmbeddedJsonBytes = 128, maxJsonDepth = 2)

        val result = bounded.parse(success(html))

        assertThat(result.embeddedJson).singleElement().extracting { it.path("valid").asBoolean() }.isEqualTo(true)
        assertThat(result.signals.skippedEmbeddedJson).isEqualTo(2)
    }

    @Test
    fun `resolves page references against the first valid HTML base URL`() {
        val html = """
            <html><head>
              <base href="javascript:alert(1)">
              <base href="https://cdn.example.com/manufacturer/catalog/">
              <base href="https://ignored.example.com/">
              <link rel="canonical" href="meters/dmm-2000">
              <script type="application/ld+json">
                {"@type":"Product","mpn":"DMM-2000","offers":{"url":"buy/dmm-2000"}}
              </script>
            </head><body>
              <a href="meters">Meters</a>
              <a rel="next" href="page/2">Next</a>
              <a class="manual" href="manuals/dmm-2000.pdf">Manual</a>
            </body></html>
        """.trimIndent()

        val result = parser.parse(success(html))

        assertThat(result.canonicalUrl.toString())
            .isEqualTo("https://cdn.example.com/manufacturer/catalog/meters/dmm-2000")
        assertThat(result.links.map { it.uri.toString() }).contains(
            "https://cdn.example.com/manufacturer/catalog/meters",
        )
        assertThat(result.pagination.single().uri.toString())
            .isEqualTo("https://cdn.example.com/manufacturer/catalog/page/2")
        assertThat(result.documents.single().uri.toString())
            .isEqualTo("https://cdn.example.com/manufacturer/catalog/manuals/dmm-2000.pdf")
        assertThat(result.jsonLdProducts.single().offers.single().uri.toString())
            .isEqualTo("https://cdn.example.com/manufacturer/catalog/buy/dmm-2000")
    }

    @Test
    fun `discovers extensionless manuals from supported anchor media type`() {
        val html = """
            <html><body>
              <a href="/download?id=dmm-1000" type="application/pdf">Download</a>
            </body></html>
        """.trimIndent()

        val result = parser.parse(success(html))

        assertThat(result.documents).hasSize(1)
        assertThat(result.documents.single().uri.toString()).isEqualTo("https://example.com/download?id=dmm-1000")
        assertThat(result.documents.single().mediaType).isEqualTo("application/pdf")
    }

    @Test
    fun `resolves JSON-LD graph offer references by id`() {
        val html = """
            <html><head><script type="application/ld+json">
            {"@graph":[
              {"@type":"Product","name":"DMM-42","mpn":"DMM-42","offers":{"@id":"#offer-42"}},
              {"@id":"#offer-42","@type":"Offer","price":"42.00","priceCurrency":"USD",
               "url":"/products/dmm-42"}
            ]}
            </script></head><body>DMM-42</body></html>
        """.trimIndent()

        val page = parser.parse(success(html))
        assertThat(page.signals.skippedEmbeddedJson).isZero()
        assertThat(page.jsonLdProducts).describedAs("product nodes in @graph").hasSize(1)
        val product = page.jsonLdProducts.single()

        assertThat(product.offers.single().price).isEqualTo("42.00")
        assertThat(product.offers.single().priceCurrency).isEqualTo("USD")
        assertThat(product.offers.single().uri).isEqualTo(URI("https://example.com/products/dmm-42"))
    }

    private fun successFixture(name: String): FetchResult.Success = success(resourceBytes(name).toString(Charsets.UTF_8))

    private fun success(html: String) = FetchResult.Success(
        url = URI("https://example.com/catalog/product-page.html"),
        status = 200,
        contentType = "text/html; charset=UTF-8",
        body = html.toByteArray(),
        etag = null,
        lastModified = null,
        method = FetchMethod.HTTP,
        contentHash = sha256(html.toByteArray()),
    )

    private fun resourceBytes(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/crawl/$name")) {
        "missing fixture $name"
    }.use { it.readAllBytes() }
}
