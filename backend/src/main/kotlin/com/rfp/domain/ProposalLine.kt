package com.rfp.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity @Table(name = "proposal_line")
data class ProposalLine(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposal_id", nullable = false)
    val proposal: Proposal,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_id", nullable = false)
    val line: TenderLine,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_product_id")
    val selectedProduct: Product? = null,
    val matchScore: BigDecimal? = null,
    val acceptanceProbability: BigDecimal? = null,
    val llmReasoning: String? = null,
    val isOverridden: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
