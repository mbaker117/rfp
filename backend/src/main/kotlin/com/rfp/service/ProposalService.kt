package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rfp.domain.*
import com.rfp.repository.*
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

data class EnrichedAlt(
    val productId: Long, val name: String, val mpn: String?,
    val score: Int, val attributeVerdicts: List<Map<String, Any?>>,
    val price: BigDecimal?, val currency: String,
    val description: String?, val manualLink: String?, val supplierName: String
)

data class SelectedProductDto(
    val id: Long, val name: String, val mpn: String?,
    val price: BigDecimal?, val currency: String,
    val supplierName: String, val description: String?, val manualLink: String?
)

data class ProposalLineDto(
    val lineId: Long, val description: String?, val qty: BigDecimal?,
    val matchScore: BigDecimal?, val selectedProduct: SelectedProductDto?,
    val acceptanceProbability: BigDecimal?, val llmReasoning: String?,
    val isOverridden: Boolean, val alternatives: List<EnrichedAlt>
)

data class ProposalDto(
    val id: Long, val variant: String, val status: String,
    val acceptanceRate: BigDecimal?, val matchScore: BigDecimal?,
    val isComplete: Boolean, val lines: List<ProposalLineDto>
)

@Service
open class ProposalService(
    private val proposalRepo: ProposalRepository,
    private val proposalLineRepo: ProposalLineRepository,
    private val tenderRepo: TenderRepository,
    private val tenderLineRepo: TenderLineRepository,
    private val matchResultRepo: MatchResultRepository,
    private val productRepo: ProductRepository,
    private val productPriceRepo: ProductPriceRepository,
    private val attrDefRepo: AttributeDefRepository,
    private val llmService: LlmService
) {
    private val mapper = ObjectMapper().apply { findAndRegisterModules() }

    @Async("taskExecutor")
    open fun generateProposals(tenderId: Long) {
        val tender = tenderRepo.findById(tenderId).orElseThrow()
        val lines = tenderLineRepo.findByTenderId(tenderId)
        proposalRepo.deleteByTenderId(tenderId)

        val variants = listOf("PERFECT", "BEST_ACCEPTANCE", "CHEAPEST")
        val proposals = variants.map { variant ->
            proposalRepo.save(Proposal(tender = tender, variant = variant))
        }

        proposals.forEach { proposal ->
            try {
                generateVariantLines(proposal, lines)
                val pLines = proposalLineRepo.findByProposalId(proposal.id)
                val avgAcceptance = pLines.mapNotNull { it.acceptanceProbability }
                    .takeIf { it.isNotEmpty() }
                    ?.let { list -> list.reduce(BigDecimal::add).divide(BigDecimal(list.size), 2, RoundingMode.HALF_UP) }
                val avgScore = pLines.mapNotNull { it.matchScore }
                    .takeIf { it.isNotEmpty() }
                    ?.let { list -> list.reduce(BigDecimal::add).divide(BigDecimal(list.size), 2, RoundingMode.HALF_UP) }
                val isComplete = pLines.none { it.selectedProduct == null || it.acceptanceProbability == BigDecimal.ZERO }
                proposalRepo.save(proposal.copy(
                    acceptanceRate = avgAcceptance,
                    matchScore = avgScore,
                    isComplete = isComplete,
                    status = "READY",
                    updatedAt = Instant.now()
                ))
            } catch (e: Exception) {
                proposalRepo.save(proposal.copy(status = "FAILED", updatedAt = Instant.now()))
            }
        }
    }

    private fun generateVariantLines(proposal: Proposal, lines: List<TenderLine>) {
        lines.forEach { line ->
            val matchResult = matchResultRepo.findByLineId(line.id)
            val alts: List<EnrichedAlt> = parseAlts(matchResult?.alternatives)

            val (selectedProduct, matchScore) = when (proposal.variant) {
                "PERFECT" -> Pair(matchResult?.product, matchResult?.score?.toBigDecimal())
                "BEST_ACCEPTANCE" -> selectBestAcceptance(line, matchResult, alts)
                "CHEAPEST" -> selectCheapest(matchResult, alts)
                else -> Pair(matchResult?.product, matchResult?.score?.toBigDecimal())
            }

            val estimate = if (selectedProduct != null) {
                computeEstimate(line, matchResult, selectedProduct, alts)
            } else null

            proposalLineRepo.save(ProposalLine(
                proposal = proposal,
                line = line,
                selectedProduct = selectedProduct,
                matchScore = matchScore,
                acceptanceProbability = estimate?.probability?.toBigDecimal(),
                llmReasoning = estimate?.reasoning
            ))
        }
    }

    private fun parseAlts(alternativesJson: String?): List<EnrichedAlt> {
        if (alternativesJson == null || alternativesJson == "[]") return emptyList()
        return try { mapper.readValue(alternativesJson) } catch (_: Exception) { emptyList() }
    }

    private fun selectBestAcceptance(
        line: TenderLine,
        matchResult: MatchResult?,
        alts: List<EnrichedAlt>
    ): Pair<Product?, BigDecimal?> {
        if (matchResult?.product == null) return Pair(null, null)
        val candidates = mutableListOf<Pair<Product, Int>>()
        candidates.add(Pair(matchResult.product, matchResult.score))
        alts.forEach { alt ->
            productRepo.findById(alt.productId).orElse(null)?.let { p ->
                candidates.add(Pair(p, alt.score))
            }
        }
        val lineAttrs: Map<String, Any> = try { mapper.readValue(line.attributes) } catch (_: Exception) { emptyMap() }
        val best = candidates.maxByOrNull { (product, score) ->
            val productAttrs: Map<String, Any> = try { mapper.readValue(product.attributes) } catch (_: Exception) { emptyMap() }
            val price = productPriceRepo.findById(product.id).orElse(null)
            val verdictsStr = buildVerdictsStr(matchResult, alts, product.id)
            try {
                llmService.estimateAcceptance(
                    lineDescription = line.description ?: line.rawText,
                    lineAttrs = lineAttrs,
                    productName = product.name,
                    productMpn = product.mpn,
                    productAttrs = productAttrs,
                    verdictsStr = verdictsStr,
                    price = price?.price,
                    currency = price?.currency ?: "JOD"
                ).probability
            } catch (_: Exception) { score }
        }
        return Pair(best?.first, best?.second?.toBigDecimal())
    }

    private fun selectCheapest(
        matchResult: MatchResult?,
        alts: List<EnrichedAlt>
    ): Pair<Product?, BigDecimal?> {
        if (matchResult?.product == null) return Pair(null, null)

        data class Candidate(val product: Product, val score: Int, val price: BigDecimal?)

        val candidates = mutableListOf<Candidate>()
        val mainPrice = productPriceRepo.findById(matchResult.product.id).orElse(null)?.price
        candidates.add(Candidate(matchResult.product, matchResult.score, mainPrice))
        alts.filter { it.score > 0 }.forEach { alt ->
            productRepo.findById(alt.productId).orElse(null)?.let { p ->
                candidates.add(Candidate(p, alt.score, alt.price))
            }
        }
        val cheapest = candidates.sortedWith { a, b ->
            when {
                a.price == null && b.price == null -> 0
                a.price == null -> 1   // null sorts last
                b.price == null -> -1
                else -> a.price.compareTo(b.price)
            }
        }.firstOrNull()
        return Pair(cheapest?.product, cheapest?.score?.toBigDecimal())
    }

    private fun computeEstimate(
        line: TenderLine,
        matchResult: MatchResult?,
        product: Product,
        alts: List<EnrichedAlt>
    ): AcceptanceEstimate? {
        return try {
            val lineAttrs: Map<String, Any> = mapper.readValue(line.attributes)
            val productAttrs: Map<String, Any> = mapper.readValue(product.attributes)
            val price = productPriceRepo.findById(product.id).orElse(null)
            val verdictsStr = buildVerdictsStr(matchResult, alts, product.id)
            llmService.estimateAcceptance(
                lineDescription = line.description ?: line.rawText,
                lineAttrs = lineAttrs,
                productName = product.name,
                productMpn = product.mpn,
                productAttrs = productAttrs,
                verdictsStr = verdictsStr,
                price = price?.price,
                currency = price?.currency ?: "JOD"
            )
        } catch (_: Exception) { null }
    }

    private fun buildVerdictsStr(matchResult: MatchResult?, alts: List<EnrichedAlt>, productId: Long): String {
        if (matchResult?.product?.id == productId) {
            val verdicts: List<Map<String, Any?>> = try {
                if (matchResult.attributeVerdicts != "[]") mapper.readValue(matchResult.attributeVerdicts)
                else emptyList()
            } catch (_: Exception) { emptyList() }
            return verdicts.joinToString(", ") { "${it["attr"]}: ${it["verdict"]}" }
        }
        val alt = alts.firstOrNull { it.productId == productId }
        return alt?.attributeVerdicts?.joinToString(", ") { "${it["attr"]}: ${it["verdict"]}" } ?: ""
    }

    @Async("taskExecutor")
    open fun overrideLine(proposalId: Long, lineId: Long, newProductId: Long) {
        val proposal = proposalRepo.findById(proposalId).orElseThrow()
        val pl = proposalLineRepo.findByProposalIdAndLineId(proposalId, lineId) ?: return
        val newProduct = productRepo.findById(newProductId).orElseThrow()

        // First save: clear acceptance probability to signal "computing"
        proposalLineRepo.save(pl.copy(
            selectedProduct = newProduct,
            isOverridden = true,
            acceptanceProbability = null,
            llmReasoning = null,
            updatedAt = Instant.now()
        ))

        // Fetch match result and alts for context (failure is non-fatal)
        val mr = try { matchResultRepo.findByLineId(lineId) } catch (_: Exception) { null }
        val alts: List<EnrichedAlt> = parseAlts(mr?.alternatives)

        // Re-estimate acceptance for new product
        val line = pl.line
        val estimate = computeEstimate(line, mr, newProduct, alts)

        // Compute match score for overridden product
        val attrDefs = attrDefRepo.findByProductClassId(line.productClass?.id ?: 0L)
        val lineAttrs: Map<String, Any> = try { mapper.readValue(line.attributes) } catch (_: Exception) { emptyMap() }
        val productAttrs: Map<String, Any> = try { mapper.readValue(newProduct.attributes) } catch (_: Exception) { emptyMap() }
        val defMap = attrDefs.associateBy { it.name }
        val compliant = lineAttrs.count { (k, req) ->
            val def = defMap[k] ?: return@count false
            val offered = productAttrs[k] ?: return@count false
            evalCompliant(def, req, offered)
        }
        val newMatchScore = if (lineAttrs.isEmpty()) BigDecimal.ZERO
        else (compliant * 100 / lineAttrs.size).toBigDecimal()

        // Second save: full update with computed values
        proposalLineRepo.save(pl.copy(
            selectedProduct = newProduct,
            isOverridden = true,
            matchScore = newMatchScore,
            acceptanceProbability = estimate?.probability?.toBigDecimal(),
            llmReasoning = estimate?.reasoning,
            updatedAt = Instant.now()
        ))

        // Recompute proposal rollup
        val allLines = proposalLineRepo.findByProposalId(proposalId)
        val avgAcceptance = allLines.mapNotNull { it.acceptanceProbability }
            .takeIf { it.isNotEmpty() }
            ?.let { list -> list.reduce(BigDecimal::add).divide(BigDecimal(list.size), 2, RoundingMode.HALF_UP) }
        val avgScore = allLines.mapNotNull { it.matchScore }
            .takeIf { it.isNotEmpty() }
            ?.let { list -> list.reduce(BigDecimal::add).divide(BigDecimal(list.size), 2, RoundingMode.HALF_UP) }
        val isComplete = allLines.none { it.selectedProduct == null || it.acceptanceProbability == BigDecimal.ZERO }
        proposalRepo.save(proposal.copy(
            acceptanceRate = avgAcceptance,
            matchScore = avgScore,
            isComplete = isComplete,
            updatedAt = Instant.now()
        ))
    }

    private fun evalCompliant(def: AttributeDef, required: Any, offered: Any): Boolean {
        fun toDouble(v: Any) = when (v) {
            is Number -> v.toDouble()
            else -> v.toString().toDoubleOrNull()
        }
        return when (def.matchOp) {
            "eq" -> when (def.datatype) {
                "numeric" -> {
                    val r = toDouble(required) ?: return false
                    val o = toDouble(offered) ?: return false
                    kotlin.math.abs(r - o) < 1e-9
                }
                else -> required.toString().trim().equals(offered.toString().trim(), ignoreCase = true)
            }
            "gte" -> {
                val r = toDouble(required) ?: return false
                val o = toDouble(offered) ?: return false
                o >= r
            }
            "lte" -> {
                val r = toDouble(required) ?: return false
                val o = toDouble(offered) ?: return false
                o <= r
            }
            else -> false
        }
    }

    fun getProposals(tenderId: Long): List<ProposalDto> {
        return proposalRepo.findByTenderId(tenderId).map { proposal ->
            val pLines = proposalLineRepo.findByProposalId(proposal.id)
            val lineDtos = pLines.map { pl ->
                val mr = matchResultRepo.findByLineId(pl.line.id)
                val alts: List<EnrichedAlt> = parseAlts(mr?.alternatives)
                val sp = pl.selectedProduct?.let { p ->
                    val price = productPriceRepo.findById(p.id).orElse(null)
                    val attrs: Map<String, Any> = try { mapper.readValue(p.attributes) } catch (_: Exception) { emptyMap() }
                    SelectedProductDto(
                        id = p.id,
                        name = p.name,
                        mpn = p.mpn,
                        price = price?.price,
                        currency = price?.currency ?: "JOD",
                        supplierName = p.supplier.name,
                        description = attrs["description"] as? String,
                        manualLink = attrs["manualLink"] as? String
                    )
                }
                ProposalLineDto(
                    lineId = pl.line.id,
                    description = pl.line.description,
                    qty = pl.line.qty,
                    matchScore = pl.matchScore,
                    selectedProduct = sp,
                    acceptanceProbability = pl.acceptanceProbability,
                    llmReasoning = pl.llmReasoning,
                    isOverridden = pl.isOverridden,
                    alternatives = alts
                )
            }
            ProposalDto(
                id = proposal.id,
                variant = proposal.variant,
                status = proposal.status,
                acceptanceRate = proposal.acceptanceRate,
                matchScore = proposal.matchScore,
                isComplete = proposal.isComplete,
                lines = lineDtos
            )
        }
    }
}
