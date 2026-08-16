package com.rfp.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity @Table(name = "product_price_history")
data class ProductPriceHistory(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    val product: Product,
    val price: BigDecimal,
    val currency: String = "JOD",
    val recordedAt: Instant = Instant.now(),
    val sourceUrl: String? = null,
    val extractionMethod: String? = null,
    val observedAt: Instant? = null
)
