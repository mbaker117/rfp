package com.rfp.repository

import com.rfp.domain.Instrument
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface InstrumentRepository : JpaRepository<Instrument, Long> {
    fun findByCompanyIdIn(companyIds: List<Long>): List<Instrument>

    @Query("""
        SELECT i FROM Instrument i
        WHERE i.company.id IN :companyIds
          AND i.isStale = false
          AND (
            lower(i.normalizedName) LIKE lower(concat('%', :keyword, '%'))
            OR lower(i.description) LIKE lower(concat('%', :keyword, '%'))
          )
    """)
    fun findCandidates(companyIds: List<Long>, keyword: String): List<Instrument>
}
