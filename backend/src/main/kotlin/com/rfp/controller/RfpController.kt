package com.rfp.controller

import com.rfp.domain.Tender
import com.rfp.repository.MatchResultRepository
import com.rfp.repository.TenderRepository
import com.rfp.repository.TenderLineRepository
import com.rfp.service.ReportService
import com.rfp.service.TenderExtractionService
import com.rfp.service.MatchingEngineService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/rfp")
class RfpController(
    private val tenderRepo: TenderRepository,
    private val tenderLineRepo: TenderLineRepository,
    private val matchResultRepo: MatchResultRepository,
    private val extractionService: TenderExtractionService,
    private val matchingService: MatchingEngineService,
    private val reportService: ReportService
) {
    @PostMapping("/upload", consumes = ["multipart/form-data"])
    fun upload(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("supplierIds", required = false, defaultValue = "") supplierIds: List<Long>,
        auth: Authentication
    ): ResponseEntity<Map<String, Any>> {
        val userId = auth.principal as? Long ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val ext = file.originalFilename?.substringAfterLast('.', "")?.lowercase() ?: ""
        if (ext !in setOf("pdf", "docx", "doc", "xlsx", "xls"))
            return ResponseEntity.badRequest().body(mapOf("error" to "Unsupported file type"))

        val tender = tenderRepo.save(Tender(userId = userId, filename = file.originalFilename ?: "upload", fileType = ext))
        extractionService.extract(tender.id, file.bytes, ext, supplierIds)
        return ResponseEntity.ok(mapOf("rfpId" to tender.id))
    }

    @PostMapping("/{id}/match")
    fun match(@PathVariable id: Long): ResponseEntity<Map<String, Any>> {
        tenderRepo.findById(id).orElseThrow { NoSuchElementException("Tender $id not found") }
        matchingService.matchAsync(id)
        return ResponseEntity.ok(mapOf("jobId" to "rfp-$id-match"))
    }

    @GetMapping("/{id}/report")
    fun report(@PathVariable id: Long): ResponseEntity<Map<String, Any?>> {
        val tender = tenderRepo.findById(id).orElseThrow { NoSuchElementException("Tender $id not found") }
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
        return ResponseEntity.ok(mapOf("rfpId" to tender.id, "status" to tender.status, "items" to results))
    }

    @GetMapping("/{id}/report/export")
    fun export(@PathVariable id: Long, @RequestParam format: String): ResponseEntity<ByteArray> =
        when (format.lowercase()) {
            "xlsx" -> ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=report-$id.xlsx")
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(reportService.exportXlsx(id))
            "pdf" -> ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=report-$id.pdf")
                .header("Content-Type", "application/pdf")
                .body(reportService.exportPdf(id))
            else -> ResponseEntity.badRequest().build()
        }
}
