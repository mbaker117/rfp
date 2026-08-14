package com.rfp.service

import com.rfp.dto.*
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LlmServiceTest {

    @Test
    fun `parseCatalogBatch returns parsed products`() {
        val llmClient = mockk<LlmClient>()
        val service = LlmService(llmClient)
        every { llmClient.call(any(), any()) } returns """
            {"products":[{"className":"Multimeter","name":"Fluke 179","mpn":"FL179",
              "price":320.0,"currency":"JOD","attributes":{"description":"Portable true-RMS multimeter.",
              "manualLink":"https://example.com/manual.pdf","max_voltage":1000,"has_trms":true}}]}
        """.trimIndent()

        val result = service.parseCatalogBatch("raw text", emptyList())
        assertThat(result).hasSize(1)
        assertThat(result[0].name).isEqualTo("Fluke 179")
        assertThat(result[0].mpn).isEqualTo("FL179")
        assertThat(result[0].attributes["description"])
            .isEqualTo("Portable true-RMS multimeter.")
        assertThat(result[0].attributes["manualLink"])
            .isEqualTo("https://example.com/manual.pdf")
    }

    @Test
    fun `defineClass returns attribute defs with match ops`() {
        val llmClient = mockk<LlmClient>()
        val service = LlmService(llmClient)
        every { llmClient.call(any(), any()) } returns """
            {"className":"Multimeter","attributeDefs":[
              {"name":"max_voltage","label":"Max Voltage","datatype":"numeric",
               "matchOp":"gte","canonicalUnit":"V","allowedValues":[]}
            ]}
        """.trimIndent()

        val result = service.defineClass("Multimeter", listOf("Fluke 179 1000V"))
        assertThat(result.className).isEqualTo("Multimeter")
        assertThat(result.attributeDefs[0].matchOp).isEqualTo("gte")
    }

    @Test
    fun `parseTenderLines returns lines with attributes`() {
        val llmClient = mockk<LlmClient>()
        val service = LlmService(llmClient)
        every { llmClient.call(any(), any()) } returns """
            {"lines":[{"className":"Multimeter","description":"True RMS multimeter 1000V",
              "qty":5,"qtyUnit":"pcs","attributes":{"max_voltage":1000,"has_trms":true}}]}
        """.trimIndent()

        val result = service.parseTenderLines("RFP text", emptyList())
        assertThat(result).hasSize(1)
        assertThat(result[0].description).isEqualTo("True RMS multimeter 1000V")
        assertThat(result[0].qty?.toInt()).isEqualTo(5)
    }
}
