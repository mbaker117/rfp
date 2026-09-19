package com.rfp.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** Cached LLM extraction of one catalog chunk; see V11__catalog_chunk_cache.sql. */
@Entity @Table(name = "catalog_chunk_cache")
data class CatalogChunkCache(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val cacheKey: String,
    @JdbcTypeCode(SqlTypes.JSON)
    val products: String,   // JSON array of ParsedProduct
    val productCount: Int,
    val createdAt: Instant = Instant.now()
)
