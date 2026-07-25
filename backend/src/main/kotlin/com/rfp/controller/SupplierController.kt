// backend/src/main/kotlin/com/rfp/controller/SupplierController.kt
package com.rfp.controller

import com.rfp.domain.Supplier
import com.rfp.repository.SupplierRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

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
}
