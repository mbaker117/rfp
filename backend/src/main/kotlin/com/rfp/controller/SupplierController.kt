// backend/src/main/kotlin/com/rfp/controller/SupplierController.kt
package com.rfp.controller

import com.rfp.domain.Supplier
import com.rfp.dto.CrawlRunSummaryDto
import com.rfp.repository.CatalogIngestRepository
import com.rfp.repository.CrawlRunRepository
import com.rfp.repository.SupplierRepository
import com.rfp.service.CatalogIngestService
import com.rfp.service.ScrapeService
import com.rfp.service.crawl.CrawlCoordinator
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import kotlin.reflect.KClass

// ---------------------------------------------------------------------------
// Hostname validation
// ---------------------------------------------------------------------------

private val IPV4_PATTERN = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
private val PORT_SUFFIX_PATTERN = Regex(""".*:\d+$""")

/** Returns true if [host] is a plain DNS hostname (no scheme, port, or IP literal). */
fun isValidCrawlHost(host: String): Boolean {
    if (host.isBlank()) return false
    if (host.contains("://")) return false      // URL with scheme
    if (host.startsWith("[")) return false      // IPv6 bracket notation
    if (IPV4_PATTERN.matches(host)) return false // IPv4 literal
    if (PORT_SUFFIX_PATTERN.matches(host)) return false  // port suffix (:N)
    return true
}

