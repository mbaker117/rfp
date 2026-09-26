package com.rfp.service

/**
 * Decides whether two spec values may be treated as the same value spelled differently.
 *
 * Merging values is not symmetric with merging spec names: a wrong name merge shows up as products disagreeing
 * on the same key, which code can check, but a wrong value merge silently rewrites what a product *is*. Models
 * have proposed `CW` -> `CW/CCW` (a one-way motor becomes reversible), `Any Angle (Except Shaft Up)` ->
 * `Any angle` (a mounting restriction disappears) and `Ball` -> `Ball, permanently lubricated` (a bearing gains
 * a feature). Each claims more than the catalog said, on exactly the specs a buyer filters by.
 *
 * So: a merge passes only when the two are the same thing written differently — same letters and digits, or the
 * same number with a unit — and is rejected whenever one value contains the other, because then one of them says
 * more. This is deliberately conservative: it also rejects true merges like `Baldor Electric` -> `Baldor`, and
 * two spellings of one brand left apart cost far less than a spec that claims capability a product lacks.
 */
object ValueMergeVeto {

    /** Number optionally followed by a unit: "90V", "115 V AC", "40°C". */
    private val NUMBER_WITH_UNIT = Regex("^(\\d+(?:\\.\\d+)?)\\s*[\\p{L}%°/ ]*$")

    fun allows(variant: String, canonical: String): Boolean {
        val a = variant.trim()
        val b = canonical.trim()
        if (a.isEmpty() || b.isEmpty()) return false

        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true                      // "Cast-iron" / "Cast Iron"

        sameNumber(a, b)?.let { return it }            // "90V" / "90"

        // Values that name directions are compared by which directions they claim, not by their words:
        // "Clockwise facing output shaft" shares no text with "CW/CCW" but claims one direction against two.
        directions(a)?.let { da ->
            directions(b)?.let { db -> return da == db }
        }

        // One contains the other, so one of them says more: "CW" in "CW/CCW", "Any Angle" in "Any Angle (Except…)".
        return !na.contains(nb) && !nb.contains(na)
    }

    /** Which of a proposed map's entries may be applied. */
    fun filter(map: Map<String, String>): Map<String, String> = map.filter { (variant, canonical) ->
        allows(variant, canonical)
    }

    private fun normalize(v: String) = v.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Which rotation directions a value claims, or null when it is not about rotation. A motor that turns one way
     * is not the same product as one that turns either way, however the catalog words it.
     */
    private fun directions(value: String): Set<String>? {
        val v = value.lowercase()
        val both = setOf("cw", "ccw")
        if (Regex("\\breversible\\b|\\bbi-?directional\\b|\\beither direction\\b").containsMatchIn(v)) return both
        val found = mutableSetOf<String>()
        // Counter-clockwise first: "counterclockwise" also contains "clockwise".
        val ccw = Regex("\\bccw\\b|counter-?\\s?clockwise")
        val cw = Regex("\\bcw\\b|clockwise")
        if (ccw.containsMatchIn(v)) found += "ccw"
        if (cw.containsMatchIn(ccw.replace(v, " "))) found += "cw"
        return found.ifEmpty { null }
    }

    /** true/false when both sides are a number with an optional unit, null when they are not comparable that way. */
    private fun sameNumber(a: String, b: String): Boolean? {
        val na = NUMBER_WITH_UNIT.matchEntire(a)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val nb = NUMBER_WITH_UNIT.matchEntire(b)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return kotlin.math.abs(na - nb) < 1e-9
    }
}
