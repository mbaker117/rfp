package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rfp.domain.AttributeAlias
import com.rfp.domain.AttributeDef
import com.rfp.dto.AttributeMeta
import com.rfp.dto.AttributeSample
import com.rfp.dto.AttributeUsage
import com.rfp.dto.ValueUsage
import com.rfp.repository.AttributeAliasRepository
import com.rfp.repository.AttributeDefRepository
import com.rfp.repository.ProductClassRepository
import com.rfp.repository.ProductRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.abs
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
    private val llmService: LlmService,
    private val aliasRepo: AttributeAliasRepository? = null
) {
    data class SyncResult(
        val added: Int = 0, val fixed: Int = 0, val removed: Int = 0, val merged: Int = 0, val values: Int = 0
    ) {
        operator fun plus(o: SyncResult) =
            SyncResult(added + o.added, fixed + o.fixed, removed + o.removed, merged + o.merged, values + o.values)
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper().apply { findAndRegisterModules() }

    /** Admin action: sync every class and look for duplicate spec names and value spellings in each. */
    fun syncAll(): SyncResult = syncClasses(productClassRepo.findAll().map { it.id }, alwaysMerge = true)

    /**
     * After an import: sync the touched classes, and look for duplicate spec names only in classes that just
     * gained definitions (a new name is when a duplicate can appear). Value spellings are checked in every touched
     * class, since any import can bring a new spelling. Classes that changed are synced again.
     */
    fun syncClasses(classIds: Collection<Long>, alwaysMerge: Boolean = false): SyncResult =
        classIds.distinct().fold(SyncResult()) { acc, id ->
            val synced = syncClass(id)
            val merged = if (alwaysMerge || synced.added > 0) mergeUntilStable(id) else 0
            val values = mergeValueSpellings(id)
            val resynced = if (merged + values > 0) syncClass(id) else SyncResult()
            acc + synced + resynced + SyncResult(merged = merged, values = values)
        }

    /** The LLM can miss a group in one pass; repeat until a pass merges nothing (at most [MAX_MERGE_PASSES]). */
    fun mergeUntilStable(classId: Long): Int {
        var total = 0
        repeat(MAX_MERGE_PASSES) {
            val merged = mergeDuplicates(classId)
            if (merged == 0) return total
            total += merged
        }
        return total
    }

    /**
     * Merges spec keys of one class that are the same spec under different names ("phase"/"phases") into the
     * canonical key: moves alias values on every product, translates value spellings, removes the alias
     * definitions and records the aliases for [canonicalize]. The LLM proposes groups; a group is vetoed when its
     * keys disagree on any product that carries both, because then they are different specs. Returns groups merged.
     */
    fun mergeDuplicates(classId: Long): Int {
        val aliasRepo = aliasRepo ?: return 0
        val productClass = productClassRepo.findById(classId).orElse(null) ?: return 0
        val defs = attrDefRepo.findByProductClassId(classId).filterNot { isIdentifier(it.name) }
        if (defs.size < 2) return 0
        val products = productRepo.findByProductClassId(classId)
        val working = products.associate { p -> p.id to readAttributes(p.attributes).toMutableMap() }

        val usage = defs.map { d ->
            val seen = working.values.mapNotNull { it[d.name] }
            AttributeUsage(d.name, seen.size, seen.map { it.toString() }.distinct().take(SAMPLES_PER_KEY + 3))
        }
        val groups = try {
            llmService.findDuplicateAttributes(productClass.name, usage)
        } catch (e: Exception) {
            log.warn("Duplicate spec detection for class '{}' failed: {}", productClass.name, e.message)
            return 0
        }

        val defsByName = defs.associateBy { it.name }
        val knownAliases = aliasRepo.findByClassId(classId).map { it.alias }.toSet()
        val changed = mutableSetOf<Long>()
        var merged = 0
        groups.forEach { g ->
            val canonicalDef = defsByName[g.canonical] ?: return@forEach
            val aliasDefs = g.aliases.mapNotNull { defsByName[it] }
            if (aliasDefs.isEmpty()) return@forEach
            val valueMap = g.valueMap.mapKeys { it.key.trim().lowercase() }
            fun translate(v: Any?): Any? = v?.let { valueMap[it.toString().trim().lowercase()] ?: it }

            val conflict = working.values.any { a ->
                val c = a[g.canonical] ?: return@any false
                g.aliases.any { al -> a[al]?.let { !sameValue(translate(it), translate(c)) } ?: false }
            }
            if (conflict) {
                log.info("Not merging {} into {} for class '{}': they differ on the same products", g.aliases, g.canonical, productClass.name)
                return@forEach
            }

            working.forEach { (id, a) ->
                g.aliases.forEach { al ->
                    if (a.containsKey(al)) {
                        val v = a.remove(al)
                        if (a[g.canonical] == null) a[g.canonical] = v
                        changed += id
                    }
                }
                a[g.canonical]?.let { v -> translate(v).let { t -> if (t != v) { a[g.canonical] = t; changed += id } } }
            }
            if (g.valueMap.isNotEmpty()) {
                val all = readStringMap(canonicalDef.valueAliases) + g.valueMap
                attrDefRepo.save(canonicalDef.copy(valueAliases = mapper.writeValueAsString(all)))
            }
            attrDefRepo.deleteAll(aliasDefs)
            g.aliases.filter { it !in knownAliases }.forEach {
                aliasRepo.save(AttributeAlias(classId = classId, alias = it, canonicalName = g.canonical))
            }
            merged++
        }
        if (changed.isNotEmpty()) {
            productRepo.saveAll(products.filter { it.id in changed }.map {
                it.copy(attributes = mapper.writeValueAsString(working[it.id]), updatedAt = Instant.now())
            })
        }
        return merged
    }

    /**
     * Rewrites spellings of one value within a text spec ("PSC" -> "Permanent Split Capacitor") to one canonical
     * spelling and records them in the def's valueAliases for [canonicalize]. The LLM proposes the mappings from the
     * spec's values; code keeps only mappings between values the spec actually has, rejects two different numbers
     * and follows chains to a final value. Specs with more than [MAX_SPELLING_VALUES] values (frames, ratios) are
     * codes rather than words and are skipped. Returns the number of values mapped.
     */
    fun mergeValueSpellings(classId: Long): Int {
        val productClass = productClassRepo.findById(classId).orElse(null) ?: return 0
        val defs = attrDefRepo.findByProductClassId(classId)
            .filter { it.datatype in setOf("text", "enum") && !isIdentifier(it.name) }
        if (defs.isEmpty()) return 0
        val products = productRepo.findByProductClassId(classId)
        val working = products.associate { p -> p.id to readAttributes(p.attributes).toMutableMap() }

        val specs = defs.mapNotNull { d ->
            val counts = working.values.mapNotNull { a -> a[d.name]?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
                .groupingBy { it }.eachCount()
            if (counts.size < 2 || counts.size > MAX_SPELLING_VALUES) null
            else ValueUsage(d.name, counts.entries.sortedByDescending { it.value }.associate { it.key to it.value })
        }
        if (specs.isEmpty()) return 0
        val proposed = try {
            llmService.findValueSynonyms(productClass.name, specs)
        } catch (e: Exception) {
            log.warn("Value spelling detection for class '{}' failed: {}", productClass.name, e.message)
            return 0
        }

        val defsByName = defs.associateBy { it.name }
        val changed = mutableSetOf<Long>()
        var mapped = 0
        proposed.forEach { (key, raw) ->
            val def = defsByName[key] ?: return@forEach
            val map = resolveSpellings(raw)
            if (map.isEmpty()) return@forEach
            val lookup = map.mapKeys { it.key.lowercase() }
            working.forEach { (id, a) ->
                val v = a[key] ?: return@forEach
                lookup[v.toString().trim().lowercase()]?.let { a[key] = it; changed += id }
            }
            attrDefRepo.save(def.copy(valueAliases = mapper.writeValueAsString(readStringMap(def.valueAliases) + map)))
            log.info("Class '{}', spec '{}': merged value spellings {}", productClass.name, key, map)
            mapped += map.size
        }
        if (changed.isNotEmpty()) {
            productRepo.saveAll(products.filter { it.id in changed }.map {
                it.copy(attributes = mapper.writeValueAsString(working[it.id]), updatedAt = Instant.now())
            })
        }
        return mapped
    }

    /** Follows variant -> canonical chains to a value that is not itself a variant; drops cycles and number clashes. */
    private fun resolveSpellings(raw: Map<String, String>): Map<String, String> =
        raw.mapNotNull { (variant, first) ->
            var target = first
            val seen = mutableSetOf(variant)
            while (target in raw) {
                if (!seen.add(target)) return@mapNotNull null
                target = raw.getValue(target)
            }
            val nv = SpecNumbers.parse(variant)
            val nt = SpecNumbers.parse(target)
            if (nv != null && nt != null && abs(nv - nt) >= 1e-9) null
            else if (variant.equals(target, ignoreCase = true)) null
            else variant to target
        }.toMap()

    /** Translates alias keys and value spellings to the class's canonical form (new products and tender lines). */
    fun canonicalize(classId: Long, attributes: Map<String, Any>): Map<String, Any> {
        val aliases = aliasRepo?.findByClassId(classId)?.associate { it.alias to it.canonicalName }.orEmpty()
        val valueMaps = attrDefRepo.findByProductClassId(classId)
            .filter { it.valueAliases.isNotBlank() && it.valueAliases != "{}" }
            .associate { d -> d.name to readStringMap(d.valueAliases).mapKeys { it.key.trim().lowercase() } }
        if (aliases.isEmpty() && valueMaps.isEmpty()) return attributes
        val out = LinkedHashMap<String, Any>()
        attributes.forEach { (k, v) ->
            val key = aliases[k] ?: k
            if (key == k || !out.containsKey(key)) out[key] = v
        }
        valueMaps.forEach { (k, m) -> out[k]?.let { v -> m[v.toString().trim().lowercase()]?.let { out[k] = it } } }
        return out
    }

    private fun sameValue(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return a == b
        val na = SpecNumbers.parse(a)
        val nb = SpecNumbers.parse(b)
        if (na != null && nb != null) return abs(na - nb) < 1e-9
        return a.toString().trim().equals(b.toString().trim(), ignoreCase = true)
    }

    private fun readAttributes(json: String): Map<String, Any?> =
        runCatching { mapper.readValue<Map<String, Any?>>(json) }.getOrDefault(emptyMap())

    private fun readStringMap(json: String): Map<String, String> =
        runCatching { mapper.readValue<Map<String, String>>(json) }.getOrDefault(emptyMap())

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
        const val MAX_MERGE_PASSES = 3
        const val MAX_SPELLING_VALUES = 40
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
