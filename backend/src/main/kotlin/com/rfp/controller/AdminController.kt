package com.rfp.controller

import com.rfp.job.InstrumentRefreshJob
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin")
class AdminController(private val refreshJob: InstrumentRefreshJob) {

    @PostMapping("/refresh")
    fun triggerRefresh(): ResponseEntity<Map<String, String>> {
        refreshJob.refreshStaleCompanies()
        return ResponseEntity.ok(mapOf("status" to "refresh enqueued"))
    }
}
