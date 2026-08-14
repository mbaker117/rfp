package com.rfp.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity @Table(name = "proposal")
data class Proposal(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tender_id", nullable = false)
    val tender: Tender,
    val variant: String,
    val acceptanceRate: BigDecimal? = null,
    val matchScore: BigDecimal? = null,
    val isComplete: Boolean = false,
    val status: String = "GENERATING",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
