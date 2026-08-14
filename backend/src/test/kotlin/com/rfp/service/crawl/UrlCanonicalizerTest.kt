package com.rfp.service.crawl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

class UrlCanonicalizerTest {
    private val canonicalizer = UrlCanonicalizer()

    @Test
    fun `resolves nested relative path against current page`() {
        val result = canonicalizer.resolveAndNormalize(
            URI("https://example.com/catalog/meters/index.html"),
            "../manuals/m1.pdf#page=2",
        )

        assertThat(result.toString()).isEqualTo("https://example.com/catalog/manuals/m1.pdf")
    }

    @Test
    fun `removes tracking parameters but preserves functional query order`() {
        val result = canonicalizer.resolveAndNormalize(
            URI("https://example.com/"),
            "/products?page=2&utm_source=email&sort=price&gclid=123&FBCLID=456",
        )

        assertThat(result.toString()).isEqualTo("https://example.com/products?page=2&sort=price")
    }

    @Test
    fun `normalizes scheme host default port dot segments and fragment`() {
        val result = canonicalizer.resolveAndNormalize(
            URI("HTTPS://SHOP.Example.COM:443/catalog/"),
            "./meters/../m1?view=full#specifications",
        )

        assertThat(result.toString()).isEqualTo("https://shop.example.com/catalog/m1?view=full")
    }

    @Test
    fun `preserves non-default port`() {
        val result = canonicalizer.resolveAndNormalize(
            URI("http://example.com:8080/catalog/"),
            "meter",
        )

        assertThat(result.toString()).isEqualTo("http://example.com:8080/catalog/meter")
    }

    @Test
    fun `normalizes bracketed IPv6 host`() {
        val result = canonicalizer.resolveAndNormalize(
            URI("HTTPS://[2001:4860:4860::8888]:443/catalog/"),
            "manual.pdf",
        )

        assertThat(result.toString()).isEqualTo("https://[2001:4860:4860::8888]/catalog/manual.pdf")
    }

    @Test
    fun `rejects blank malformed fragment-only unsupported and credentialed references`() {
        val page = URI("https://example.com/catalog/index.html")

        assertThat(canonicalizer.resolveAndNormalize(page, "   ")).isNull()
        assertThat(canonicalizer.resolveAndNormalize(page, "https://exa mple.com/manual")).isNull()
        assertThat(canonicalizer.resolveAndNormalize(page, "#section")).isNull()
        assertThat(canonicalizer.resolveAndNormalize(page, "ftp://example.com/manual")).isNull()
        assertThat(canonicalizer.resolveAndNormalize(page, "https://user:secret@example.com/manual")).isNull()
        assertThat(canonicalizer.resolveAndNormalize(page, "https://example.com:70000/manual")).isNull()
    }
}
