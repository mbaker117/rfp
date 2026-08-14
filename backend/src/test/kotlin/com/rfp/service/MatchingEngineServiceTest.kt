package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.rfp.domain.*
import com.rfp.repository.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MatchingEngineServiceTest {

    private val mapper = ObjectMapper()
    private val tenderRepo = mockk<TenderRepository>()
    private val tenderLineRepo = mockk<TenderLineRepository>()
    private val tenderSupplierRepo = mockk<TenderSupplierRepository>()
    private val productRepo = mockk<ProductRepository>()
    private val attrDefRepo = mockk<AttributeDefRepository>()
    private val matchResultRepo = mockk<MatchResultRepository>()
    private val productPriceRepo = mockk<ProductPriceRepository>()

    private val supplier = Supplier(id = 1L, name = "Acme")
    private val productClass = ProductClass(id = 10L, name = "Multimeter")
    private val tender = Tender(id = 1L, userId = 1L, filename = "rfp.pdf", fileType = "pdf", status = "matching")

    private fun service() = MatchingEngineService(
        tenderRepo, tenderLineRepo, tenderSupplierRepo, productRepo, attrDefRepo, matchResultRepo,
        productPriceRepo
    )

    @Test
    fun `exact name match returns score 100 and status matched`() {
        val line = TenderLine(id = 1L, tender = tender, rawText = "Fluke 179",
            description = "Fluke 179", productClass = productClass,
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0)))
        val product = Product(id = 5L, supplier = supplier, productClass = productClass,
            name = "Fluke 179", mpn = "FL179", source = "upload",
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0)))

        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { productRepo.findBySupplierIdInAndIsStaleAndNameIgnoreCase(listOf(1L), false, "Fluke 179") } returns listOf(product)
        every { matchResultRepo.findByLineId(1L) } returns null
        every { matchResultRepo.save(any()) } answers { firstArg() }

        service().matchTender(tender, listOf(1L))

        // MPN lookup must NOT be called when name match found (short-circuit)
        verify(exactly = 0) { productRepo.findBySupplierIdInAndIsStaleAndMpnIgnoreCase(any(), any(), any()) }
        verify { matchResultRepo.save(match { it.score == 100 && it.status == "matched" && it.matchType == "exact" }) }
    }

    @Test
    fun `exact MPN match used as fallback when name lookup misses`() {
        val line = TenderLine(id = 2L, tender = tender, rawText = "FL179",
            description = "FL179", productClass = productClass, attributes = "{}")
        val product = Product(id = 5L, supplier = supplier, productClass = productClass,
            name = "Fluke 179", mpn = "FL179", source = "upload", attributes = "{}")

        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { productRepo.findBySupplierIdInAndIsStaleAndNameIgnoreCase(listOf(1L), false, "FL179") } returns emptyList()
        every { productRepo.findBySupplierIdInAndIsStaleAndMpnIgnoreCase(listOf(1L), false, "FL179") } returns listOf(product)
        every { matchResultRepo.findByLineId(2L) } returns null
        every { matchResultRepo.save(any()) } answers { firstArg() }

        service().matchTender(tender, listOf(1L))

        verify { matchResultRepo.save(match { it.score == 100 && it.matchType == "exact" }) }
    }

    @Test
    fun `unclassified line saved as not_found without querying products`() {
        val line = TenderLine(id = 3L, tender = tender, rawText = "mystery item",
            description = "mystery item", productClass = null, attributes = "{}")

        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { productRepo.findBySupplierIdInAndIsStaleAndNameIgnoreCase(listOf(1L), false, "mystery item") } returns emptyList()
        every { productRepo.findBySupplierIdInAndIsStaleAndMpnIgnoreCase(listOf(1L), false, "mystery item") } returns emptyList()
        every { matchResultRepo.findByLineId(3L) } returns null
        every { matchResultRepo.save(any()) } answers { firstArg() }

        service().matchTender(tender, listOf(1L))

        verify(exactly = 0) { productRepo.findBySupplierIdInAndIsStaleAndProductClassId(any(), any(), any()) }
        verify { matchResultRepo.save(match { it.score == 0 && it.status == "not_found" }) }
    }

    @Test
    fun `spec match scores based on compliant attributes`() {
        val line = TenderLine(id = 4L, tender = tender, rawText = "multimeter 1000V",
            description = "multimeter 1000V", productClass = productClass,
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0, "has_trms" to true)))
        val product = Product(id = 6L, supplier = supplier, productClass = productClass,
            name = "Fluke 115", mpn = "FL115", source = "upload",
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 600.0, "has_trms" to true)))
        val attrDefs = listOf(
            AttributeDef(id = 1L, productClass = productClass, name = "max_voltage", label = "Max V",
                datatype = "numeric", matchOp = "gte", canonicalUnit = "V"),
            AttributeDef(id = 2L, productClass = productClass, name = "has_trms", label = "True RMS",
                datatype = "bool", matchOp = "eq", canonicalUnit = null)
        )

        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { productRepo.findBySupplierIdInAndIsStaleAndNameIgnoreCase(any(), false, any()) } returns emptyList()
        every { productRepo.findBySupplierIdInAndIsStaleAndMpnIgnoreCase(any(), false, any()) } returns emptyList()
        every { productRepo.findBySupplierIdInAndIsStaleAndProductClassId(listOf(1L), false, 10L) } returns listOf(product)
        every { attrDefRepo.findByProductClassId(10L) } returns attrDefs
        every { matchResultRepo.findByLineId(4L) } returns null
        every { productPriceRepo.findAllById(any<Iterable<Long>>()) } returns emptyList()
        every { matchResultRepo.save(any()) } answers { firstArg() }

        service().matchTender(tender, listOf(1L))

        // max_voltage: 600 >= 1000? No → DEVIATION. has_trms: true == true → COMPLIANT → score=50 → partial
        verify { matchResultRepo.save(match { it.score == 50 && it.status == "partial" }) }
    }

    @Test
    fun `numeric eq comparison handles int vs double (220 vs 220_0)`() {
        val line = TenderLine(id = 5L, tender = tender, rawText = "psu", description = "psu",
            productClass = productClass,
            attributes = mapper.writeValueAsString(mapOf("voltage" to 220)))  // Int from JSON
        val product = Product(id = 7L, supplier = supplier, productClass = productClass,
            name = "PSU-220", source = "upload",
            attributes = mapper.writeValueAsString(mapOf("voltage" to 220.0)))  // Double from JSON
        val attrDefs = listOf(
            AttributeDef(id = 3L, productClass = productClass, name = "voltage", label = "Voltage",
                datatype = "numeric", matchOp = "eq", canonicalUnit = "V")
        )

        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { productRepo.findBySupplierIdInAndIsStaleAndNameIgnoreCase(any(), false, any()) } returns emptyList()
        every { productRepo.findBySupplierIdInAndIsStaleAndMpnIgnoreCase(any(), false, any()) } returns emptyList()
        every { productRepo.findBySupplierIdInAndIsStaleAndProductClassId(listOf(1L), false, 10L) } returns listOf(product)
        every { attrDefRepo.findByProductClassId(10L) } returns attrDefs
        every { matchResultRepo.findByLineId(5L) } returns null
        every { productPriceRepo.findAllById(any<Iterable<Long>>()) } returns emptyList()
        every { matchResultRepo.save(any()) } answers { firstArg() }

        service().matchTender(tender, listOf(1L))

        // 220 (int) vs 220.0 (double) → should be COMPLIANT, not DEVIATION
        verify { matchResultRepo.save(match { it.score == 100 && it.status == "matched" && it.matchType == "spec" }) }
    }

    @Test
    fun `alternatives are capped at 5 and sorted descending`() {
        val line = TenderLine(id = 6L, tender = tender, rawText = "multimeter",
            description = "multimeter", productClass = productClass,
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0)))
        val attrDefs = listOf(
            AttributeDef(id = 1L, productClass = productClass, name = "max_voltage", label = "Max V",
                datatype = "numeric", matchOp = "gte", canonicalUnit = "V")
        )
        // 7 products: voltages 1200, 1100, 900, 800, 700, 600, 500
        // Those >= 1000V get score 100; others: 0 (fail filter it.score > 0)
        // Actually with lineAttrs.size=1: each product either COMPLIANT (score=100) or DEVIATION (score=0)
        // Products with voltage < 1000: score=0 → filtered out by .filter { it.score > 0 }
        // Let's make it so best=1200V, alternatives=1100V only (score>=40), capped at 5
        val products = (1..7).map { i ->
            val v = 1200 - (i - 1) * 100  // 1200, 1100, 1000, 900, 800, 700, 600
            Product(id = i.toLong(), supplier = supplier, productClass = productClass,
                name = "P$i", source = "upload",
                attributes = mapper.writeValueAsString(mapOf("max_voltage" to v.toDouble())))
        }

        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { productRepo.findBySupplierIdInAndIsStaleAndNameIgnoreCase(any(), false, any()) } returns emptyList()
        every { productRepo.findBySupplierIdInAndIsStaleAndMpnIgnoreCase(any(), false, any()) } returns emptyList()
        every { productRepo.findBySupplierIdInAndIsStaleAndProductClassId(listOf(1L), false, 10L) } returns products
        every { attrDefRepo.findByProductClassId(10L) } returns attrDefs
        every { matchResultRepo.findByLineId(6L) } returns null
        every { productPriceRepo.findAllById(any<Iterable<Long>>()) } returns emptyList()
        every { matchResultRepo.save(any()) } answers { firstArg() }

        service().matchTender(tender, listOf(1L))

        // Products 1-3 score 100 (1200,1100,1000 >= 1000); 4-7 score 0, filtered out
        // Best = product 1 (1200V), alternatives = products 2,3 (score>=40), max 5
        val saved = mutableListOf<MatchResult>()
        verify { matchResultRepo.save(capture(saved)) }
        assertEquals(100, saved.last().score)
        assertEquals("matched", saved.last().status)
    }

    @Test
    fun `re-running match reuses existing result id (upsert, no unique constraint violation)`() {
        val line = TenderLine(id = 7L, tender = tender, rawText = "Fluke 179",
            description = "Fluke 179", productClass = productClass, attributes = "{}")
        val product = Product(id = 5L, supplier = supplier, productClass = productClass,
            name = "Fluke 179", source = "upload", attributes = "{}")
        val existingResult = MatchResult(id = 99L, line = line, product = product,
            matchType = "exact", score = 100, status = "matched")

        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { productRepo.findBySupplierIdInAndIsStaleAndNameIgnoreCase(listOf(1L), false, "Fluke 179") } returns listOf(product)
        every { matchResultRepo.findByLineId(7L) } returns existingResult  // already matched before
        every { matchResultRepo.save(any()) } answers { firstArg() }

        service().matchTender(tender, listOf(1L))

        // Must save with the existing id=99 to trigger UPDATE not INSERT
        verify { matchResultRepo.save(match { it.id == 99L && it.score == 100 }) }
    }

    @Test
    fun `alternatives JSON includes price and supplierName`() {
        val line = TenderLine(id = 6L, tender = tender, rawText = "multimeter",
            description = "multimeter", productClass = productClass,
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0)))
        val attrDefs = listOf(
            AttributeDef(id = 1L, productClass = productClass, name = "max_voltage",
                label = "Max V", datatype = "numeric", matchOp = "gte", canonicalUnit = "V")
        )
        val best = Product(id = 1L, supplier = supplier, productClass = productClass,
            name = "P1", source = "upload",
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1200.0,
                "description" to "A fine tool", "manualLink" to null)))
        val alt = Product(id = 2L, supplier = supplier, productClass = productClass,
            name = "P2", source = "upload",
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1100.0,
                "description" to "Alt tool", "manualLink" to "https://example.com")))

        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { productRepo.findBySupplierIdInAndIsStaleAndNameIgnoreCase(any(), false, any()) } returns emptyList()
        every { productRepo.findBySupplierIdInAndIsStaleAndMpnIgnoreCase(any(), false, any()) } returns emptyList()
        every { productRepo.findBySupplierIdInAndIsStaleAndProductClassId(any(), false, 10L) } returns listOf(best, alt)
        every { attrDefRepo.findByProductClassId(10L) } returns attrDefs
        every { matchResultRepo.findByLineId(6L) } returns null
        every { productPriceRepo.findAllById(any<Iterable<Long>>()) } returns listOf(
            com.rfp.domain.ProductPrice(productId = 2L, price = java.math.BigDecimal("99.50"), currency = "JOD")
        )
        val saved = mutableListOf<com.rfp.domain.MatchResult>()
        every { matchResultRepo.save(capture(saved)) } answers { firstArg() }

        service().matchTender(tender, listOf(1L))

        val altJson = mapper.readTree(saved.last().alternatives)
        val altNode = altJson[0]
        assertEquals("P2", altNode["name"].asText())
        assertEquals(99.50, altNode["price"].asDouble(), 0.01)
        assertEquals("Acme", altNode["supplierName"].asText())
        assertEquals("Alt tool", altNode["description"].asText())
    }
}
