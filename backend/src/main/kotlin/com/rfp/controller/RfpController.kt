package com.rfp.controller

import com.rfp.domain.Tender
import com.rfp.domain.TenderSupplier
import com.rfp.domain.TenderSupplierId
import com.rfp.repository.*
import com.rfp.service.MatchingEngineService
import com.rfp.service.ReportService
import com.rfp.service.TenderExtractionService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.time.Instant

data class TenderSummaryDto(
    val id: Long,
    val filename: String,
    val status: String,
    val createdAt: Instant,
    val lineCount: Int,
    val proposalCount: Int,
    val supplierIds: List<Long>
)

data class MatchRequestBody(val supplierIds: List<Long>? = null)

@RestController
@RequestMapping("/rfp")
class RfpController(
    private val tenderRepo: TenderRepository,
    private val tenderLineRepo: TenderLineRepository,
    private val matchResultRepo: MatchResultRepository,
    private val extractionService: TenderExtractionService,
    private val matchingService: MatchingEngineService,
    private val reportService: ReportService,
    private val proposalRepo: ProposalRepository,
    private val proposalLineRepo: ProposalLineRepository,
    private val tenderSupplierRepo: TenderSupplierRepository,
    private val supplierRepo: SupplierRepository
) {
    @PostMapping("/upload", consumes = ["multipart/form-data"])
    fun upload(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("supplierIds", required = false, defaultValue = "") supplierIds: List<Long>,
        auth: Authentication
    ): ResponseEntity<Map<String, Any>> {
        val userId = auth.name.toLongOrNull() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val ext = file.originalFilename?.substringAfterLast('.', "")?.lowercase() ?: ""
        if (ext !in setOf("pdf", "docx", "doc", "xlsx", "xls"))
            return ResponseEntity.badRequest().body(mapOf("error" to "Unsupported file type"))

        val tender = tenderRepo.save(Tender(userId = userId, filename = file.originalFilename ?: "upload", fileType = ext))
        extractionService.extract(tender.id, file.bytes, ext, supplierIds)
        return ResponseEntity.ok(mapOf("rfpId" to tender.id))
    }

    @GetMapping
    fun list(auth: Authentication): ResponseEntity<List<TenderSummaryDto>> {
        val userId = auth.name.toLongOrNull() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val tenders = tenderRepo.findByUserIdOrderByCreatedAtDesc(userId)
        val summaries = tenders.map { t ->
            TenderSummaryDto(
                id = t.id,
                filename = t.filename,
                status = t.status,
                createdAt = t.createdAt,
                lineCount = tenderLineRepo.findByTenderId(t.id).size,
                proposalCount = proposalRepo.findByTenderId(t.id).size,
                supplierIds = tenderSupplierRepo.findSupplierIdsByTenderId(t.id)
            )
        }
        return ResponseEntity.ok(summaries)
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long, auth: Authentication): ResponseEntity<Void> {
        val userId = auth.name.toLongOrNull() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val tender = tenderRepo.findById(id).orElseThrow { NoSuchElementException("Tender $id not found") }
        if (tender.userId != userId) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()

        proposalRepo.findByTenderId(id).forEach { proposalLineRepo.deleteByProposalId(it.id) }
        proposalRepo.deleteByTenderId(id)
        matchResultRepo.deleteByLineTenderId(id)
        tenderSupplierRepo.deleteByTenderId(id)
        tenderLineRepo.deleteByTenderId(id)
        tenderRepo.deleteById(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/match")
    fun match(
        @PathVariable id: Long,
        @RequestBody(required = false) body: MatchRequestBody?,
        auth: Authentication
    ): ResponseEntity<Map<String, Any>> {
        val userId = auth.name.toLongOrNull() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val tender = tenderRepo.findById(id).orElseThrow { NoSuchElementException("Tender $id not found") }
        if (tender.userId != userId) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()

        val newSupplierIds = body?.supplierIds
        if (!newSupplierIds.isNullOrEmpty()) {
            tenderSupplierRepo.deleteByTenderId(id)
            newSupplierIds.forEach { sid ->
                tenderSupplierRepo.save(
                    TenderSupplier(
                        id = TenderSupplierId(id, sid),
                        tender = tender,
                        supplier = supplierRepo.getReferenceById(sid)
                    )
                )
            }
        }
        proposalRepo.findByTenderId(id).forEach { proposalLineRepo.deleteByProposalId(it.id) }
        proposalRepo.deleteByTenderId(id)
        matchResultRepo.deleteByLineTenderId(id)
        tenderRepo.save(tender.copy(status = "pending_match"))
        matchingService.matchAsync(id)
        return ResponseEntity.ok(mapOf("jobId" to "rfp-$id-match"))
    }

    @GetMapping("/{id}/report")
    fun report(@PathVariable id: Long): ResponseEntity<Map<String, Any?>> {
        val tender = tenderRepo.findById(id).orElseThrow { NoSuchElementException("Tender $id not found") }
        val lineCount = tenderLineRepo.findByTenderId(id).size
        val results = matchResultRepo.findByLineTenderId(id).map { r ->
            mapOf(
                "lineId" to r.line.id,
                "description" to r.line.description,
                "qty" to r.line.qty,
                "matchType" to r.matchType,
                "score" to r.score,
                "status" to r.status,
                "matchedProduct" to r.product?.name,
                "mpn" to r.product?.mpn,
                "attributeVerdicts" to r.attributeVerdicts,
                "alternatives" to r.alternatives
            )
        }
        return ResponseEntity.ok(mapOf("rfpId" to tender.id, "status" to tender.status, "lineCount" to lineCount, "items" to results))
    }

    @GetMapping("/{id}/report/export")
    fun export(
        @PathVariable id: Long,
        @RequestParam format: String,
        @RequestParam(required = false) proposalId: Long?
    ): ResponseEntity<ByteArray> =
        when (format.lowercase()) {
            "xlsx" -> ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=report-$id.xlsx")
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(reportService.exportXlsx(id, proposalId))
            "pdf" -> ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=report-$id.pdf")
                .header("Content-Type", "application/pdf")
                .body(reportService.exportPdf(id, proposalId))
            else -> ResponseEntity.badRequest().build()
        }
}
