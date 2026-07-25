package com.rfp.repository

import com.rfp.domain.CatalogIngest
import org.springframework.data.jpa.repository.JpaRepository

interface CatalogIngestRepository : JpaRepository<CatalogIngest, Long> {
    fun findBySupplierId(supplierId: Long): List<CatalogIngest>
}
