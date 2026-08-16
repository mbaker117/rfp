package com.rfp.job

import com.rfp.domain.CrawlRunStatus
import com.rfp.repository.CrawlRunRepository
import com.rfp.service.crawl.BatchOutcome
import com.rfp.service.crawl.CrawlCoordinator
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentSkipListSet
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
        /** How long to pause between processBatch calls when only RETRY URLs with future timestamps remain. */
        private const val RETRY_WAIT_MS = 30_000L
    }

    /** Tracks which run IDs currently have a driver thread executing, to prevent duplicate submissions. */
    private val inFlightRuns = ConcurrentSkipListSet<Long>()

    /**
     * Poll for QUEUED runs every 5 seconds and drive each on the crawl executor.
     * Also recovers abandoned claims for CRAWLING runs (worker-restart safety).
     *
     * ShedLock prevents concurrent execution across multiple instances. [lockAtMostFor]
     * is set to PT14M — slightly less than the batch duration ceiling (PT15M) — so a
     * crashed instance does not block successors for longer than one batch cycle.
     */
    @Scheduled(fixedDelay = 5_000)
    @SchedulerLock(name = "driveQueuedRuns", lockAtMostFor = "PT14M")
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
            // Skip if a driver thread for this run is already executing (C3 — prevent duplicate threads)
            if (!inFlightRuns.add(runId)) {
                log.debug("Run $runId already being driven; skipping this tick")
                continue
            }
            executor.execute {
                try {
                    // Recover any abandoned claims before processing
                    try {
                        coordinator.recoverAbandonedClaims(runId, Instant.now())
                    } catch (e: Exception) {
                        log.warn("Claim recovery failed for run $runId", e)
                    }
                    driveRun(runId)
                } finally {
                    inFlightRuns.remove(runId)
                }
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
                if (outcome == BatchOutcome.WAITING) {
                    // Only RETRY URLs with future nextAttemptAt remain — sleep to avoid a busy-loop.
                    log.debug("Run $runId: only retry-scheduled URLs remain; sleeping ${RETRY_WAIT_MS}ms")
                    Thread.sleep(RETRY_WAIT_MS)
                }
            } while (outcome == BatchOutcome.MORE_WORK || outcome == BatchOutcome.WAITING)
            log.info("Run $runId finished with outcome: $outcome")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.info("Run $runId drive thread interrupted")
        } catch (e: Exception) {
            log.error("Unhandled error driving run $runId — run will be retried on next tick", e)
        }
    }
}
