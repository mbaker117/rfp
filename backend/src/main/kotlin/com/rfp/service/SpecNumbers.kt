package com.rfp.service

/**
 * Reads a spec value as a number: plain numbers, numeric strings, and the mixed fractions catalogs print
 * ("13 3/8", "12 3⁄8" with the fraction slash, "1-7/8", "5/8"). Only proper fractions with catalog-style
 * denominators (up to 64) count, so a dual rating such as "115/230" is not mistaken for 0.5.
 */
object SpecNumbers {
    private val MIXED = Regex("""^(\d+)(?:[\s-]+(\d+)/(\d+))?$""")
    private val FRACTION = Regex("""^(\d+)/(\d+)$""")
    private const val MAX_DENOMINATOR = 64

    fun parse(value: Any?): Double? {
        when (value) {
            null, is Boolean -> return null
            is Number -> return value.toDouble()
        }
        val s = value.toString().trim().replace('⁄', '/')
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { return it }
        FRACTION.matchEntire(s)?.let { m -> return fraction(m.groupValues[1], m.groupValues[2]) }
        MIXED.matchEntire(s)?.let { m ->
            val whole = m.groupValues[1].toDouble()
            if (m.groupValues[2].isEmpty()) return whole
            return fraction(m.groupValues[2], m.groupValues[3])?.let { whole + it }
        }
        return null
    }

    private fun fraction(numerator: String, denominator: String): Double? {
        val n = numerator.toInt()
        val d = denominator.toInt()
        return if (d in 1..MAX_DENOMINATOR && n < d) n.toDouble() / d else null
    }
}
