package com.rfp.domain

import jakarta.persistence.*
import java.time.Instant

@Entity @Table(name = "supplier")
data class Supplier(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val name: String,
    val officialWebsite: String? = null,
    val contactEmail: String? = null,
    val contactPhone: String? = null,
    val country: String? = null,
    val description: String? = null,
    @Column(columnDefinition = "text[]")
    val categories: Array<String> = emptyArray(),
    val scrapeStatus: String = "PENDING",
    val lastScrapedAt: Instant? = null,
    val createdAt: Instant = Instant.now()
) {
    override fun equals(other: Any?) = other is Supplier && id == other.id
    override fun hashCode() = id.hashCode()
}
