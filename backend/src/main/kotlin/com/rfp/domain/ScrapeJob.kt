package com.rfp.domain

import com.rfp.domain.enums.ScrapeStatus
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "scrape_job")
data class ScrapeJob(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    val company: Company,
    @Enumerated(EnumType.STRING)
    var status: ScrapeStatus = ScrapeStatus.PENDING,
    var errorMsg: String? = null,
    var startedAt: Instant? = null,
    var finishedAt: Instant? = null
)
