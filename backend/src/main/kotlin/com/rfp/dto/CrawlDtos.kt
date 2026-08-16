package com.rfp.dto

import java.time.Instant

data class CrawlRunSummaryDto(
    val id: Long,
    val status: String,
    val mode: String,
    val discoveredUrlCount: Int,
    val fetchedUrlCount: Int,
    val failedUrlCount: Int,
    val observedProductCount: Int,
    val insertedProductCount: Int,
    val updatedProductCount: Int,
    val completenessScore: Int?,
    val completenessReason: String?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val createdAt: Instant
)

data class CrawlRunDetailDto(
    val id: Long,
    val supplierId: Long,
    val status: String,
    val mode: String,
    val configJson: String,
    val counts: CrawlCountsDto,
    val completeness: CompletenessDto,
    val batchCount: Int,
    val checkpointCount: Int,
    val cancellationRequested: Boolean,
    val failureCategory: String?,
    val failureDetails: String?,
    val startedAt: Instant?,
    val heartbeatAt: Instant?,
    val finishedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class CrawlCountsDto(
    val discovered: Int,
    val fetched: Int,
    val failed: Int,
    val rejected: Int,
    val retried: Int,
    val pending: Int,
    val observedProducts: Int,
    val insertedProducts: Int,
    val updatedProducts: Int,
    val unchangedProducts: Int,
    val staleProducts: Int
)

data class CompletenessDto(
    val canReconcile: Boolean,
    val score: Int?,
    val reason: String?
)
