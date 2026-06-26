package com.rfp.repository

import com.rfp.domain.Company
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface CompanyRepository : JpaRepository<Company, Long> {
    fun findByNameIgnoreCase(name: String): Company?
    fun findByOfficialWebsite(url: String): Company?
    fun findByLastScrapedAtBefore(cutoff: Instant): List<Company>
}
