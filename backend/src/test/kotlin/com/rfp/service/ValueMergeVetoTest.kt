package com.rfp.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The cases are real proposals from catalog imports — the ones that were right, and the ones that lost data. */
class ValueMergeVetoTest {

    @Test
    fun `values that claim more than the other are rejected`() {
        // A one-way motor would become reversible.
        assertThat(ValueMergeVeto.allows("CW", "CW/CCW")).isFalse()
        assertThat(ValueMergeVeto.allows("CCW", "CW/CCW")).isFalse()
        // A mounting restriction would disappear.
        assertThat(ValueMergeVeto.allows("Any Angle (Except Shaft Up)", "Any angle")).isFalse()
        // A bearing would gain a feature the catalog never claimed.
        assertThat(ValueMergeVeto.allows("Ball", "Ball, permanently lubricated")).isFalse()
        // "No" is not the same as "not included".
        assertThat(ValueMergeVeto.allows("Capacitor Not Included", "No")).isFalse()
    }

    @Test
    fun `the same value written differently is allowed`() {
        assertThat(ValueMergeVeto.allows("Cast-iron", "Cast Iron")).isTrue()
        assertThat(ValueMergeVeto.allows("Die-cast zinc", "Die Cast Zinc")).isTrue()
        assertThat(ValueMergeVeto.allows("Totally Enclosed Non-Ventilated", "Totally Enclosed Nonventilated")).isTrue()
        assertThat(ValueMergeVeto.allows("Open Dripproof", "Open Drip Proof")).isTrue()
    }

    @Test
    fun `an abbreviation and its expansion are allowed`() {
        assertThat(ValueMergeVeto.allows("PSC", "Permanent Split Capacitor")).isTrue()
        assertThat(ValueMergeVeto.allows("TEFC", "Totally Enclosed Fan-Cooled")).isTrue()
        assertThat(ValueMergeVeto.allows("3-Phase", "Three-Phase")).isTrue()
        assertThat(ValueMergeVeto.allows("CW Facing Lead End", "CWLE")).isTrue()
        assertThat(ValueMergeVeto.allows("Single", "1")).isTrue()
    }

    @Test
    fun `the same number with and without its unit is allowed`() {
        assertThat(ValueMergeVeto.allows("90V", "90")).isTrue()
        assertThat(ValueMergeVeto.allows("115V AC", "115")).isTrue()
        assertThat(ValueMergeVeto.allows("40 °C", "40")).isTrue()
        assertThat(ValueMergeVeto.allows("230", "115")).isFalse()   // different numbers are different values
    }

    @Test
    fun `a true merge is rejected when one value contains the other, which is the price of the rule`() {
        assertThat(ValueMergeVeto.allows("Baldor Electric", "Baldor")).isFalse()
    }

    @Test
    fun `filter keeps only the entries that pass`() {
        val proposed = mapOf("PSC" to "Permanent Split Capacitor", "CW" to "CW/CCW", "Cast-iron" to "Cast Iron")

        assertThat(ValueMergeVeto.filter(proposed))
            .containsExactlyInAnyOrderEntriesOf(mapOf("PSC" to "Permanent Split Capacitor", "Cast-iron" to "Cast Iron"))
    }

    @Test
    fun `blank values are never merged`() {
        assertThat(ValueMergeVeto.allows("", "Ball")).isFalse()
        assertThat(ValueMergeVeto.allows("Ball", "   ")).isFalse()
        assertThat(ValueMergeVeto.allows("-", "Ball")).isFalse()
    }
}
