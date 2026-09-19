package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.rfp.domain.*
import com.rfp.repository.*
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
open class TenderExtractionService(
    private val tenderRepo: TenderRepository,
    private val tenderLineRepo: TenderLineRepository,
    private val tenderSupplierRepo: TenderSupplierRepository,
    private val supplierRepo: SupplierRepository,
    private val productClassRepo: ProductClassRepository,
    private val attrDefRepo: AttributeDefRepository,
    private val llmService: LlmService,
    private val unitService: UnitNormalizationService,
    private val docParser: DocumentParsingService,
    private val matchingService: MatchingEngineService,
    private val schemaService: AttributeSchemaService? = null
) {
    private val mapper = ObjectMapper().apply { findAndRegisterModules() }

    @Async("taskExecutor")
    open fun extract(tenderId: Long, bytes: ByteArray, fileType: String, supplierIds: List<Long>) {
        val tender = tenderRepo.findById(tenderId).orElseThrow()
        tenderRepo.save(tender.copy(status = "extracting"))

        supplierIds.forEach { sid ->
            tenderSupplierRepo.save(TenderSupplier(
                id = TenderSupplierId(tenderId, sid),
                tender = tender,
                supplier = supplierRepo.getReferenceById(sid)
            ))
        }

        try {
            val rawText = docParser.extractText(bytes, fileType)
            runExtraction(tender, rawText)
            // C6: chain extraction → matching automatically
            matchingService.matchAsync(tenderId)
        } catch (e: Exception) {
            tenderRepo.save(tender.copy(status = "failed"))
            throw e
        }
    }

    fun runExtraction(tender: Tender, rawText: String) {
        val allClasses = productClassRepo.findAll()
        val knownClasses = allClasses.map { pc ->
            com.rfp.dto.ClassSchema(pc.name, attrDefRepo.findByProductClassId(pc.id).map {
                com.rfp.dto.AttrSchema(it.name, it.datatype, it.canonicalUnit)
            })
        }

        val lines = llmService.parseTenderLines(rawText, knownClasses)
        lines.forEachIndexed { idx, line ->
            val productClass = productClassRepo.findByNameIgnoreCase(line.className)
            val defs = productClass?.let { attrDefRepo.findByProductClassId(it.id) } ?: emptyList()
            // Duplicate spec names were merged per class: use the canonical names products are stored under.
            val requiredAttrs = productClass?.let { schemaService?.canonicalize(it.id, line.attributes) } ?: line.attributes
            val normalizedAttrs = unitService.normalizeAttributes(requiredAttrs, defs)
            val attrsJson = mapper.writeValueAsString(normalizedAttrs)

            tenderLineRepo.save(TenderLine(
                tender = tender,
                lineNo = (idx + 1).toString(),
                rawText = line.description,
                description = line.description,
                qty = line.qty,
                qtyUnit = line.qtyUnit,
                productClass = productClass,
                attributes = attrsJson,
                status = if (productClass == null) "unclassified" else "extracted"
            ))
        }
    }
}
