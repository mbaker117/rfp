package com.rfp.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "instrument")
data class Instrument(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    val company: Company,
    val description: String,
    val normalizedName: String,
    var manualLink: String? = null,
    var price: BigDecimal? = null,
    val currency: String = "JOD",
    @Column(columnDefinition = "jsonb")
    var rawData: String? = null,
    var isStale: Boolean = false,
    var llmCacheKey: String? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
)
