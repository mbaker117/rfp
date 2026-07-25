package com.rfp.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal

@Entity @Table(name = "tender_line")
data class TenderLine(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tender_id")
    val tender: Tender,
    val lineNo: String? = null,
    val rawText: String,
    val description: String? = null,
    val qty: BigDecimal? = null,
    val qtyUnit: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id")
    val productClass: ProductClass? = null,
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    val attributes: String = "{}",
    val status: String = "extracted"
)
