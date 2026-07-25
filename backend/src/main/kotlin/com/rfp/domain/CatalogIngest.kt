package com.rfp.domain

import jakarta.persistence.*
import java.time.Instant

@Entity @Table(name = "catalog_ingest")
data class CatalogIngest(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    val supplier: Supplier,
    val kind: String,       // company_upload, admin_upload, scrape
    val filename: String? = null,
    val status: String = "PENDING",
    val errorMsg: String? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null
)
