package com.rfp.repository

import com.rfp.domain.MatchResult
import org.springframework.data.jpa.repository.JpaRepository

interface MatchResultRepository : JpaRepository<MatchResult, Long> {
    fun findByLineId(lineId: Long): MatchResult?
    fun findByLineTenderId(tenderId: Long): List<MatchResult>
}
