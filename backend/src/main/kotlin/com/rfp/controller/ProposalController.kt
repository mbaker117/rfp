package com.rfp.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.rfp.domain.AttributeDef
import com.rfp.repository.AttributeDefRepository
import com.rfp.repository.ProductPriceRepository
import com.rfp.repository.ProductRepository
import com.rfp.repository.TenderLineRepository
import com.rfp.repository.TenderSupplierRepository
import com.rfp.service.ProposalDto
import com.rfp.service.ProposalService
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

data class OverrideRequest(val productId: Long)

data class SearchResultDto(
    val productId: Long,
    val name: String,
    val mpn: String?,
    val score: Int,
    val price: BigDecimal?,
    val currency: String,
    val supplierName: String
)

@RestController
@RequestMapping("/rfp/{rfpId}/proposals")
class ProposalController(
    private val proposalService: ProposalService,
    private val tenderLineRepo: TenderLineRepository,
    private val productRepo: ProductRepository,
    private val productPriceRepo: ProductPriceRepository,
    private val attrDefRepo: AttributeDefRepository,
    private val tenderSupplierRepo: TenderSupplierRepository
) {
    private val mapper = ObjectMapper().apply { findAndRegisterModules() }

    @PostMapping
    fun generate(@PathVariable rfpId: Long): ResponseEntity<Map<String, String>> {
        proposalService.generateProposals(rfpId)
        return ResponseEntity.ok(mapOf("status" to "generating"))
    }

    @GetMapping
    fun list(@PathVariable rfpId: Long): ResponseEntity<List<ProposalDto>> =
        ResponseEntity.ok(proposalService.getProposals(rfpId))

    @PatchMapping("/{proposalId}/lines/{lineId}")
    fun override(
        @PathVariable rfpId: Long,
        @PathVariable proposalId: Long,
        @PathVariable lineId: Long,
        @RequestBody req: OverrideRequest
    ): ResponseEntity<Map<String, String>> {
        proposalService.overrideLine(proposalId, lineId, req.productId)
        return ResponseEntity.ok(mapOf("status" to "ok"))
    }

    @GetMapping("/{proposalId}/lines/{lineId}/search")
    fun search(
        @PathVariable rfpId: Long,
        @PathVariable proposalId: Long,
        @PathVariable lineId: Long,
        @RequestParam q: String
    ): ResponseEntity<List<SearchResultDto>> {
        val line = tenderLineRepo.findById(lineId).orElseThrow()
        val supplierIds = tenderSupplierRepo.findSupplierIdsByTenderId(rfpId)
        val classId = line.productClass?.id
        val attrDefs = if (classId != null) attrDefRepo.findByProductClassId(classId) else emptyList()
        val lineAttrs: Map<String, Any> = parseJsonMap(line.attributes)

        val page = productRepo.searchByNameOrMpn(q, PageRequest.of(0, 50))
        val filtered = page.content.filter { p ->
            supplierIds.contains(p.supplier.id) && !p.isStale
        }

        val defMap = attrDefs.associateBy { it.name }
        val results = filtered.map { p ->
            val productAttrs: Map<String, Any> = parseJsonMap(p.attributes)
            val compliant = lineAttrs.count { (k, req) ->
                val def = defMap[k] ?: return@count false
                val offered = productAttrs[k] ?: return@count false
                evalCompliant(def, req, offered)
            }
            val score = if (lineAttrs.isEmpty()) 0 else (compliant * 100 / lineAttrs.size)
            val productPrice = productPriceRepo.findById(p.id).orElse(null)
            SearchResultDto(
                productId = p.id,
                name = p.name,
                mpn = p.mpn,
                score = score,
                price = productPrice?.price,
                currency = productPrice?.currency ?: "JOD",
                supplierName = p.supplier.name
            )
        }.sortedByDescending { it.score }.take(10)

        return ResponseEntity.ok(results)
    }

    private fun parseJsonMap(json: String): Map<String, Any> = try {
        @Suppress("UNCHECKED_CAST")
        mapper.readValue(json, Map::class.java) as Map<String, Any>
    } catch (_: Exception) {
        emptyMap()
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
}
