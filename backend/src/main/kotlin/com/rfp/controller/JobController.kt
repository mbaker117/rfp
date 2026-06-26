package com.rfp.controller

import com.rfp.repository.ScrapeJobRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/jobs")
class JobController(private val scrapeJobRepo: ScrapeJobRepository) {

    @GetMapping("/{id}")
    fun status(@PathVariable id: Long): ResponseEntity<Map<String, Any?>> {
        val job = scrapeJobRepo.findById(id).orElseThrow { NoSuchElementException("Job $id not found") }
        return ResponseEntity.ok(mapOf(
            "id" to job.id,
            "status" to job.status,
            "error" to (job.errorMsg ?: "")
        ))
    }
}
