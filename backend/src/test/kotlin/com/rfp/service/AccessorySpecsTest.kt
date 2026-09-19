package com.rfp.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AccessorySpecsTest {

    @Test
    fun `included and not included become a boolean`() {
        assertThat(AccessorySpecs.normalize(mapOf("capacitor_req" to "Capacitor Included", "power_hp" to 1.0)))
            .isEqualTo(mapOf("capacitor_included" to true, "power_hp" to 1.0))
        assertThat(AccessorySpecs.normalize(mapOf("capacitor_req" to "Capacitor Not Included")))
            .isEqualTo(mapOf("capacitor_included" to false))
    }

    @Test
    fun `an item number is the accessory to buy, so it is not included`() {
        assertThat(AccessorySpecs.normalize(mapOf("capacitor_required" to "2MDV6")))
            .isEqualTo(mapOf("capacitor_item_no" to "2MDV6", "capacitor_included" to false))
        assertThat(AccessorySpecs.normalize(mapOf("requires_capacitor" to "2MDV3")))
            .isEqualTo(mapOf("capacitor_item_no" to "2MDV3", "capacitor_included" to false))
        assertThat(AccessorySpecs.normalize(mapOf("capacitor_selection" to "2MDV6 for 1/5 to 1/3 HP Setup, 2MDV7 for 1/2 HP")))
            .isEqualTo(mapOf("capacitor_item_no" to "2MDV6 for 1/5 to 1/3 HP Setup, 2MDV7 for 1/2 HP", "capacitor_included" to false))
    }

    @Test
    fun `no in a required column means none is needed`() {
        assertThat(AccessorySpecs.normalize(mapOf("capacitor_req" to "No")))
            .isEqualTo(mapOf("capacitor_required" to false))
    }

    @Test
    fun `compatible item keys are renamed and existing values win`() {
        assertThat(AccessorySpecs.normalize(mapOf("compatible_capacitor_item_no" to "2MDW3", "capacitor_req" to "Capacitor Not Included")))
            .isEqualTo(mapOf("capacitor_item_no" to "2MDW3", "capacitor_included" to false))
        assertThat(AccessorySpecs.normalize(mapOf("capacitor_included" to true, "capacitor_req" to "2MDV4")))
            .isEqualTo(mapOf("capacitor_included" to true, "capacitor_item_no" to "2MDV4"))
    }

    @Test
    fun `other keys and values are left alone and normalizing twice changes nothing`() {
        val attrs = mapOf("voltage_selection" to "115V", "power_req" to "115/230", "capacitor_remotely_mounted" to true)
        assertThat(AccessorySpecs.normalize(attrs)).isEqualTo(attrs)
        val once = AccessorySpecs.normalize(mapOf("capacitor_req" to "No", "brush_req" to "Brush Included"))
        assertThat(AccessorySpecs.normalize(once)).isEqualTo(once)
        assertThat(once).isEqualTo(mapOf("capacitor_required" to false, "brush_included" to true))
    }
}
