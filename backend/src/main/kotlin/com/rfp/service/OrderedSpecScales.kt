package com.rfp.service

/**
 * Specs whose values are letters or codes but still have an order, so "at least this" is a meaningful test.
 *
 * A tender asking for insulation class F is satisfied by class H: both are standard thermal ratings (A 105 °C,
 * B 130 °C, F 155 °C, H 180 °C) and H tolerates more heat. Compared with `eq`, that motor is reported as a
 * deviation and drops out of the proposal for exceeding the requirement. The same holds for IEC efficiency
 * classes, where IE3 satisfies a request for IE2.
 *
 * Only scales where a higher value genuinely satisfies a lower request belong here; anything else stays `eq`.
 */
object OrderedSpecScales {

    /** Spec name -> value -> rank. Ranks are the real quantity where there is one, so they read as themselves. */
    private val SCALES: Map<String, Map<String, Double>> = mapOf(
        // Insulation class, ranked by its maximum winding temperature in °C.
        "insulation_class" to mapOf("a" to 105.0, "b" to 130.0, "f" to 155.0, "h" to 180.0, "n" to 200.0),
        // IEC 60034-30 efficiency classes.
        "motor_efficiency_group" to mapOf("ie1" to 1.0, "ie2" to 2.0, "ie3" to 3.0, "ie4" to 4.0, "ie5" to 5.0),
        "efficiency_group" to mapOf("ie1" to 1.0, "ie2" to 2.0, "ie3" to 3.0, "ie4" to 4.0, "ie5" to 5.0)
    )

    fun isOrdered(spec: String) = spec.lowercase() in SCALES

    /** The rank of a value, accepting the spellings catalogs use ("F", "Class F", "155 °C"). */
    fun rank(spec: String, value: Any?): Double? {
        val scale = SCALES[spec.lowercase()] ?: return null
        val raw = value?.toString()?.trim()?.lowercase() ?: return null
        val cleaned = raw.removePrefix("class").trim().ifEmpty { return null }
        scale[cleaned]?.let { return it }
        // A value given as the quantity itself ("155", "155 °C") counts when the scale is that quantity.
        val number = Regex("^(\\d+(?:\\.\\d+)?)").find(cleaned)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return if (scale.values.any { kotlin.math.abs(it - number) < 1e-9 }) number else null
    }
}
