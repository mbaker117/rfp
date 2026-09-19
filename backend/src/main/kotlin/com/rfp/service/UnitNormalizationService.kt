package com.rfp.service

import com.rfp.domain.AttributeDef
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class UnitNormalizationService(private val jdbc: JdbcTemplate) {

    fun normalize(value: Double, fromUnit: String, toUnit: String): Double {
        if (fromUnit == toUnit) return value
        val row = try {
            jdbc.queryForList(
                "SELECT factor, addend FROM unit_conversion WHERE from_unit = ? AND to_unit = ?",
                fromUnit, toUnit
            ).firstOrNull() ?: return value
        } catch (e: Exception) { return value }
        val factor = (row["factor"] as? Number)?.toDouble() ?: return value
        val addend = (row["addend"] as? Number)?.toDouble() ?: 0.0
        return value * factor + addend
    }

    // Normalize a raw attribute map to canonical units based on attribute definitions.
    // For numeric attributes, looks for a companion "{name}_unit" key in the map.
    fun normalizeAttributes(
        attrs: Map<String, Any>,
        defs: List<AttributeDef>
    ): Map<String, Any> {
        val result = attrs.toMutableMap()
        defs.filter { it.datatype == "numeric" && it.canonicalUnit != null }.forEach { def ->
            val rawValue = attrs[def.name] ?: return@forEach
            val unitKey = "${def.name}_unit"
            val fromUnit = attrs[unitKey]?.toString() ?: def.canonicalUnit!!
            val canonical = def.canonicalUnit!!
            val normalized = normalize(SpecNumbers.parse(rawValue) ?: return@forEach, fromUnit, canonical)
            result[def.name] = normalized
            result.remove(unitKey)
        }
        return result
    }
}
