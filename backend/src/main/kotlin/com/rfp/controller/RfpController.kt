package com.rfp.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.rfp.domain.RequiredInstrument
import com.rfp.domain.RfpRequest
import com.rfp.domain.enums.RfpStatus
import com.rfp.repository.RequiredInstrumentRepository
import com.rfp.repository.RfpRequestRepository
import com.rfp.service.DocumentParsingService
import com.rfp.service.LlmService
import com.rfp.service.MatchingService
import com.rfp.service.ReportService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

data class UploadResponse(val rfpId: Long)
data class MatchJobResponse(val jobId: String)

@RestController
@RequestMapping("/rfp")
class RfpController(
    private val rfpRepo: RfpRequestRepository,
    private val reqInstrRepo: RequiredInstrumentRepository,
    private val parsingService: DocumentParsingService,
    private val llmService: LlmService,
    private val matchingService: MatchingService,
    private val reportService: ReportService
) {
    private val mapper = ObjectMapper()

    @PostMapping("/upload", consumes = ["multipart/form-data"])
    fun upload(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("companyIds", required = false, defaultValue = "") companyIds: List<Long>,
        auth: Authentication
    ): ResponseEntity<UploadResponse> {
        val userId = auth.principal as Long
        val originalName = file.originalFilename ?: "upload"
        val ext = originalName.substringAfterLast('.', "pdf")

        val rfp = rfpRepo.save(
            RfpRequest(userId = userId, originalFilename = originalName, fileType = ext, status = RfpStatus.EXTRACTING)
        )

        val text = parsingService.extractText(file.bytes, ext)
        val requirements = llmService.extractRequirements(text)

        requirements.forEach { req ->
            reqInstrRepo.save(
                RequiredInstrument(
                    rfpRequest = rfp,
                    rawText = req.rawText,
                    extractedSpec = mapper.writeValueAsString(mapOf("name" to req.name, "specs" to req.specs))
                )
            )
        }

        rfpRepo.save(rfp.copy(status = RfpStatus.UPLOADED))
        return ResponseEntity.ok(UploadResponse(rfp.id))
    }

    @PostMapping("/{id}/match")
    fun match(
        @PathVariable id: Long,
        @RequestBody body: Map<String, List<Long>>
    ): ResponseEntity<MatchJobResponse> {
        rfpRepo.findById(id).orElseThrow { NoSuchElementException("RFP $id not found") }
        val companyIds = body["companyIds"] ?: emptyList()
        matchingService.matchAsync(id, companyIds)
        return ResponseEntity.ok(MatchJobResponse("rfp-$id-match"))
    }

    @GetMapping("/{id}/report")
    fun report(@PathVariable id: Long): ResponseEntity<Map<String, Any?>> {
        val rfp = rfpRepo.findById(id).orElseThrow { NoSuchElementException("RFP $id not found") }
        val items = reqInstrRepo.findByRfpRequestId(id).map { r ->
            mapOf(
                "requiredInstrument" to r.rawText,
                "matchedInstrument" to r.matchedInstrument?.normalizedName,
                "manualLink" to r.matchedInstrument?.manualLink,
                "score" to r.matchingScore,
                "status" to r.matchStatus,
                "price" to r.matchedInstrument?.price,
                "currency" to (r.matchedInstrument?.currency ?: "JOD")
            )
        }
        return ResponseEntity.ok(mapOf("rfpId" to rfp.id, "status" to rfp.status, "items" to items))
    }

    @GetMapping("/{id}/report/export")
    fun export(
        @PathVariable id: Long,
        @RequestParam format: String
    ): ResponseEntity<ByteArray> {
        return when (format.lowercase()) {
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
}
