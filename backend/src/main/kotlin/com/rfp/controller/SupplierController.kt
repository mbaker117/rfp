// backend/src/main/kotlin/com/rfp/controller/SupplierController.kt
package com.rfp.controller

import com.rfp.domain.Supplier
import com.rfp.repository.SupplierRepository
import com.rfp.service.CatalogIngestService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

data class SupplierRequest(
    val name: String,
    val officialWebsite: String? = null,
    val contactEmail: String? = null,
    val contactPhone: String? = null,
    val country: String? = null,
    val description: String? = null,
    val categories: List<String> = emptyList()
)

data class SupplierResponse(
    val id: Long, val name: String, val officialWebsite: String?,
    val contactEmail: String?, val contactPhone: String?,
    val country: String?, val description: String?,
    val categories: List<String>, val scrapeStatus: String
)

fun Supplier.toResponse() = SupplierResponse(
    id, name, officialWebsite, contactEmail, contactPhone,
    country, description, categories.toList(), scrapeStatus
)

@RestController
@RequestMapping("/suppliers")
class SupplierController(private val repo: SupplierRepository) {

    @Autowired
    private lateinit var catalogIngestService: CatalogIngestService

    @PostMapping
    fun register(@RequestBody req: SupplierRequest): ResponseEntity<SupplierResponse> {
        val saved = repo.save(Supplier(
            name = req.name,
            officialWebsite = req.officialWebsite,
            contactEmail = req.contactEmail,
            contactPhone = req.contactPhone,
            country = req.country,
            description = req.description,
            categories = req.categories.toTypedArray()
        ))
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
    fun update(@PathVariable id: Long, @RequestBody req: SupplierRequest): ResponseEntity<SupplierResponse> {
        val existing = repo.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        val updated = repo.save(existing.copy(
            name = req.name,
            officialWebsite = req.officialWebsite ?: existing.officialWebsite,
            contactEmail = req.contactEmail ?: existing.contactEmail,
            contactPhone = req.contactPhone ?: existing.contactPhone,
            country = req.country ?: existing.country,
            description = req.description ?: existing.description,
            categories = if (req.categories.isNotEmpty()) req.categories.toTypedArray() else existing.categories
        ))
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
        if (ext !in setOf("pdf","docx","doc","xlsx","xls"))
            return ResponseEntity.badRequest().body(mapOf("error" to "Unsupported file type"))
        catalogIngestService.ingestFile(id, file.bytes, ext, kind)
        return ResponseEntity.ok(mapOf("supplierId" to id, "status" to "ingest_started"))
    }

    @PostMapping("/{id}/catalog/scrape")
    fun triggerScrape(@PathVariable id: Long): ResponseEntity<Map<String, Any>> {
        repo.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        catalogIngestService.ingestScrape(id)
        return ResponseEntity.ok(mapOf("supplierId" to id, "status" to "scrape_started"))
    }
}
