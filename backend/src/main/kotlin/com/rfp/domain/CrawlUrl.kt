package com.rfp.domain

import jakarta.persistence.*
import java.time.Instant

enum class CrawlUrlStatus { PENDING, CLAIMED, FETCHED, EXTRACTED, RETRY, FAILED, REJECTED, SKIPPED }

enum class CrawlPageType { UNKNOWN, SITEMAP, FEED, API, CATEGORY, LISTING, PRODUCT, DOCUMENT, OTHER }

@Entity
@Table(
    name = "crawl_url",
    uniqueConstraints = [UniqueConstraint(name = "crawl_url_run_normalized_url_uq", columnNames = ["crawl_run_id", "normalized_url"])]
)
data class CrawlUrl(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "crawl_run_id", nullable = false)
    val run: CrawlRun,
    @Column(nullable = false, columnDefinition = "text")
    val originalUrl: String,
    @Column(nullable = false, columnDefinition = "text")
    val normalizedUrl: String,
    @Column(nullable = false)
    val host: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: CrawlUrlStatus,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var pageType: CrawlPageType,
    @Column(nullable = false)
    val depth: Int,
    @Column(nullable = false)
    var priority: Int,
    @Column(columnDefinition = "text")
    val parentUrl: String? = null,
    var attemptCount: Int = 0,
    var nextAttemptAt: Instant? = null,
    var claimedAt: Instant? = null,
    var fetchedAt: Instant? = null,
    var errorCategory: String? = null,
    @Column(columnDefinition = "text")
    var errorDetails: String? = null,
    var httpStatus: Int? = null,
    var contentType: String? = null,
    var contentLength: Long? = null,
    var etag: String? = null,
    var lastModified: String? = null,
    @Column(columnDefinition = "text")
    var canonicalUrl: String? = null,
    var contentHash: String? = null,
    var requiredPartition: Boolean = false,
    var paginationMember: Boolean = false,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
)
