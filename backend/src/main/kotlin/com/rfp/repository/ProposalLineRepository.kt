package com.rfp.repository

import com.rfp.domain.ProposalLine
import org.springframework.data.jpa.repository.JpaRepository

interface ProposalLineRepository : JpaRepository<ProposalLine, Long> {
    fun findByProposalId(proposalId: Long): List<ProposalLine>
    fun findByProposalIdAndLineId(proposalId: Long, lineId: Long): ProposalLine?
}
