package com.rfp.domain

import com.rfp.domain.enums.ScrapeStatus
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "company")
data class Company(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val name: String,
    val officialWebsite: String? = null,
    val country: String = "Jordan",
    @Enumerated(EnumType.STRING)
    var scrapeStatus: ScrapeStatus = ScrapeStatus.PENDING,
    var lastScrapedAt: Instant? = null,
    val createdAt: Instant = Instant.now()
)
