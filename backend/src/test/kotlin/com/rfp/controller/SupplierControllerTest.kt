// backend/src/test/kotlin/com/rfp/controller/SupplierControllerTest.kt
package com.rfp.controller

import com.rfp.domain.Supplier
import com.rfp.repository.CatalogIngestRepository
import com.rfp.repository.SupplierRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class SupplierControllerTest {
    private val repo = mockk<SupplierRepository>()
    private val catalogIngestRepo = mockk<CatalogIngestRepository>()
    private val controller = SupplierController(repo, catalogIngestRepo)

    @Test
    fun `register saves and returns supplier`() {
        val req = SupplierRequest(name = "Acme", officialWebsite = "https://acme.com")
        every { repo.findByNameIgnoreCase("Acme") } returns null
        every { repo.save(any()) } answers { firstArg<Supplier>().copy(id = 1L) }
        val resp = controller.register(req)
        assertThat(resp.statusCode.value()).isEqualTo(200)
        assertThat(resp.body?.name).isEqualTo("Acme")
    }

    @Test
    fun `getById returns 404 when missing`() {
        every { repo.findById(99L) } returns Optional.empty()
        val resp = controller.getById(99L)
        assertThat(resp.statusCode.value()).isEqualTo(404)
    }
}
