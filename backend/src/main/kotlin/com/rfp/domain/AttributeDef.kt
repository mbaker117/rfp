package com.rfp.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

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
    val allowedValues: Array<String> = emptyArray(),
    /** JSON object of value translations, e.g. {"Single":"1"}; applied by AttributeSchemaService.canonicalize. */
    @JdbcTypeCode(SqlTypes.JSON)
    val valueAliases: String = "{}"
) {
    override fun equals(other: Any?) = other is AttributeDef && id == other.id
    override fun hashCode() = id.hashCode()
}
