package com.rfp.controller

import com.rfp.domain.CrawlRun
import com.rfp.domain.CrawlRunStatus
import com.rfp.dto.CompletenessDto
import com.rfp.dto.CrawlCountsDto
import com.rfp.dto.CrawlRunDetailDto
import com.rfp.repository.CrawlRunRepository
import com.rfp.service.crawl.CrawlCoordinator
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/crawl-runs")
@PreAuthorize("hasRole('ADMIN')")
class CrawlController(
    private val runRepo: CrawlRunRepository,
    private val coordinator: CrawlCoordinator
) {

    companion object {
        /** States from which no control action (cancel/resume/retry) makes sense. */
        private val TERMINAL_STATES = setOf(
            CrawlRunStatus.COMPLETE,
            CrawlRunStatus.PARTIAL,
            CrawlRunStatus.FAILED,
            CrawlRunStatus.CANCELLED
        )
    }

    @GetMapping("/{runId}")
    fun getDetail(@PathVariable runId: Long): ResponseEntity<CrawlRunDetailDto> {
        val run = runRepo.findById(runId).orElse(null)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(run.toDetailDto())
    }

    @PostMapping("/{runId}/resume")
    fun resume(@PathVariable runId: Long): ResponseEntity<Any> {
        val run = runRepo.findById(runId).orElse(null)
            ?: return ResponseEntity.notFound().build()
        // Resuming a fully-completed or already-cancelled-beyond-recovery run is invalid.
        if (run.status == CrawlRunStatus.COMPLETE) {
            return ResponseEntity.status(409).body(mapOf("error" to "Run ${run.id} is already COMPLETE"))
        }
        coordinator.resume(runId)
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/{runId}/cancel")
    fun cancel(@PathVariable runId: Long): ResponseEntity<Any> {
        val run = runRepo.findById(runId).orElse(null)
            ?: return ResponseEntity.notFound().build()
        if (run.status in TERMINAL_STATES) {
            return ResponseEntity.status(409)
                .body(mapOf("error" to "Run ${run.id} is in terminal state ${run.status}; cannot cancel"))
        }
        coordinator.cancel(runId)
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/{runId}/retry-failed")
    fun retryFailed(@PathVariable runId: Long): ResponseEntity<Any> {
        val run = runRepo.findById(runId).orElse(null)
            ?: return ResponseEntity.notFound().build()
        if (run.status !in setOf(CrawlRunStatus.FAILED, CrawlRunStatus.PARTIAL)) {
            return ResponseEntity.status(409)
                .body(mapOf("error" to "Cannot retry-failed from state ${run.status}"))
        }
        coordinator.retryFailed(runId)
        return ResponseEntity.accepted().build()
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private fun CrawlRun.toDetailDto() = CrawlRunDetailDto(
        id = id,
        supplierId = supplier.id,
        status = status.name,
        mode = mode,
        configJson = configJson,
        counts = CrawlCountsDto(
            discovered = discoveredUrlCount,
            fetched = fetchedUrlCount,
            failed = failedUrlCount,
            rejected = rejectedUrlCount,
            retried = retriedUrlCount,
            pending = pendingUrlCount,
            observedProducts = observedProductCount,
            insertedProducts = insertedProductCount,
            updatedProducts = updatedProductCount,
            unchangedProducts = unchangedProductCount,
            staleProducts = staleProductCount
        ),
        completeness = CompletenessDto(
            // Reconciliation is only valid when all URLs have been processed (COMPLETE run)
            canReconcile = status == CrawlRunStatus.COMPLETE,
            score = completenessScore,
            reason = completenessReason
        ),
        batchCount = batchCount,
        checkpointCount = checkpointCount,
        cancellationRequested = cancellationRequested,
        failureCategory = failureCategory,
        failureDetails = failureDetails,
        startedAt = startedAt,
        heartbeatAt = heartbeatAt,
        finishedAt = finishedAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
