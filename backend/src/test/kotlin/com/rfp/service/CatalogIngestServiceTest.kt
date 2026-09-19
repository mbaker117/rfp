// backend/src/test/kotlin/com/rfp/service/CatalogIngestServiceTest.kt
package com.rfp.service

import com.rfp.domain.*
import com.rfp.dto.*
import com.rfp.repository.*
import io.mockk.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CatalogIngestServiceTest {

    private val supplierRepo = mockk<SupplierRepository>()
    private val productClassRepo = mockk<ProductClassRepository>()
    private val attrDefRepo = mockk<AttributeDefRepository>()
    private val productRepo = mockk<ProductRepository>()
    private val productPriceRepo = mockk<ProductPriceRepository>()
    private val priceHistoryRepo = mockk<ProductPriceHistoryRepository>()
    private val ingestRepo = mockk<CatalogIngestRepository>()
    private val llmService = mockk<LlmService>()
    private val unitService = mockk<UnitNormalizationService>()
    private val docParser = mockk<DocumentParsingService>()

    private val supplier = Supplier(id = 1L, name = "Acme")
    private val productClass = ProductClass(id = 10L, name = "Multimeter")

    /** Builds the service without a ScrapeService dependency (removed after facade change). */
    private fun makeService() = CatalogIngestService(
        supplierRepo, productClassRepo, attrDefRepo, productRepo,
        productPriceRepo, priceHistoryRepo, ingestRepo, llmService, unitService, docParser
    )

    @Test
    fun `ingest upserts new product`() {
        val ingest = CatalogIngest(id = 1L, supplier = supplier, kind = "company_upload")
        every { supplierRepo.findById(1L) } returns java.util.Optional.of(supplier)
        every { ingestRepo.save(any()) } answers { firstArg<CatalogIngest>().copy(id = 1L) }
        every { docParser.extractText(any(), "xlsx") } returns "raw text"
        every { productClassRepo.findAll() } returns listOf(productClass)
        every { attrDefRepo.findByProductClassId(10L) } returns emptyList()
        every { llmService.parseCatalogChunk(any(), any(), any()) } returns CatalogBatchResult(truncated = false, products = listOf(
            ParsedProduct("Multimeter", "Fluke 179", "FL179", BigDecimal("320"), "JOD",
                mapOf("max_voltage" to 1000.0))
        ))
        every { productClassRepo.findByNameIgnoreCase("Multimeter") } returns productClass
        every { unitService.normalizeAttributes(any(), any()) } answers { firstArg() }
        every { productRepo.findAllBySupplierIdAndMpnIgnoreCase(1L, "FL179") } returns emptyList()
        every { productRepo.findAllBySupplierIdAndNameIgnoreCase(1L, "Fluke 179") } returns emptyList()
        every { productRepo.save(any()) } answers { firstArg<Product>().copy(id = 5L) }
        every { productPriceRepo.findById(any()) } returns java.util.Optional.empty()
        every { productPriceRepo.save(any()) } answers { firstArg() }
        every { productRepo.findBySupplierId(1L) } returns emptyList()
        every { supplierRepo.save(any()) } answers { firstArg() }

        makeService().runIngest(ingest, "raw text", "upload")

        verify { productRepo.save(match { it.name == "Fluke 179" && it.mpn == "FL179" }) }
    }

    @Test
    fun `upload ingestion path does not route through CrawlCoordinator`() {
        // CatalogIngestService has no reference to CrawlCoordinator; this test documents
        // that the upload path (ingestFile → runIngest) is completely decoupled from the
        // adaptive crawler after the facade change.
        val ingest = CatalogIngest(id = 2L, supplier = supplier, kind = "company_upload")
        every { ingestRepo.save(any()) } answers { firstArg<CatalogIngest>().copy(id = 2L) }
        every { productClassRepo.findAll() } returns listOf(productClass)
        every { attrDefRepo.findByProductClassId(10L) } returns emptyList()
        every { llmService.parseCatalogChunk(any(), any(), any()) } returns CatalogBatchResult(emptyList(), truncated = false)
        every { productRepo.findBySupplierId(1L) } returns emptyList()
        every { supplierRepo.save(any()) } answers { firstArg() }

        // Should complete without any exception or unexpected interaction
        makeService().runIngest(ingest, "some catalog text", "upload")

        // Verify ingest is saved as DONE with no coordinator involvement
        verify { ingestRepo.save(match { it.status == "DONE" }) }
        verify { supplierRepo.save(match { it.scrapeStatus == "DONE" }) }
    }
}
