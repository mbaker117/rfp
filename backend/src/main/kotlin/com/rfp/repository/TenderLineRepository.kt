package com.rfp.repository

import com.rfp.domain.TenderLine
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.transaction.annotation.Transactional

interface TenderLineRepository : JpaRepository<TenderLine, Long> {
    fun findByTenderId(tenderId: Long): List<TenderLine>

    @Transactional
    @Modifying
    fun deleteByTenderId(tenderId: Long)
}
