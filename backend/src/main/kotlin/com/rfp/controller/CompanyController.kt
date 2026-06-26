package com.rfp.controller

import com.rfp.service.CompanyResolutionService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class CompanyResolveRequest(val names: List<String>)

@RestController
@RequestMapping("/companies")
class CompanyController(private val resolutionService: CompanyResolutionService) {

    @PostMapping("/resolve")
    fun resolve(@RequestBody req: CompanyResolveRequest): ResponseEntity<Map<String, Any>> {
        val companies = resolutionService.resolveCompanies(req.names)
        return ResponseEntity.ok(mapOf("companies" to companies.map {
            mapOf("id" to it.id, "name" to it.name, "scrapeStatus" to it.scrapeStatus)
        }))
    }
}
