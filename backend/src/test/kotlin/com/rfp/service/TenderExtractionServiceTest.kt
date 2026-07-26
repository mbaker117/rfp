package com.rfp.service

import com.rfp.domain.*
import com.rfp.dto.*
import com.rfp.repository.*
import io.mockk.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TenderExtractionServiceTest {

    private val tenderRepo = mockk<TenderRepository>()
    private val tenderLineRepo = mockk<TenderLineRepository>()
    private val tenderSupplierRepo = mockk<TenderSupplierRepository>()
    private val supplierRepo = mockk<SupplierRepository>()
    private val productClassRepo = mockk<ProductClassRepository>()
    private val attrDefRepo = mockk<AttributeDefRepository>()
    private val llmService = mockk<LlmService>()
    private val unitService = mockk<UnitNormalizationService>()
    private val docParser = mockk<DocumentParsingService>()
    private val matchingService = mockk<MatchingEngineService>(relaxed = true)

    @Test
    fun `extract saves tender lines with class and attributes`() {
        val tender = Tender(id = 1L, userId = 1L, filename = "rfp.pdf", fileType = "pdf")
        val productClass = ProductClass(id = 10L, name = "Multimeter")

        every { tenderRepo.findById(1L) } returns java.util.Optional.of(tender)
        every { tenderRepo.save(any()) } answers { firstArg() }
        every { docParser.extractText(any(), "pdf") } returns "RFP content"
        every { productClassRepo.findAll() } returns listOf(productClass)
        every { attrDefRepo.findByProductClassId(10L) } returns listOf(
            AttributeDef(id = 1L, productClass = productClass, name = "max_voltage",
                label = "Max Voltage", datatype = "numeric", matchOp = "gte", canonicalUnit = "V")
        )
        every { llmService.parseTenderLines(any(), any()) } returns listOf(
            ParsedTenderLine("Multimeter", "True RMS multimeter", BigDecimal("5"), "pcs",
                mapOf("max_voltage" to 1000.0, "max_voltage_unit" to "V"))
        )
        every { productClassRepo.findByNameIgnoreCase("Multimeter") } returns productClass
        every { unitService.normalizeAttributes(any(), any()) } answers { firstArg() }
        every { tenderLineRepo.save(any()) } answers { firstArg<TenderLine>().copy(id = 1L) }

        val service = TenderExtractionService(
            tenderRepo, tenderLineRepo, tenderSupplierRepo,
            supplierRepo, productClassRepo, attrDefRepo, llmService, unitService, docParser,
            matchingService
        )
        service.runExtraction(tender, "RFP content")

        verify { tenderLineRepo.save(match {
            it.description == "True RMS multimeter" && it.productClass?.id == 10L
        }) }
    }
}
