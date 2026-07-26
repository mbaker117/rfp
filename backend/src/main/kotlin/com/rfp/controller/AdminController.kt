package com.rfp.controller

import com.rfp.job.CatalogRefreshJob
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin")
class AdminController(private val refreshJob: CatalogRefreshJob) {

    @PostMapping("/refresh")
    fun triggerRefresh(): ResponseEntity<Map<String, String>> {
        refreshJob.refreshStaleSuppliers()
        return ResponseEntity.ok(mapOf("status" to "refresh enqueued"))
    }
}
