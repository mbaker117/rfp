package com.rfp.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

enum class CrawlRunStatus {
    QUEUED, DISCOVERING, CRAWLING, EXTRACTING, RECONCILING, COMPLETE, PARTIAL, FAILED, CANCELLED
}

@Entity
@Table(name = "crawl_run")
data class CrawlRun(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    val supplier: Supplier,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: CrawlRunStatus,
    @Column(nullable = false)
    val mode: String = "ADAPTIVE",
    @Column(name = "config_json", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    val configJson: String,
    var discoveredUrlCount: Int = 0,
    var fetchedUrlCount: Int = 0,
    var failedUrlCount: Int = 0,
    var rejectedUrlCount: Int = 0,
    var retriedUrlCount: Int = 0,
    var pendingUrlCount: Int = 0,
    var observedProductCount: Int = 0,
    var insertedProductCount: Int = 0,
    var updatedProductCount: Int = 0,
    var unchangedProductCount: Int = 0,
    var staleProductCount: Int = 0,
    var completenessScore: Int? = null,
    @Column(columnDefinition = "text")
    var completenessReason: String? = null,
    var batchCount: Int = 0,
    var checkpointCount: Int = 0,
    var cancellationRequested: Boolean = false,
    var failureCategory: String? = null,
    @Column(columnDefinition = "text")
    var failureDetails: String? = null,
    var startedAt: Instant? = null,
    var heartbeatAt: Instant? = null,
    var finishedAt: Instant? = null,
    /** Set when reconciliation completes; used as an idempotency guard so re-runs are no-ops. */
    var reconciledAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
)
