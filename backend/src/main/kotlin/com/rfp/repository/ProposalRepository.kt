package com.rfp.repository

import com.rfp.domain.Proposal
import org.springframework.data.jpa.repository.JpaRepository

interface ProposalRepository : JpaRepository<Proposal, Long> {
    fun findByTenderId(tenderId: Long): List<Proposal>
    fun deleteByTenderId(tenderId: Long)
}
