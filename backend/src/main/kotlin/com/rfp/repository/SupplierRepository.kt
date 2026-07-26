package com.rfp.repository

import com.rfp.domain.Supplier
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface SupplierRepository : JpaRepository<Supplier, Long> {
    fun findByNameIgnoreCase(name: String): Supplier?
    // Includes suppliers that have never been scraped (lastScrapedAt IS NULL)
    fun findByLastScrapedAtBeforeOrLastScrapedAtIsNull(cutoff: Instant): List<Supplier>
}
