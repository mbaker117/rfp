package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rfp.domain.*
import com.rfp.repository.*
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import kotlin.math.abs

data class AttributeVerdict(
    val attr: String,
    val required: Any?,
    val offered: Any?,
    val verdict: String   // COMPLIANT, DEVIATION, UNVERIFIABLE
)

data class CandidateScore(val productId: Long, val score: Int, val verdicts: List<AttributeVerdict>)

@Service
open class MatchingEngineService(
    private val tenderRepo: TenderRepository,
    private val tenderLineRepo: TenderLineRepository,
    private val tenderSupplierRepo: TenderSupplierRepository,
    private val productRepo: ProductRepository,
    private val attrDefRepo: AttributeDefRepository,
    private val matchResultRepo: MatchResultRepository
) {
    private val mapper = ObjectMapper().apply { findAndRegisterModules() }

    @Async("taskExecutor")
    open fun matchAsync(tenderId: Long) {
        val tender = tenderRepo.findById(tenderId).orElseThrow()
        if (tender.status == "matching" || tender.status == "done") return
        val supplierIds = tenderSupplierRepo.findSupplierIdsByTenderId(tenderId)
        tenderRepo.save(tender.copy(status = "matching"))
        try {
            matchTender(tender, supplierIds)
            tenderRepo.save(tender.copy(status = "done"))
        } catch (e: Exception) {
            tenderRepo.save(tender.copy(status = "failed"))
            throw e
        }
    }

    fun matchTender(tender: Tender, supplierIds: List<Long>) {
        val lines = tenderLineRepo.findByTenderId(tender.id)
        lines.forEach { line -> matchLine(line, supplierIds) }
    }

    private fun matchLine(line: TenderLine, supplierIds: List<Long>) {
        // Stage 1: exact name match, then exact MPN match (short-circuit: MPN only if name misses)
        val exactByName = line.description?.let {
            productRepo.findBySupplierIdInAndIsStaleAndNameIgnoreCase(supplierIds, false, it)
        } ?: emptyList()
        val exactMatch = exactByName.firstOrNull()
            ?: line.description?.let {
                productRepo.findBySupplierIdInAndIsStaleAndMpnIgnoreCase(supplierIds, false, it)
            }?.firstOrNull()

        // Upsert: reuse existing result row if this line was already matched
        val existingId = matchResultRepo.findByLineId(line.id)?.id ?: 0L

        if (exactMatch != null) {
            matchResultRepo.save(MatchResult(
                id = existingId,
                line = line,
                product = exactMatch,
                matchType = "exact",
                score = 100,
                attributeVerdicts = "[]",
                status = "matched",
                alternatives = "[]"
            ))
            return
        }

        val classId = line.productClass?.id
        if (classId == null) {
            matchResultRepo.save(MatchResult(id = existingId, line = line, product = null,
                matchType = null, score = 0, attributeVerdicts = "[]",
                status = "not_found", alternatives = "[]"))
            return
        }

        val candidates = productRepo.findBySupplierIdInAndIsStaleAndProductClassId(supplierIds, false, classId)
        val attrDefs = attrDefRepo.findByProductClassId(classId)
        val lineAttrs: Map<String, Any> = mapper.readValue(line.attributes)

        val scored = candidates.map { product ->
            scoreCandidate(product, lineAttrs, attrDefs)
        }.filter { it.score > 0 }.sortedByDescending { it.score }

        val best = scored.firstOrNull()
        val alternatives = scored.drop(1).filter { it.score >= 40 }.take(5)

        if (best == null) {
            matchResultRepo.save(MatchResult(id = existingId, line = line, product = null,
                matchType = "spec", score = 0, attributeVerdicts = "[]",
                status = "not_found", alternatives = "[]"))
            return
        }

        val bestProduct = candidates.first { it.id == best.productId }
        val status = when {
            best.score == 100 -> "matched"
            best.score >= 40  -> "partial"
            else              -> "not_found"
        }

        matchResultRepo.save(MatchResult(
            id = existingId,
            line = line,
            product = bestProduct,
            matchType = "spec",
            score = best.score,
            attributeVerdicts = mapper.writeValueAsString(best.verdicts),
            status = status,
            alternatives = mapper.writeValueAsString(alternatives.map { alt ->
                val altProduct = candidates.first { c -> c.id == alt.productId }
                mapOf("productId" to alt.productId, "name" to altProduct.name,
                    "mpn" to altProduct.mpn, "score" to alt.score,
                    "attributeVerdicts" to alt.verdicts)
            })
        ))
    }

    private fun scoreCandidate(
        product: Product,
        lineAttrs: Map<String, Any>,
        attrDefs: List<AttributeDef>
    ): CandidateScore {
        val productAttrs: Map<String, Any> = mapper.readValue(product.attributes)
        val defMap = attrDefs.associateBy { it.name }
        val verdicts = mutableListOf<AttributeVerdict>()

        // I7: iterate over REQUIRED attributes (lineAttrs), not attrDefs
        lineAttrs.forEach { (attrName, required) ->
            val def = defMap[attrName]
            val offered = productAttrs[attrName]

            val verdict = when {
                def == null || offered == null -> "UNVERIFIABLE"
                else -> evalVerdict(def, required, offered)
            }
            verdicts.add(AttributeVerdict(attrName, required, offered, verdict))
        }

        val score = if (verdicts.isEmpty()) 0
        else (verdicts.count { it.verdict == "COMPLIANT" } * 100 / verdicts.size)

        return CandidateScore(product.id, score, verdicts)
    }

    // I2/I3: type-safe comparison; returns UNVERIFIABLE when values can't be parsed
    private fun evalVerdict(def: AttributeDef, required: Any, offered: Any): String {
        return when (def.matchOp) {
            "eq" -> when (def.datatype) {
                "numeric" -> {
                    val r = toDoubleOrNull(required) ?: return "UNVERIFIABLE"
                    val o = toDoubleOrNull(offered) ?: return "UNVERIFIABLE"
                    if (abs(r - o) < 1e-9) "COMPLIANT" else "DEVIATION"
                }
                "bool" -> if (required.toString().equals(offered.toString(), ignoreCase = true))
                    "COMPLIANT" else "DEVIATION"
                else -> if (required.toString().trim().equals(offered.toString().trim(), ignoreCase = true))
                    "COMPLIANT" else "DEVIATION"
            }
            "gte" -> {
                val r = toDoubleOrNull(required) ?: return "UNVERIFIABLE"
                val o = toDoubleOrNull(offered) ?: return "UNVERIFIABLE"
                if (o >= r) "COMPLIANT" else "DEVIATION"
            }
            "lte" -> {
                val r = toDoubleOrNull(required) ?: return "UNVERIFIABLE"
                val o = toDoubleOrNull(offered) ?: return "UNVERIFIABLE"
                if (o <= r) "COMPLIANT" else "DEVIATION"
            }
            else -> "UNVERIFIABLE"
        }
    }

    private fun toDoubleOrNull(v: Any): Double? = when (v) {
        is Number -> v.toDouble()
        else -> v.toString().toDoubleOrNull()
    }
}
