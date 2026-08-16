package com.rfp.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity @Table(name = "product")
data class Product(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    val supplier: Supplier,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id")
    val productClass: ProductClass? = null,
    val name: String,
    val mpn: String? = null,
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    val attributes: String = "{}",
    val source: String,
    val isStale: Boolean = false,
    val crawlMissCount: Int = 0,
    val canonicalSourceUrl: String? = null,
    val lastObservedAt: Instant? = null,
    /** Set only by complete crawl reconciliation; never clears a manual [isStale] state. */
    val crawlerStale: Boolean = false,
    /**
     * Normalized identity key computed by [com.rfp.service.crawl.ProductIdentityService].
     * Null for products created before the adaptive crawler was introduced.
     * Scoped per supplier — uniqueness is enforced via the (supplier_id, identity_key) index.
     */
    val identityKey: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
