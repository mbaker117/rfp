package com.rfp.repository

import com.rfp.domain.CatalogChunkCache
import org.springframework.data.jpa.repository.JpaRepository

interface CatalogChunkCacheRepository : JpaRepository<CatalogChunkCache, Long> {
    fun findByCacheKey(cacheKey: String): CatalogChunkCache?
}
