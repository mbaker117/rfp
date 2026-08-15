package com.rfp.job

import com.rfp.domain.CrawlRunStatus
import com.rfp.repository.CrawlRunRepository
import com.rfp.service.crawl.BatchOutcome
import com.rfp.service.crawl.CrawlCoordinator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.Executor

/**
 * Scheduled job that picks up QUEUED crawl runs and drives them batch-by-batch
 * until they reach a terminal state (COMPLETE, PARTIAL, CANCELLED, FAILED).
 *
 * Uses a dedicated [crawlBatchExecutor] thread pool (distinct from [taskExecutor])
 * to avoid blocking RFP matching threads.
 *
 * Recovery: on startup (or every tick) it also attempts to recover abandoned CLAIMED URLs
 * for in-progress runs before kicking off new batches.
 */
@Component
class CrawlBatchJob(
    private val runRepo: CrawlRunRepository,
    private val coordinator: CrawlCoordinator,
    @Qualifier("crawlBatchExecutor") private val executor: Executor,
) {
    companion object {
        private val log = LoggerFactory.getLogger(CrawlBatchJob::class.java)
    }

    /**
     * Poll for QUEUED runs every 5 seconds and drive each on the crawl executor.
     * Also recovers abandoned claims for CRAWLING runs (worker-restart safety).
     */
    @Scheduled(fixedDelay = 5_000)
    fun driveQueuedRuns() {
        val activeStatuses = listOf(CrawlRunStatus.QUEUED, CrawlRunStatus.CRAWLING)
        val activeRuns = try {
            runRepo.findByStatusIn(activeStatuses)
        } catch (e: Exception) {
            log.error("Failed to query active crawl runs", e)
            return
        }

        for (run in activeRuns) {
            val runId = run.id
            executor.execute {
                // Recover any abandoned claims before processing
                try {
                    coordinator.recoverAbandonedClaims(runId, Instant.now())
                } catch (e: Exception) {
                    log.warn("Claim recovery failed for run $runId", e)
                }
                driveRun(runId)
            }
        }
    }

    private fun driveRun(runId: Long) {
        log.debug("Driving crawl run $runId")
        try {
            var outcome: BatchOutcome
            do {
                outcome = coordinator.processBatch(runId)
                log.debug("Run $runId batch outcome: $outcome")
            } while (outcome == BatchOutcome.MORE_WORK)
            log.info("Run $runId finished with outcome: $outcome")
        } catch (e: Exception) {
            log.error("Unhandled error driving run $runId — run will be retried on next tick", e)
        }
    }
}
