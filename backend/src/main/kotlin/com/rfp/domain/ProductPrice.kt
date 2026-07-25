package com.rfp.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate

@Entity @Table(name = "product_price")
data class ProductPrice(
    @Id
    val productId: Long,
    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    val product: Product,
    val price: BigDecimal? = null,
    val currency: String = "JOD",
    val asOf: LocalDate? = null,
    val sourceFile: String? = null
)
