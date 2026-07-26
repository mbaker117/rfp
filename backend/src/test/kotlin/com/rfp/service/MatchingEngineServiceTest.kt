package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.rfp.domain.*
import com.rfp.repository.*
import io.mockk.*
import org.junit.jupiter.api.Test

class MatchingEngineServiceTest {

    private val mapper = ObjectMapper()
    private val tenderRepo = mockk<TenderRepository>()
    private val tenderLineRepo = mockk<TenderLineRepository>()
    private val tenderSupplierRepo = mockk<TenderSupplierRepository>()
    private val productRepo = mockk<ProductRepository>()
    private val attrDefRepo = mockk<AttributeDefRepository>()
    private val matchResultRepo = mockk<MatchResultRepository>()

    private val supplier = Supplier(id = 1L, name = "Acme")
    private val productClass = ProductClass(id = 10L, name = "Multimeter")
    private val tender = Tender(id = 1L, userId = 1L, filename = "rfp.pdf", fileType = "pdf", status = "matching")

    @Test
    fun `exact name match returns score 100 and status matched`() {
        val line = TenderLine(id = 1L, tender = tender, rawText = "Fluke 179",
            description = "Fluke 179", productClass = productClass,
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0)))
        val product = Product(id = 5L, supplier = supplier, productClass = productClass,
            name = "Fluke 179", mpn = "FL179", source = "upload",
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0)))

        every { tenderRepo.findById(1L) } returns java.util.Optional.of(tender)
        every { tenderRepo.save(any()) } answers { firstArg() }
        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { tenderSupplierRepo.findSupplierIdsByTenderId(1L) } returns listOf(1L)
        every { productRepo.findBySupplierIdInAndIsStaleAndNameIgnoreCase(listOf(1L), false, "Fluke 179") } returns listOf(product)
        every { matchResultRepo.save(any()) } answers { firstArg() }

        val service = MatchingEngineService(tenderRepo, tenderLineRepo, tenderSupplierRepo, productRepo, attrDefRepo, matchResultRepo)
        service.matchTender(tender, listOf(1L))

        verify { matchResultRepo.save(match { it.score == 100 && it.status == "matched" && it.matchType == "exact" }) }
    }

    @Test
    fun `spec match scores based on compliant attributes`() {
        val line = TenderLine(id = 2L, tender = tender, rawText = "multimeter 1000V",
            description = "multimeter 1000V", productClass = productClass,
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0, "has_trms" to true)))
        val product = Product(id = 6L, supplier = supplier, productClass = productClass,
            name = "Fluke 115", mpn = "FL115", source = "upload",
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 600.0, "has_trms" to true)))
        val attrDefs = listOf(
            AttributeDef(id=1L, productClass=productClass, name="max_voltage", label="Max V",
                datatype="numeric", matchOp="gte", canonicalUnit="V"),
            AttributeDef(id=2L, productClass=productClass, name="has_trms", label="True RMS",
                datatype="bool", matchOp="eq", canonicalUnit=null)
        )

        every { tenderRepo.findById(1L) } returns java.util.Optional.of(tender)
        every { tenderRepo.save(any()) } answers { firstArg() }
        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { tenderSupplierRepo.findSupplierIdsByTenderId(1L) } returns listOf(1L)
        every { productRepo.findBySupplierIdInAndIsStaleAndNameIgnoreCase(any(), false, any()) } returns emptyList()
        every { productRepo.findBySupplierIdInAndIsStaleAndMpnIgnoreCase(any(), false, any()) } returns emptyList()
        every { productRepo.findBySupplierIdInAndIsStaleAndProductClassId(listOf(1L), false, 10L) } returns listOf(product)
        every { attrDefRepo.findByProductClassId(10L) } returns attrDefs
        every { matchResultRepo.save(any()) } answers { firstArg() }

        val service = MatchingEngineService(tenderRepo, tenderLineRepo, tenderSupplierRepo, productRepo, attrDefRepo, matchResultRepo)
        service.matchTender(tender, listOf(1L))

        // has_trms matches (50%), max_voltage doesn't (600 < 1000) → score = 50 → partial
        verify { matchResultRepo.save(match { it.score == 50 && it.status == "partial" }) }
    }
}
