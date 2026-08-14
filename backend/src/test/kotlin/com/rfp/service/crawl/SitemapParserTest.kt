package com.rfp.service.crawl

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SitemapParserTest {
    private val parser = SitemapParser(UrlCanonicalizer())

    @Test
    fun `parses and canonicalizes every child in a sitemap index`() {
        val result = parser.parse(resourceText("sitemap-index.xml"))

        assertThat(result).isInstanceOf(SitemapResult.Index::class.java)
        assertThat((result as SitemapResult.Index).sitemaps.map { it.uri.toString() }).containsExactly(
            "https://example.com/sitemaps/products-1.xml",
            "https://example.com/sitemaps/products-2.xml",
        )
        assertThat(result.sitemaps.first().lastModified.toString()).isEqualTo("2026-08-12")
    }

    @Test
    fun `parses URL metadata and preserves Arabic URL encoding`() {
        val result = parser.parse(resourceText("sitemap-urls.xml")) as SitemapResult.Urls

        assertThat(result.urls.first().uri.toString()).isEqualTo("https://example.com/products/dmm-1000")
        assertThat(result.urls.first().changeFrequency).isEqualTo("weekly")
        assertThat(result.urls.first().priority).isEqualByComparingTo("0.8")
        assertThat(result.urls.last().uri.toASCIIString()).contains("%D8%AC%D9%87%D8%A7%D8%B2")
    }

    @Test
    fun `rejects doctypes instead of resolving external entities`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE urlset [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://example.com/&xxe;</loc></url>
            </urlset>
        """.trimIndent()

        assertThatThrownBy { parser.parse(xml) }
            .isInstanceOf(SitemapParseException::class.java)
    }

    private fun resourceText(name: String): String = checkNotNull(javaClass.getResourceAsStream("/crawl/$name"))
        .bufferedReader().use { it.readText() }
}
