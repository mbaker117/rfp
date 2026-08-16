package com.rfp.repository

import com.rfp.domain.CrawlPageType
import com.rfp.domain.CrawlRun
import com.rfp.domain.CrawlRunStatus
import com.rfp.domain.CrawlUrl
import com.rfp.domain.CrawlUrlStatus
import com.rfp.domain.Product
import com.rfp.domain.ProductPrice
import com.rfp.domain.ProductPriceHistory
import com.rfp.domain.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.math.BigDecimal

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CrawlPersistenceTest(
    @Autowired val runs: CrawlRunRepository,
    @Autowired val urls: CrawlUrlRepository,
    @Autowired val suppliers: SupplierRepository,
    @Autowired val products: ProductRepository,
    @Autowired val prices: ProductPriceRepository,
    @Autowired val priceHistory: ProductPriceHistoryRepository,
    @Autowired val jdbc: JdbcTemplate
) {
    @Test
    fun `frontier URL is unique per run and pending work can be claimed`() {
        val supplier = suppliers.save(
            Supplier(name = "Large Catalog", officialWebsite = "https://shop.example.com")
        )
        val run = runs.save(
            CrawlRun(
                supplier = supplier,
                status = CrawlRunStatus.QUEUED,
                configJson = """{"batchPages":100}"""
            )
        )
        val frontierUrl = CrawlUrl(
            run = run,
            originalUrl = "https://shop.example.com/p/1",
            normalizedUrl = "https://shop.example.com/p/1",
            host = "shop.example.com",
            status = CrawlUrlStatus.PENDING,
            pageType = CrawlPageType.UNKNOWN,
            depth = 1,
            priority = 50
        )
        urls.save(frontierUrl)

        val claimed = urls.claimBatch(run.id, 10, Instant.now())

        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().status).isEqualTo(CrawlUrlStatus.CLAIMED)

        assertThatThrownBy { urls.save(frontierUrl.copy(id = 0)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `crawl price provenance is retained for current and historical prices`() {
        val supplier = suppliers.save(Supplier(name = "Priced Catalog"))
        val product = products.save(Product(supplier = supplier, name = "Meter", source = "scrape"))
        val observedAt = Instant.parse("2026-08-14T12:00:00Z")

        val current = prices.save(
            ProductPrice(
                productId = product.id,
                price = BigDecimal("12.500"),
                currency = "JOD",
                sourceUrl = "https://shop.example.com/products/meter",
                extractionMethod = "JSON_LD",
                observedAt = observedAt
            )
        )
        val historical = priceHistory.save(
            ProductPriceHistory(
                product = product,
                price = BigDecimal("11.000"),
                currency = "JOD",
                sourceUrl = "https://shop.example.com/products/meter",
                extractionMethod = "JSON_LD",
                observedAt = observedAt
            )
        )

        assertThat(current.sourceUrl).isEqualTo("https://shop.example.com/products/meter")
        assertThat(current.extractionMethod).isEqualTo("JSON_LD")
        assertThat(current.observedAt).isEqualTo(observedAt)
        assertThat(historical.sourceUrl).isEqualTo("https://shop.example.com/products/meter")
        assertThat(historical.extractionMethod).isEqualTo("JSON_LD")
        assertThat(historical.observedAt).isEqualTo(observedAt)
    }

    @Test
    fun `final crawl migration provides price provenance columns`() {
        val columns = jdbc.queryForList(
            """
                SELECT table_name || '.' || column_name
                FROM information_schema.columns
                WHERE table_name IN ('product_price', 'product_price_history')
                  AND column_name IN ('source_url', 'extraction_method', 'observed_at')
            """.trimIndent(),
            String::class.java
        )

        assertThat(columns).containsExactlyInAnyOrder(
            "product_price.source_url",
            "product_price.extraction_method",
            "product_price.observed_at",
            "product_price_history.source_url",
            "product_price_history.extraction_method",
            "product_price_history.observed_at"
        )
    }
}
