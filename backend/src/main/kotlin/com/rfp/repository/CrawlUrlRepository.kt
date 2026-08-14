package com.rfp.repository

import com.rfp.domain.CrawlUrl
import com.rfp.domain.CrawlUrlStatus
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface CrawlUrlRepository : JpaRepository<CrawlUrl, Long>, CrawlUrlRepositoryCustom

interface CrawlUrlRepositoryCustom {
    fun claimBatch(runId: Long, limit: Int, now: Instant): List<CrawlUrl>
}

@Repository
class CrawlUrlRepositoryImpl(
    @PersistenceContext private val entityManager: EntityManager
) : CrawlUrlRepositoryCustom {
    @Transactional
    override fun claimBatch(runId: Long, limit: Int, now: Instant): List<CrawlUrl> {
        require(limit > 0) { "limit must be positive" }
        @Suppress("UNCHECKED_CAST")
        val locked = entityManager.createNativeQuery(
            """
                SELECT * FROM crawl_url
                WHERE crawl_run_id = :runId
                  AND (status = 'PENDING' OR (status = 'RETRY' AND (next_attempt_at IS NULL OR next_attempt_at <= :now)))
                ORDER BY priority DESC, created_at ASC, id ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            CrawlUrl::class.java
        )
            .setParameter("runId", runId)
            .setParameter("now", now)
            .setParameter("limit", limit)
            .resultList as List<CrawlUrl>
        if (locked.isEmpty()) return emptyList()

        entityManager.createQuery(
            """
                UPDATE CrawlUrl url
                SET url.status = :status, url.claimedAt = :now, url.updatedAt = :now
                WHERE url.id IN :ids
            """.trimIndent()
        )
            .setParameter("status", CrawlUrlStatus.CLAIMED)
            .setParameter("now", now)
            .setParameter("ids", locked.map { it.id })
            .executeUpdate()
        entityManager.clear()

        return entityManager.createQuery(
            """
                SELECT url FROM CrawlUrl url
                WHERE url.id IN :ids
                ORDER BY url.priority DESC, url.createdAt ASC, url.id ASC
            """.trimIndent(),
            CrawlUrl::class.java
        )
            .setParameter("ids", locked.map { it.id })
            .resultList
    }
}
