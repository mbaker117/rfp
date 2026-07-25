package com.rfp.repository

import com.rfp.domain.TenderLine
import org.springframework.data.jpa.repository.JpaRepository

interface TenderLineRepository : JpaRepository<TenderLine, Long> {
    fun findByTenderId(tenderId: Long): List<TenderLine>
}
