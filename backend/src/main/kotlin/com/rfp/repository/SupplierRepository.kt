package com.rfp.repository

import com.rfp.domain.Supplier
import org.springframework.data.jpa.repository.JpaRepository

interface SupplierRepository : JpaRepository<Supplier, Long> {
    fun findByNameIgnoreCase(name: String): Supplier?
    fun findByLastScrapedAtBefore(cutoff: java.time.Instant): List<Supplier>
}
