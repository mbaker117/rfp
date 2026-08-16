package com.rfp.repository

import com.rfp.domain.MatchResult
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.transaction.annotation.Transactional

interface MatchResultRepository : JpaRepository<MatchResult, Long> {
    fun findByLineId(lineId: Long): MatchResult?
    fun findByLineTenderId(tenderId: Long): List<MatchResult>

    @Transactional
    @Modifying
    fun deleteByLineTenderId(tenderId: Long)
}
