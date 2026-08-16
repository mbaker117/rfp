package com.rfp.service.crawl

import com.rfp.domain.CrawlProductObservation
import com.rfp.domain.CrawlRun
import com.rfp.domain.Supplier
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [ProductIdentityService] — no DB required.
 *
 * [CrawlRun] and [Supplier] fields in [CrawlProductObservation] are mockk'd because
 * the identity service only reads mpn / productName / productClassName / sourceUrl.
 */
class ProductIdentityServiceTest {

    private val identity = ProductIdentityService()

    private val mockRun: CrawlRun = mockk()
    private val mockSupplier: Supplier = mockk()

    private fun obs(
        mpn: String?,
        name: String = "Test Product",
        className: String? = null,
        sourceUrl: String = "https://example.com/product",
    ) = CrawlProductObservation(
        run = mockRun,
        supplier = mockSupplier,
        identityKey = "placeholder",
        productName = name,
        mpn = mpn,
        productClassName = className,
        sourceUrl = sourceUrl,
        extractionMethod = "JSON_LD",
    )

    // -------------------------------------------------------------------------
    // MPN-based keys
    // -------------------------------------------------------------------------

    @Test
    fun `normalized MPN wins over name fingerprint`() {
        val o = obs(mpn = "DMM-1000", name = "Fluke Multimeter")
        assertThat(identity.identity(1L, o)).isEqualTo("mpn:DMM1000")
    }

    @Test
    fun `same MPN different casing merges to same key`() {
        assertThat(identity.identity(1L, obs("dmm-1000")))
            .isEqualTo(identity.identity(1L, obs("DMM1000")))
    }

    @Test
    fun `MPN with spaces normalizes to same key`() {
        assertThat(identity.identity(1L, obs("DMM 1000")))
            .isEqualTo("mpn:DMM1000")
    }

    @Test
    fun `MPN with dots normalizes to same key`() {
        assertThat(identity.identity(1L, obs("DMM.1000")))
            .isEqualTo("mpn:DMM1000")
    }

    @Test
    fun `MPN with mixed separators normalizes correctly`() {
        assertThat(identity.identity(1L, obs("D-M M.1000")))
            .isEqualTo("mpn:DMM1000")
    }

    // -------------------------------------------------------------------------
    // Fallback keys (no MPN)
    // -------------------------------------------------------------------------

    @Test
    fun `blank MPN falls back to name fingerprint`() {
        val o = obs(mpn = "  ", name = "Fluke Multimeter")
        assertThat(identity.identity(1L, o)).startsWith("fallback:")
    }

    @Test
    fun `null MPN falls back to name fingerprint`() {
        val o = obs(mpn = null, name = "Fluke Multimeter")
        assertThat(identity.identity(1L, o)).startsWith("fallback:")
    }

    @Test
    fun `same name and URL produce the same fallback key`() {
        val key1 = identity.identity(1L, obs(mpn = null, name = "Fluke Multimeter"))
        val key2 = identity.identity(1L, obs(mpn = null, name = "Fluke Multimeter"))
        assertThat(key1).isEqualTo(key2)
    }

    @Test
    fun `different names produce different fallback keys`() {
        val key1 = identity.identity(1L, obs(mpn = null, name = "Product A"))
        val key2 = identity.identity(1L, obs(mpn = null, name = "Product B"))
        assertThat(key1).isNotEqualTo(key2)
    }

    @Test
    fun `name casing is normalized in fallback key`() {
        val key1 = identity.identity(1L, obs(mpn = null, name = "Fluke Multimeter"))
        val key2 = identity.identity(1L, obs(mpn = null, name = "FLUKE MULTIMETER"))
        assertThat(key1).isEqualTo(key2)
    }

    @Test
    fun `different source URLs produce different fallback keys for same name`() {
        val key1 = identity.identity(1L, obs(mpn = null, name = "Product", sourceUrl = "https://a.com/p1"))
        val key2 = identity.identity(1L, obs(mpn = null, name = "Product", sourceUrl = "https://b.com/p1"))
        assertThat(key1).isNotEqualTo(key2)
    }

    // -------------------------------------------------------------------------
    // Supplier scoping: identity key itself does NOT encode supplierId;
    // scoping is enforced at the product-lookup level (supplier_id + identity_key).
    // -------------------------------------------------------------------------

    @Test
    fun `same MPN from different suppliers produces the same key string`() {
        val key1 = identity.identity(1L, obs("DMM-1000"))
        val key2 = identity.identity(2L, obs("DMM-1000"))
        assertThat(key1).isEqualTo(key2)
    }

    // -------------------------------------------------------------------------
    // Direct (fieldwise) overload
    // -------------------------------------------------------------------------

    @Test
    fun `direct overload with mpn returns same key as entity overload`() {
        val fromEntity = identity.identity(1L, obs("DMM-1000", "Multimeter"))
        val direct = identity.identity(mpn = "DMM-1000", productName = "Multimeter", productClassName = null, sourceUrl = "https://example.com/product")
        assertThat(fromEntity).isEqualTo(direct)
    }
}
