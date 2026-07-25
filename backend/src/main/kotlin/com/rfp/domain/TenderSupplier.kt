package com.rfp.domain

import jakarta.persistence.*

@Entity @Table(name = "tender_supplier")
data class TenderSupplier(
    @EmbeddedId
    val id: TenderSupplierId,
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("tenderId")
    @JoinColumn(name = "tender_id")
    val tender: Tender,
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("supplierId")
    @JoinColumn(name = "supplier_id")
    val supplier: Supplier
)

@Embeddable
data class TenderSupplierId(
    val tenderId: Long = 0,
    val supplierId: Long = 0
) : java.io.Serializable
