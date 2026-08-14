package com.rfp.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "crawl_product_observation")
data class CrawlProductObservation(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "crawl_run_id", nullable = false)
    val run: CrawlRun,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    val supplier: Supplier,
    @Column(nullable = false)
    val identityKey: String,
    @Column(nullable = false, columnDefinition = "text")
    val productName: String,
    val mpn: String? = null,
    val productClassName: String? = null,
    @Column(name = "attributes_json", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    val attributesJson: String = "{}",
    val price: BigDecimal? = null,
    val currency: String? = null,
    @Column(nullable = false, columnDefinition = "text")
    val sourceUrl: String,
    @Column(name = "field_provenance_json", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    val fieldProvenanceJson: String = "{}",
    @Column(nullable = false)
    val extractionMethod: String,
    val confidence: Int? = null,
    val contentHash: String? = null,
    val observedAt: Instant = Instant.now(),
    val createdAt: Instant = Instant.now()
)
