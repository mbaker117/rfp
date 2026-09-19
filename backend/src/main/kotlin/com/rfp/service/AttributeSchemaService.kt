package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rfp.domain.AttributeDef
import com.rfp.dto.AttributeMeta
import com.rfp.dto.AttributeSample
import com.rfp.repository.AttributeDefRepository
import com.rfp.repository.ProductClassRepository
import com.rfp.repository.ProductRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.ceil
import kotlin.math.max

/**
 * Keeps each class's [AttributeDef]s in line with the specs its products actually carry.
 *
 * Definitions were created once, from the first few products of a class, so later specs had none
 * (the matcher then reports them UNVERIFIABLE) and datatypes/units were guessed. This:
 * - adds a definition for every spec on at least [MIN_SHARE] of the class's products
 *   (datatype from the stored values, unit from the key suffix, label + matchOp from one LLM call per class);
 * - corrects a definition whose datatype or unit contradicts the data (its matchOp is kept);
 * - removes definitions for identifiers (item numbers, mpn, links), which are not requirements.
 */
@Service
class AttributeSchemaService(
    private val productClassRepo: ProductClassRepository,
    private val attrDefRepo: AttributeDefRepository,
    private val productRepo: ProductRepository,
    private val llmService: LlmService
) {
    data class SyncResult(val added: Int = 0, val fixed: Int = 0, val removed: Int = 0) {
        operator fun plus(o: SyncResult) = SyncResult(added + o.added, fixed + o.fixed, removed + o.removed)
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper().apply { findAndRegisterModules() }

    fun syncAll(): SyncResult = syncClasses(productClassRepo.findAll().map { it.id })

    fun syncClasses(classIds: Collection<Long>): SyncResult =
        classIds.distinct().fold(SyncResult()) { acc, id -> acc + syncClass(id) }

    fun syncClass(classId: Long): SyncResult {
        val productClass = productClassRepo.findById(classId).orElse(null) ?: return SyncResult()
        val products = productRepo.findByProductClassId(classId)
        val values = mutableMapOf<String, MutableList<Any>>()
        products.forEach { p ->
            val attrs: Map<String, Any?> = runCatching { mapper.readValue<Map<String, Any?>>(p.attributes) }.getOrDefault(emptyMap())
            attrs.forEach { (k, v) -> if (v != null && v.toString().isNotBlank()) values.getOrPut(k) { mutableListOf() }.add(v) }
        }
        val minCount = max(1, ceil(products.size * MIN_SHARE).toInt())
        val defs = attrDefRepo.findByProductClassId(classId)

        val identifierDefs = defs.filter { isIdentifier(it.name) }
        if (identifierDefs.isNotEmpty()) attrDefRepo.deleteAll(identifierDefs)

        var fixed = 0
        defs.filterNot { isIdentifier(it.name) }.forEach { def ->
            val seen = values[def.name]
            // Without product data the datatype can't be checked, but a numeric unit still follows the key suffix.
            val datatype = seen?.let { inferDatatype(it, def.datatype) } ?: def.datatype
            val unit = if (datatype == "numeric") unitFromKey(def.name) ?: def.canonicalUnit else null
            val matchOp = matchOpFor(datatype, def.matchOp)
            if (datatype != def.datatype || unit != def.canonicalUnit || matchOp != def.matchOp) {
                attrDefRepo.save(def.copy(datatype = datatype, canonicalUnit = unit, matchOp = matchOp))
                fixed++
            }
        }

        val defined = defs.map { it.name }.toSet()
        val missing = values.filter { (k, v) -> k !in defined && !isIdentifier(k) && v.size >= minCount }
        if (missing.isNotEmpty()) {
            val meta: Map<String, AttributeMeta> = try {
                llmService.defineAttributes(productClass.name, missing.map { (k, v) ->
                    AttributeSample(k, v.map { it.toString() }.distinct().take(SAMPLES_PER_KEY))
                }).associateBy { it.name }
            } catch (e: Exception) {
                log.warn("Attribute labels/match rules for class '{}' fell back to defaults: {}", productClass.name, e.message)
                emptyMap()
            }
            missing.forEach { (key, seen) ->
                val datatype = inferDatatype(seen, current = null)
                attrDefRepo.save(AttributeDef(
                    productClass = productClass,
                    name = key,
                    label = meta[key]?.label ?: humanize(key),
                    datatype = datatype,
                    matchOp = matchOpFor(datatype, meta[key]?.matchOp ?: "eq"),
                    canonicalUnit = if (datatype == "numeric") unitFromKey(key) else null
                ))
            }
        }
        return SyncResult(added = missing.size, fixed = fixed, removed = identifierDefs.size)
    }

    private fun isIdentifier(key: String) =
        key in IDENTIFIER_KEYS || key.endsWith("_item_no") || key.endsWith("_mpn")

    /** numeric when nearly all values are numbers; bool when all are booleans; enum kept if it was enum; else text. */
    private fun inferDatatype(seen: List<Any>, current: String?): String {
        if (seen.all { it is Boolean || it.toString().lowercase() in setOf("true", "false") }) return "bool"
        val numeric = seen.count { SpecNumbers.parse(it) != null }
        if (numeric >= seen.size * NUMERIC_SHARE) return "numeric"
        return if (current == "enum") "enum" else "text"
    }

    /** gte/lte compare numbers; the matcher reports any other datatype UNVERIFIABLE under them, so use eq. */
    private fun matchOpFor(datatype: String, requested: String): String =
        if (datatype == "numeric" && requested in setOf("eq", "gte", "lte")) requested else "eq"

    private fun unitFromKey(key: String): String? =
        UNIT_SUFFIXES.entries.firstOrNull { key.endsWith("_${it.key}") }?.value

    private fun humanize(key: String) =
        key.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private companion object {
        const val MIN_SHARE = 0.05
        const val NUMERIC_SHARE = 0.9
        const val SAMPLES_PER_KEY = 5
        val IDENTIFIER_KEYS = setOf("item_no", "mpn", "manualLink", "description", "sku")
        /** Key suffix -> canonical unit, using the unit_conversion table's symbols where it has them. */
        val UNIT_SUFFIXES = linkedMapOf(
            "pct" to "%", "rpm" to "rpm", "hp" to "hp", "kw" to "kW", "w" to "W", "kv" to "kV", "v" to "V",
            "ma" to "mA", "a" to "A", "khz" to "kHz", "hz" to "Hz", "c" to "°C", "f" to "°F", "mm" to "mm",
            "cm" to "cm", "m" to "m", "in" to "in", "ft" to "ft", "kg" to "kg", "lb" to "lb", "oz" to "oz",
            "mfd" to "µF", "uf" to "µF", "psi" to "psi", "db" to "dB", "cfm" to "cfm", "gpm" to "gpm"
        )
    }
}
