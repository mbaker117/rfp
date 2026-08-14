package com.rfp.controller

import com.rfp.domain.Product
import com.rfp.domain.Supplier
import com.rfp.job.CatalogRefreshJob
import com.rfp.repository.AppUserRepository
import com.rfp.repository.ProductRepository
import com.rfp.repository.TenderRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl

class AdminControllerTest {

    @Test
    fun `listProducts exposes non-empty product attributes`() {
        val productRepo = mockk<ProductRepository>()
        val controller = AdminController(
            mockk<CatalogRefreshJob>(),
            mockk<AppUserRepository>(),
            productRepo,
            mockk<TenderRepository>()
        )
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
        val controller = AdminController(
            mockk<CatalogRefreshJob>(),
            mockk<AppUserRepository>(),
            productRepo,
            mockk<TenderRepository>()
        )
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
}
