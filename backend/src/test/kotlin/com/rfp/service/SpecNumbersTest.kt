package com.rfp.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpecNumbersTest {

    @Test
    fun `plain numbers and numeric strings`() {
        assertThat(SpecNumbers.parse(12.375)).isEqualTo(12.375)
        assertThat(SpecNumbers.parse(3)).isEqualTo(3.0)
        assertThat(SpecNumbers.parse("1.15")).isEqualTo(1.15)
        assertThat(SpecNumbers.parse(" 40 ")).isEqualTo(40.0)
    }

    @Test
    fun `mixed fractions as printed in catalogs`() {
        assertThat(SpecNumbers.parse("13 3/8")).isEqualTo(13.375)
        assertThat(SpecNumbers.parse("12 3⁄8")).isEqualTo(12.375)   // fraction slash, as extracted from the PDF
        assertThat(SpecNumbers.parse("5/8")).isEqualTo(0.625)
        assertThat(SpecNumbers.parse("1-7/8")).isEqualTo(1.875)
    }

    @Test
    fun `values that are not a single number stay unparsed`() {
        assertThat(SpecNumbers.parse("115/230")).isNull()          // dual voltage, not a fraction to evaluate
        assertThat(SpecNumbers.parse("56H")).isNull()
        assertThat(SpecNumbers.parse("14.0/6.9-7.0")).isNull()
        assertThat(SpecNumbers.parse("1/0")).isNull()
        assertThat(SpecNumbers.parse("")).isNull()
        assertThat(SpecNumbers.parse(null)).isNull()
        assertThat(SpecNumbers.parse(true)).isNull()
    }
}
