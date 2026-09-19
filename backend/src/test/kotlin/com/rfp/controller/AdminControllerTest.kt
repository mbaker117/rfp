package com.rfp.controller

import com.rfp.domain.Product
import com.rfp.domain.ProductPrice
import com.rfp.domain.Supplier
import com.rfp.job.CatalogRefreshJob
import com.rfp.repository.AppUserRepository
import com.rfp.repository.ProductPriceRepository
import com.rfp.repository.ProductRepository
import com.rfp.repository.TenderRepository
import com.rfp.service.AttributeSchemaService
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import java.util.Optional

class AdminControllerTest {

    private fun makeController(
        productRepo: ProductRepository = mockk(),
        priceRepo: ProductPriceRepository = mockk { every { findById(any()) } returns Optional.empty() }
    ) = AdminController(
        mockk<CatalogRefreshJob>(),
        mockk<AppUserRepository>(),
        productRepo,
        mockk<TenderRepository>(),
        priceRepo,
        mockk<AttributeSchemaService>()
    )

    @Test
    fun `listProducts exposes non-empty product attributes`() {
        val productRepo = mockk<ProductRepository>()
        val controller = makeController(productRepo)
        val supplier = Supplier(id = 7L, name = "Acme", officialWebsite = "https://example.com")
        val attributes = """{"description":"Bench meter","manualLink":"https://example.com/manual.pdf"}"""
        val product = Product(
            id = 11L,
            supplier = supplier,
            name = "Meter 1000",
            attributes = attributes,
            source = "scrape"
        )
        every { productRepo.searchByNameOrMpn(null, any()) } returns PageImpl(listOf(product))

        val result = controller.listProducts(page = 0, size = 50, q = null, supplierId = null)

        assertThat(result.content).hasSize(1)
        assertThat(result.content.single().attributes).isEqualTo(attributes)
    }

    @Test
    fun `listProducts omits empty attributes object`() {
        val productRepo = mockk<ProductRepository>()
        val controller = makeController(productRepo)
        val product = Product(
            id = 12L,
            supplier = Supplier(id = 8L, name = "Empty", officialWebsite = null),
            name = "Plain product",
            attributes = "{}",
            source = "upload"
        )
        every { productRepo.searchByNameOrMpn(null, any()) } returns PageImpl(listOf(product))

        val result = controller.listProducts(page = 0, size = 50, q = null, supplierId = null)

        assertThat(result.content.single().attributes).isNull()
    }

    @Test
    fun `listProducts maps extractionMethod from ProductPrice`() {
        val productRepo = mockk<ProductRepository>()
        val priceRepo = mockk<ProductPriceRepository>()
        val controller = makeController(productRepo, priceRepo)

        val supplier = Supplier(id = 9L, name = "Supplier", officialWebsite = "https://supplier.com")
        val product = Product(id = 20L, supplier = supplier, name = "Widget", attributes = "{}", source = "crawl")
        val price = ProductPrice(productId = 20L, extractionMethod = "llm")

        every { productRepo.searchByNameOrMpn(null, any()) } returns PageImpl(listOf(product))
        every { priceRepo.findById(20L) } returns Optional.of(price)

        val result = controller.listProducts(page = 0, size = 50, q = null, supplierId = null)

        assertThat(result.content.single().extractionMethod).isEqualTo("llm")
    }

    @Test
    fun `listProducts returns null extractionMethod when no price record exists`() {
        val productRepo = mockk<ProductRepository>()
        val priceRepo = mockk<ProductPriceRepository>()
        val controller = makeController(productRepo, priceRepo)

        val supplier = Supplier(id = 10L, name = "Noprice", officialWebsite = null)
        val product = Product(id = 21L, supplier = supplier, name = "Thing", attributes = "{}", source = "upload")

        every { productRepo.searchByNameOrMpn(null, any()) } returns PageImpl(listOf(product))
        every { priceRepo.findById(21L) } returns Optional.empty()

        val result = controller.listProducts(page = 0, size = 50, q = null, supplierId = null)

        assertThat(result.content.single().extractionMethod).isNull()
    }
}