@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [PlainHostnamesValidator::class])
annotation class PlainHostnames(
    val message: String = "Each crawlAllowedHosts entry must be a plain DNS hostname (no scheme, port, or IP literal)",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class PlainHostnamesValidator : ConstraintValidator<PlainHostnames, List<String>?> {
    override fun isValid(value: List<String>?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true
        return value.all { isValidCrawlHost(it) }
    }
}

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

data class SupplierRequest(
    val name: String,
    val officialWebsite: String? = null,
    val contactEmail: String? = null,
    val contactPhone: String? = null,
    val country: String? = null,
    val description: String? = null,
    val categories: List<String> = emptyList(),
    @field:PlainHostnames
    val crawlAllowedHosts: List<String> = emptyList(),
    @field:Min(0) @field:Max(10000)
    val crawlThrottleMs: Long? = null,
    @field:Min(1) @field:Max(10)
    val crawlMaxConcurrency: Int? = null,
    @field:Min(1) @field:Max(500)
    val crawlBatchPages: Int? = null,
    @field:Min(1) @field:Max(100000)
    val crawlMaxUrls: Int? = null
)

data class SupplierResponse(
    val id: Long, val name: String, val officialWebsite: String?,
    val contactEmail: String?, val contactPhone: String?,
    val country: String?, val description: String?,
    val categories: List<String>, val scrapeStatus: String,
    val crawlAllowedHosts: List<String> = emptyList(),
    val crawlThrottleMs: Long? = null,
    val crawlMaxConcurrency: Int? = null,
    val crawlBatchPages: Int? = null,
    val crawlMaxUrls: Int? = null
)

fun Supplier.toResponse() = SupplierResponse(
    id, name, officialWebsite, contactEmail, contactPhone,
    country, description, categories.toList(), scrapeStatus,
    crawlAllowedHosts = crawlAllowedHosts.toList(),
    crawlThrottleMs = crawlThrottleMs,
    crawlMaxConcurrency = crawlMaxConcurrency,
    crawlBatchPages = crawlBatchPages,
    crawlMaxUrls = crawlMaxUrls
)

data class IngestDto(
    val id: Long, val kind: String, val filename: String?,
    val status: String, val startedAt: String?, val finishedAt: String?,
    val errorMsg: String?, val itemsFound: Int?, val stepLog: String?
)

// ---------------------------------------------------------------------------
// Controller
// ---------------------------------------------------------------------------

@RestController
@RequestMapping("/suppliers")
class SupplierController(
    private val repo: SupplierRepository,
    private val catalogIngestRepo: CatalogIngestRepository,
    private val crawlRunRepo: CrawlRunRepository,
    private val crawlCoordinator: CrawlCoordinator
) {

    @Autowired
    private lateinit var catalogIngestService: CatalogIngestService

    @Autowired
    private lateinit var scrapeService: ScrapeService

    @PostMapping
    fun register(@Valid @RequestBody req: SupplierRequest): ResponseEntity<SupplierResponse> {
        val existing = repo.findByNameIgnoreCase(req.name.trim())
        if (existing != null) {
            val updated = if (req.officialWebsite != null && existing.officialWebsite == null)
                repo.save(existing.copy(officialWebsite = req.officialWebsite))
            else existing
            return ResponseEntity.ok(updated.toResponse())
        }
        val saved = repo.save(
            Supplier(
                name = req.name.trim(),
                officialWebsite = req.officialWebsite,
                contactEmail = req.contactEmail,
                contactPhone = req.contactPhone,
                country = req.country,
                description = req.description,
                categories = req.categories.toTypedArray(),
                crawlAllowedHosts = req.crawlAllowedHosts.toTypedArray(),
                crawlThrottleMs = req.crawlThrottleMs,
                crawlMaxConcurrency = req.crawlMaxConcurrency,
                crawlBatchPages = req.crawlBatchPages,
                crawlMaxUrls = req.crawlMaxUrls
            )
        )
        return ResponseEntity.ok(saved.toResponse())
    }

    @GetMapping
    fun list(): ResponseEntity<List<SupplierResponse>> =
        ResponseEntity.ok(repo.findAll().map { it.toResponse() })

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ResponseEntity<SupplierResponse> {
        val s = repo.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(s.toResponse())
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody req: SupplierRequest): ResponseEntity<SupplierResponse> {
        val existing = repo.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        val updated = repo.save(
            existing.copy(
                name = req.name,
                officialWebsite = req.officialWebsite ?: existing.officialWebsite,
                contactEmail = req.contactEmail ?: existing.contactEmail,
                contactPhone = req.contactPhone ?: existing.contactPhone,
                country = req.country ?: existing.country,
                description = req.description ?: existing.description,
                categories = if (req.categories.isNotEmpty()) req.categories.toTypedArray() else existing.categories,
                crawlAllowedHosts = if (req.crawlAllowedHosts.isNotEmpty()) req.crawlAllowedHosts.toTypedArray() else existing.crawlAllowedHosts,
                crawlThrottleMs = req.crawlThrottleMs ?: existing.crawlThrottleMs,
                crawlMaxConcurrency = req.crawlMaxConcurrency ?: existing.crawlMaxConcurrency,
                crawlBatchPages = req.crawlBatchPages ?: existing.crawlBatchPages,
                crawlMaxUrls = req.crawlMaxUrls ?: existing.crawlMaxUrls
            )
        )
        return ResponseEntity.ok(updated.toResponse())
    }

    @PostMapping("/{id}/catalog/upload", consumes = ["multipart/form-data"])
    fun uploadCatalog(
        @PathVariable id: Long,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("kind", defaultValue = "admin_upload") kind: String,
        auth: Authentication
    ): ResponseEntity<Map<String, Any>> {
        repo.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        val ext = file.originalFilename?.substringAfterLast('.', "")?.lowercase() ?: "bin"
        if (ext !in setOf("pdf", "docx", "doc", "xlsx", "xls"))
            return ResponseEntity.badRequest().body(mapOf("error" to "Unsupported file type"))
        catalogIngestService.ingestFile(id, file.bytes, ext, kind)
        return ResponseEntity.ok(mapOf("supplierId" to id, "status" to "ingest_started"))
    }

    @PostMapping("/{id}/catalog/scrape")
    fun triggerScrape(@PathVariable id: Long): ResponseEntity<Map<String, Any?>> {
        val supplier = repo.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        return if (supplier.officialWebsite != null) {
            // Adaptive crawler path: enqueue a resumable crawl run
            val runId = crawlCoordinator.enqueue(id)
            ResponseEntity.ok(mapOf("runId" to runId, "status" to "queued"))
        } else {
            // Legacy fallback: fire-and-forget scrape (no crawl run ID)
            scrapeService.runScrapeJobAsync(id)
            ResponseEntity.ok(mapOf<String, Any?>("runId" to null, "status" to "started"))
        }
    }

    @GetMapping("/{id}/ingests")
    fun listIngests(
        @PathVariable id: Long,
        @RequestHeader("Authorization") auth: String
    ): ResponseEntity<List<IngestDto>> {
        val ingests = catalogIngestRepo.findBySupplierId(id)
        return ResponseEntity.ok(
            ingests.map {
                IngestDto(
                    it.id, it.kind, it.filename, it.status,
                    it.startedAt?.toString(), it.finishedAt?.toString(),
                    it.errorMsg, it.itemsFound, it.stepLog
                )
            }
        )
    }

    @GetMapping("/{id}/crawl-runs")
    fun listCrawlRuns(@PathVariable id: Long): ResponseEntity<List<CrawlRunSummaryDto>> {
        repo.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        val runs = crawlRunRepo.findTop100BySupplierIdOrderByCreatedAtDesc(id)
        return ResponseEntity.ok(runs.map { run ->
            CrawlRunSummaryDto(
                id = run.id,
                status = run.status.name,
                mode = run.mode,
                discoveredUrlCount = run.discoveredUrlCount,
                fetchedUrlCount = run.fetchedUrlCount,
                failedUrlCount = run.failedUrlCount,
                observedProductCount = run.observedProductCount,
                insertedProductCount = run.insertedProductCount,
                updatedProductCount = run.updatedProductCount,
                completenessScore = run.completenessScore,
                completenessReason = run.completenessReason,
                startedAt = run.startedAt,
                finishedAt = run.finishedAt,
                createdAt = run.createdAt
            )
        })
    }
}
