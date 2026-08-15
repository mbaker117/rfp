package com.rfp.controller

import com.rfp.job.CatalogRefreshJob
import com.rfp.repository.AppUserRepository
import com.rfp.repository.ProductPriceRepository
import com.rfp.repository.ProductRepository
import com.rfp.repository.TenderRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class UserDto(val id: Long, val username: String, val role: String)
data class ProductDto(
    val id: Long, val name: String, val mpn: String?,
    val supplierId: Long,
    val supplierName: String, val productClass: String?, val isStale: Boolean,
    val source: String?, val attributes: String?,
    val canonicalSourceUrl: String? = null,
    val lastObservedAt: String? = null,
    val extractionMethod: String? = null
)
data class TenderDto(
    val id: Long, val filename: String, val userId: Long,
    val status: String, val createdAt: String
)
data class PageResult<T>(val content: List<T>, val totalElements: Long)

@RestController
@RequestMapping("/admin")
class AdminController(
    private val refreshJob: CatalogRefreshJob,
    private val userRepo: AppUserRepository,
    private val productRepo: ProductRepository,
    private val tenderRepo: TenderRepository,
    private val productPriceRepo: ProductPriceRepository
) {

    @PostMapping("/refresh")
    fun triggerRefresh(): ResponseEntity<Map<String, String>> {
        refreshJob.refresh()
        return ResponseEntity.ok(mapOf("status" to "refresh enqueued"))
    }

    @GetMapping("/users")
    fun listUsers(): List<UserDto> =
        userRepo.findAll().map { UserDto(it.id, it.username, it.role) }

    @DeleteMapping("/users/{id}")
    fun deleteUser(@PathVariable id: Long): ResponseEntity<Any> {
        val tenders = tenderRepo.findAll().filter { it.userId == id }
        if (tenders.isNotEmpty()) {
            return ResponseEntity.status(409).body(mapOf("error" to "User has ${tenders.size} tender(s) and cannot be deleted"))
        }
        userRepo.deleteById(id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/products")
    fun listProducts(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) supplierId: Long?
    ): PageResult<ProductDto> {
        val pageable = PageRequest.of(page, size, Sort.by("name"))
        val result = if (supplierId != null)
            productRepo.searchBySupplierAndNameOrMpn(supplierId, q, pageable)
        else
            productRepo.searchByNameOrMpn(q, pageable)
        return PageResult(
            content = result.content.map { p ->
                val price = productPriceRepo.findById(p.id).orElse(null)
                ProductDto(
                    id = p.id,
                    name = p.name,
                    mpn = p.mpn,
                    supplierId = p.supplier.id,
                    supplierName = p.supplier.name,
                    productClass = p.productClass?.name,
                    isStale = p.isStale,
                    source = p.source,
                    attributes = p.attributes.takeIf { it != "{}" },
                    canonicalSourceUrl = p.canonicalSourceUrl,
                    lastObservedAt = p.lastObservedAt?.toString(),
                    extractionMethod = price?.extractionMethod
                )
            },
            totalElements = result.totalElements
        )
    }

    @GetMapping("/tenders")
    fun listTenders(): List<TenderDto> =
        tenderRepo.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).map { t ->
            TenderDto(t.id, t.filename, t.userId, t.status, t.createdAt.toString())
        }
}
