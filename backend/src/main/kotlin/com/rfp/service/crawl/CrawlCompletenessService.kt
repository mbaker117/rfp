package com.rfp.service.crawl

import com.rfp.domain.CrawlRunStatus
import com.rfp.domain.CrawlUrlStatus
import com.rfp.repository.CrawlRunRepository
import com.rfp.repository.CrawlUrlRepository
import org.springframework.stereotype.Service

/**
 * Result of evaluating whether a crawl run is complete enough to drive reconciliation.
 *
 * @param canReconcile `true` iff reconciliation should proceed
 * @param score        completeness score (0–100); mirrors [com.rfp.domain.CrawlRun.completenessScore]
 * @param reason       human-readable explanation when [canReconcile] is `false`; null on success
 */
data class CompletenessResult(
    val canReconcile: Boolean,
    val score: Int,
    val reason: String?,
)

/**
 * Evaluates whether a crawl run is complete enough to safely drive product reconciliation.
 *
 * A run is **complete** (`canReconcile = true`) when ALL of the following hold:
 * 1. Run status is [CrawlRunStatus.COMPLETE]
 * 2. No PENDING, CLAIMED, or RETRY URLs remain for the run
 * 3. The completeness score is null (not tracked) or 100 (budget not exhausted)
 * 4. All required-partition URLs have reached EXTRACTED or SKIPPED
 *
 * Any failure results in `canReconcile = false` with a descriptive [CompletenessResult.reason].
 */
@Service
class CrawlCompletenessService(
    private val runRepo: CrawlRunRepository,
    private val urlRepo: CrawlUrlRepository,
) {

    fun evaluate(runId: Long): CompletenessResult {
        val run = runRepo.findById(runId)
            .orElseThrow { IllegalArgumentException("Run $runId not found") }

        // 1. Status check
        if (run.status != CrawlRunStatus.COMPLETE) {
            return fail(run.completenessScore ?: 0, "Run status is ${run.status}, expected COMPLETE")
        }

        // 2. No in-flight URLs
        val blocking = urlRepo.countByRunIdAndStatusIn(
            runId, BLOCKING_STATUSES
        )
        if (blocking > 0) {
            return fail(run.completenessScore ?: 0, "$blocking PENDING/CLAIMED/RETRY URLs still remain")
        }

        // 3. Budget check (completenessScore null means no budget was tracked)
        val score = run.completenessScore
        if (score != null && score < 100) {
            return fail(score, "Completeness score $score < 100 — a budget ceiling was hit")
        }

        // 4. Required partitions must be done
        val requiredBlocked = urlRepo.countRequiredPartitionNotDone(runId, REQUIRED_DONE_STATUSES)
        if (requiredBlocked > 0) {
            return fail(
                score ?: 100,
                "$requiredBlocked required-partition URLs have not reached EXTRACTED or SKIPPED",
            )
        }

        return CompletenessResult(canReconcile = true, score = score ?: 100, reason = null)
    }

    private fun fail(score: Int, reason: String) =
        CompletenessResult(canReconcile = false, score = score, reason = reason)

    companion object {
        /** URL statuses that prevent reconciliation — work is still in progress. */
        private val BLOCKING_STATUSES = listOf(
            CrawlUrlStatus.PENDING,
            CrawlUrlStatus.CLAIMED,
            CrawlUrlStatus.RETRY,
        )

        /**
         * "Done" statuses accepted for required-partition URLs.
         * FETCHED is included because the coordinator sets status = FETCHED (not EXTRACTED) after
         * a successful fetch; EXTRACTED is reserved for future sub-phases.
         */
        private val REQUIRED_DONE_STATUSES = listOf(
            CrawlUrlStatus.EXTRACTED,
            CrawlUrlStatus.SKIPPED,
            CrawlUrlStatus.FETCHED,
            CrawlUrlStatus.FAILED,
            CrawlUrlStatus.REJECTED,
        )
    }
}
