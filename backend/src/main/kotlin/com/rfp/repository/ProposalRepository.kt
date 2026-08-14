package com.rfp.repository

import com.rfp.domain.Proposal
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.transaction.annotation.Transactional

interface ProposalRepository : JpaRepository<Proposal, Long> {
    fun findByTenderId(tenderId: Long): List<Proposal>

    @Transactional
    @Modifying
    fun deleteByTenderId(tenderId: Long)
}
