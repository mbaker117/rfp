package com.rfp.dto

import java.math.BigDecimal

// ── Shared ──────────────────────────────────────────────
data class ClassSchema(val name: String, val attributes: List<AttrSchema>)
data class AttrSchema(val name: String, val datatype: String, val canonicalUnit: String?)

// ── Task 1 output ────────────────────────────────────────
data class ParsedProduct(
    val className: String,
    val name: String,
    val mpn: String?,
    val price: BigDecimal?,
    val currency: String = "JOD",
    val attributes: Map<String, Any> = emptyMap()
)

// ── Task 2 output ────────────────────────────────────────
data class ClassDefinition(
    val className: String,
    val attributeDefs: List<AttributeDefDto>
)
data class AttributeDefDto(
    val name: String,
    val label: String,
    val datatype: String,       // numeric, text, bool, enum
    val matchOp: String,        // eq, gte, lte
    val canonicalUnit: String?,
    val allowedValues: List<String> = emptyList()
)

// ── Task 2c: labels and match rules for specs found on a class's products ──
data class AttributeSample(val name: String, val samples: List<String>)
data class AttributeMeta(val name: String, val label: String, val matchOp: String)

// ── Task 2d: spec keys of one class that mean the same thing ──
data class AttributeUsage(val name: String, val products: Int, val samples: List<String>)
data class DuplicateGroup(val canonical: String, val aliases: List<String>, val valueMap: Map<String, String> = emptyMap())

// ── Task 3 output ────────────────────────────────────────
data class ParsedTenderLine(
    val className: String,
    val description: String,
    val qty: BigDecimal?,
    val qtyUnit: String?,
    val attributes: Map<String, Any> = emptyMap()
)
