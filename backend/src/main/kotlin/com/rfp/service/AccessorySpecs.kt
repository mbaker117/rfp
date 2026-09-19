package com.rfp.service

/**
 * Splits "accessory required" columns into two consistent keys. Catalogs print one column (Grainger's
 * "Capacitor Req.") holding either "Capacitor Included" / "Capacitor Not Included" or the item number of the
 * accessory to buy, and pages named it differently (`capacitor_req`, `capacitor_required`, `requires_capacitor`,
 * `capacitor_selection`, `compatible_capacitor_item_no`). This yields `<x>_included` (boolean), `<x>_required`
 * (boolean; false when the product needs none) and `<x>_item_no` (the accessory's item number, an identifier).
 * Values that are none of these are left under their key.
 */
object AccessorySpecs {
    private val REQUIRED_KEY = Regex("^(?:requires_([a-z0-9_]+)|([a-z0-9_]+?)_(?:req|required|selection))$")
    private val COMPATIBLE_ITEM_KEY = Regex("^compatible_([a-z0-9_]+)_item_no$")
    private val ITEM_NO = Regex("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9-]{4,15}$")
    private val NUMBER_WITH_UNIT = Regex("^\\d+(?:\\.\\d+)?\\s*[A-Za-z%°/]+$")
    private val INCLUDED = setOf("included")
    private val NOT_INCLUDED = setOf("not included")
    /** "No" in a "required" column means the product needs none (shaded-pole motors), not that it isn't included. */
    private val REQUIRED = setOf("yes", "true", "required")
    private val NOT_REQUIRED = setOf("no", "false", "none", "not required", "n/a")

    /** A key [normalize] reads from (and may remove). */
    fun isSourceKey(key: String) = COMPATIBLE_ITEM_KEY.matches(key) || REQUIRED_KEY.matches(key)

    fun normalize(attributes: Map<String, Any>): Map<String, Any> {
        if (attributes.keys.none { REQUIRED_KEY.matches(it) || COMPATIBLE_ITEM_KEY.matches(it) }) return attributes
        val out = LinkedHashMap(attributes)
        attributes.forEach { (key, value) ->
            COMPATIBLE_ITEM_KEY.matchEntire(key)?.let { m ->
                out.remove(key)
                out.putIfAbsent("${m.groupValues[1]}_item_no", value)
                return@forEach
            }
            val m = REQUIRED_KEY.matchEntire(key) ?: return@forEach
            val accessory = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val text = value.toString().trim()
            val word = text.lowercase().removePrefix(accessory.replace('_', ' ')).trim()
            when {
                word in INCLUDED -> set(out, key, "${accessory}_included", true)
                word in NOT_INCLUDED -> set(out, key, "${accessory}_included", false)
                word in REQUIRED -> set(out, key, "${accessory}_required", true)
                word in NOT_REQUIRED -> set(out, key, "${accessory}_required", false)
                isItemNumber(text) || text.split(Regex("[,;]")).any { part -> part.trim().split(' ').any { isItemNumber(it) } } -> {
                    // An accessory to buy separately: not included.
                    set(out, key, "${accessory}_item_no", text)
                    out.putIfAbsent("${accessory}_included", false)
                }
            }
        }
        return out
    }

    private fun set(out: MutableMap<String, Any>, from: String, to: String, value: Any) {
        out.remove(from)
        out.putIfAbsent(to, value)
    }

    private fun isItemNumber(v: String) =
        ITEM_NO.matches(v) && !NUMBER_WITH_UNIT.matches(v) && SpecNumbers.parse(v) == null
}
