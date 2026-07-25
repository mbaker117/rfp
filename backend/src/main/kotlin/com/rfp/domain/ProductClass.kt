package com.rfp.domain

import jakarta.persistence.*
import java.time.Instant

@Entity @Table(name = "product_class")
data class ProductClass(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val name: String,
    val autoCreated: Boolean = false,
    val createdAt: Instant = Instant.now()
)
