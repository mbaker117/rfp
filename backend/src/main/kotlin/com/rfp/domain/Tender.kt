package com.rfp.domain

import jakarta.persistence.*
import java.time.Instant

@Entity @Table(name = "tender")
data class Tender(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val userId: Long,
    val filename: String,
    val fileType: String,
    val status: String = "uploading",
    val createdAt: Instant = Instant.now()
)
