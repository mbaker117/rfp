package com.rfp.domain

import jakarta.persistence.*

@Entity @Table(name = "attribute_def")
data class AttributeDef(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id")
    val productClass: ProductClass,
    val name: String,
    val label: String,
    val datatype: String,   // numeric, text, bool, enum
    val matchOp: String,    // eq, gte, lte
    val canonicalUnit: String? = null,
    @Column(columnDefinition = "text[]")
    val allowedValues: Array<String> = emptyArray()
) {
    override fun equals(other: Any?) = other is AttributeDef && id == other.id
    override fun hashCode() = id.hashCode()
}
