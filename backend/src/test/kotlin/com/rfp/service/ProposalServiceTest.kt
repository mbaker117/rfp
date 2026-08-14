package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.rfp.domain.*
import com.rfp.repository.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ProposalServiceTest {

    private val mapper = ObjectMapper()
    private val proposalRepo = mockk<ProposalRepository>(relaxed = true)
    private val proposalLineRepo = mockk<ProposalLineRepository>(relaxed = true)
    private val tenderRepo = mockk<TenderRepository>()
    private val tenderLineRepo = mockk<TenderLineRepository>()
    private val matchResultRepo = mockk<MatchResultRepository>()
    private val productRepo = mockk<ProductRepository>()
    private val productPriceRepo = mockk<ProductPriceRepository>()
    private val attrDefRepo = mockk<AttributeDefRepository>()
    private val llmService = mockk<LlmService>()

    private val supplier = Supplier(id = 1L, name = "Acme")
    private val productClass = ProductClass(id = 10L, name = "Multimeter")
    private val tender = Tender(id = 1L, userId = 1L, filename = "rfp.pdf", fileType = "pdf")

    private fun service() = ProposalService(
        proposalRepo, proposalLineRepo, tenderRepo, tenderLineRepo,
        matchResultRepo, productRepo, productPriceRepo, attrDefRepo, llmService
    )

    @Test
    fun `PERFECT variant selects the best-score product from matchResult`() {
        val product = Product(id = 5L, supplier = supplier, productClass = productClass,
            name = "Fluke 179", mpn = "FL179", source = "upload",
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0)))
        val line = TenderLine(id = 1L, tender = tender, rawText = "multimeter",
            description = "multimeter 1000V", productClass = productClass,
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0)))
        val matchResult = MatchResult(id = 1L, line = line, product = product,
            matchType = "spec", score = 90, status = "matched",
            attributeVerdicts = mapper.writeValueAsString(
                listOf(mapOf("attr" to "max_voltage", "required" to 1000.0,
                    "offered" to 1000.0, "verdict" to "COMPLIANT"))),
            alternatives = "[]")

        every { tenderRepo.findById(1L) } returns java.util.Optional.of(tender)
        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { matchResultRepo.findByLineId(1L) } returns matchResult
        every { proposalRepo.deleteByTenderId(1L) } just Runs
        every { productPriceRepo.findById(5L) } returns java.util.Optional.of(
            ProductPrice(productId = 5L, price = BigDecimal("320.00"), currency = "JOD"))
        every { proposalRepo.save(any()) } answers { firstArg<Proposal>().copy(id = 10L) }
        every { proposalLineRepo.save(any()) } answers { firstArg() }
        every { llmService.estimateAcceptance(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            AcceptanceEstimate(probability = 88, reasoning = "Good fit.")

        service().generateProposals(1L)

        // PERFECT proposal line should use product id=5
        verify {
            proposalLineRepo.save(match { pl ->
                pl.selectedProduct?.id == 5L && pl.proposal.variant == "PERFECT"
            })
        }
    }

    @Test
    fun `CHEAPEST variant selects lowest-price candidate among score-gt-0 products`() {
        val expensiveProduct = Product(id = 5L, supplier = supplier, productClass = productClass,
            name = "Expensive", source = "upload",
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1200.0)))
        val cheapProduct = Product(id = 6L, supplier = supplier, productClass = productClass,
            name = "Cheap", source = "upload",
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1100.0)))
        val line = TenderLine(id = 1L, tender = tender, rawText = "x",
            description = "x", productClass = productClass,
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0)))
        val altJson = mapper.writeValueAsString(listOf(
            mapOf("productId" to 6L, "name" to "Cheap", "mpn" to null,
                "score" to 80, "attributeVerdicts" to emptyList<Any>(),
                "price" to 99.50, "currency" to "JOD",
                "description" to null, "manualLink" to null, "supplierName" to "Acme")))
        val matchResult = MatchResult(id = 1L, line = line, product = expensiveProduct,
            matchType = "spec", score = 90, status = "matched",
            attributeVerdicts = "[]", alternatives = altJson)

        every { tenderRepo.findById(1L) } returns java.util.Optional.of(tender)
        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { matchResultRepo.findByLineId(1L) } returns matchResult
        every { proposalRepo.deleteByTenderId(1L) } just Runs
        every { productRepo.findById(6L) } returns java.util.Optional.of(cheapProduct)
        every { productPriceRepo.findById(5L) } returns java.util.Optional.of(
            ProductPrice(productId = 5L, price = BigDecimal("500.00"), currency = "JOD"))
        every { productPriceRepo.findById(6L) } returns java.util.Optional.of(
            ProductPrice(productId = 6L, price = BigDecimal("99.50"), currency = "JOD"))
        every { proposalRepo.save(any()) } answers { firstArg<Proposal>().copy(id = 10L) }
        every { proposalLineRepo.save(any()) } answers { firstArg() }
        every { llmService.estimateAcceptance(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            AcceptanceEstimate(probability = 75, reasoning = "Budget option.")

        service().generateProposals(1L)

        verify {
            proposalLineRepo.save(match { pl ->
                pl.selectedProduct?.id == 6L && pl.proposal.variant == "CHEAPEST"
            })
        }
    }

    @Test
    fun `overrideLine updates selectedProduct and clears probability`() {
        val proposal = Proposal(id = 10L, tender = tender, variant = "PERFECT",
            status = "READY", isComplete = true)
        val product = Product(id = 5L, supplier = supplier, productClass = productClass,
            name = "Original", source = "upload", attributes = "{}")
        val newProduct = Product(id = 7L, supplier = supplier, productClass = productClass,
            name = "Override", source = "upload", attributes = "{}")
        val line = TenderLine(id = 1L, tender = tender, rawText = "x",
            description = "x", productClass = productClass, attributes = "{}")
        val existingPL = ProposalLine(id = 100L, proposal = proposal, line = line,
            selectedProduct = product, matchScore = BigDecimal("90"),
            acceptanceProbability = BigDecimal("88"), llmReasoning = "old")
        val allLines = listOf(existingPL)

        every { proposalRepo.findById(10L) } returns java.util.Optional.of(proposal)
        every { proposalLineRepo.findByProposalIdAndLineId(10L, 1L) } returns existingPL
        every { productRepo.findById(7L) } returns java.util.Optional.of(newProduct)
        every { productPriceRepo.findById(7L) } returns java.util.Optional.empty()
        every { tenderLineRepo.findById(1L) } returns java.util.Optional.of(line)
        every { attrDefRepo.findByProductClassId(any()) } returns emptyList()
        every { proposalLineRepo.save(any()) } answers { firstArg() }
        every { proposalLineRepo.findByProposalId(10L) } returns allLines
        every { proposalRepo.save(any()) } answers { firstArg() }
        every { llmService.estimateAcceptance(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            AcceptanceEstimate(probability = 71, reasoning = "Overridden product.")

        service().overrideLine(10L, 1L, 7L)

        verify { proposalLineRepo.save(match { pl ->
            pl.selectedProduct?.id == 7L && pl.isOverridden && pl.acceptanceProbability == null
        }) }
    }
}
