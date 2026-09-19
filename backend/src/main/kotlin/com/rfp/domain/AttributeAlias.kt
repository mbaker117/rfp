package com.rfp.domain

import jakarta.persistence.*

/** Another name a class's spec was extracted under; see V13__attribute_aliases.sql. */
@Entity @Table(name = "attribute_alias")
data class AttributeAlias(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "class_id")
    val classId: Long,
    val alias: String,
    val canonicalName: String
)
