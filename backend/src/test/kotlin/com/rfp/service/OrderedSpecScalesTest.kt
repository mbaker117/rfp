package com.rfp.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderedSpecScalesTest {

    @Test
    fun `insulation classes rank by their temperature rating`() {
        val a = OrderedSpecScales.rank("insulation_class", "A")!!
        val b = OrderedSpecScales.rank("insulation_class", "B")!!
        val f = OrderedSpecScales.rank("insulation_class", "F")!!
        val h = OrderedSpecScales.rank("insulation_class", "H")!!

        assertThat(a).isLessThan(b)
        assertThat(b).isLessThan(f)
        assertThat(f).isLessThan(h)
    }

    @Test
    fun `the spellings catalogs use are all understood`() {
        val f = OrderedSpecScales.rank("insulation_class", "F")
        assertThat(OrderedSpecScales.rank("insulation_class", "Class F")).isEqualTo(f)
        assertThat(OrderedSpecScales.rank("insulation_class", "class f")).isEqualTo(f)
        assertThat(OrderedSpecScales.rank("insulation_class", "155")).isEqualTo(f)
        assertThat(OrderedSpecScales.rank("insulation_class", "155 °C")).isEqualTo(f)
    }

    @Test
    fun `efficiency classes rank in order`() {
        assertThat(OrderedSpecScales.rank("motor_efficiency_group", "IE2")!!)
            .isLessThan(OrderedSpecScales.rank("motor_efficiency_group", "IE3")!!)
    }

    @Test
    fun `values and specs outside a known scale have no rank`() {
        assertThat(OrderedSpecScales.rank("insulation_class", "Q")).isNull()
        assertThat(OrderedSpecScales.rank("insulation_class", "999")).isNull()
        assertThat(OrderedSpecScales.rank("enclosure", "TEFC")).isNull()
        assertThat(OrderedSpecScales.isOrdered("enclosure")).isFalse()
        assertThat(OrderedSpecScales.isOrdered("insulation_class")).isTrue()
    }
}
