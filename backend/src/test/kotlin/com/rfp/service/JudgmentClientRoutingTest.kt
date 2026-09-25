package com.rfp.service

import com.rfp.dto.AttributeSample
import com.rfp.dto.AttributeUsage
import com.rfp.dto.ClassSchema
import com.rfp.dto.ValueUsage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Extraction and judgment can run on different models; this checks each task reaches the right one. */
class JudgmentClientRoutingTest {

    private class Recording(val name: String, val reply: String) : LlmClient {
        val prompts = mutableListOf<String>()
        override fun call(systemPrompt: String, userMessage: String): String {
            prompts += userMessage
            return reply
        }
        override fun callDetailed(systemPrompt: String, userMessage: String) =
            LlmResponse(call(systemPrompt, userMessage), truncated = false)
    }

    private val main = Recording("main", """{"products":[]}""")
    private val judgment = Recording("judgment", """{"groups":[],"specs":[],"attributes":[],"className":"X","attributeDefs":[]}""")
    private val service = LlmService(main, JudgmentLlmClient(judgment))

    @Test
    fun `schema judgment goes to the judgment model`() {
        service.findDuplicateAttributes("AC Motor", listOf(AttributeUsage("phase", 5, listOf("1"))))
        service.findValueSynonyms("AC Motor", listOf(ValueUsage("enclosure", mapOf("TEFC" to 3, "ODP" to 2))))
        service.defineAttributes("AC Motor", listOf(AttributeSample("power_hp", listOf("1"))))

        assertThat(judgment.prompts).hasSize(3)
        assertThat(main.prompts).isEmpty()
    }

    @Test
    fun `reading the catalog stays on the main model`() {
        service.parseCatalogBatch("some catalog text", emptyList())

        assertThat(main.prompts).hasSize(1)
        assertThat(judgment.prompts).isEmpty()
    }

    @Test
    fun `without a judgment model every task uses the main one`() {
        val sole = Recording("sole", """{"groups":[],"products":[],"lines":[]}""")
        val only = LlmService(sole)

        only.findDuplicateAttributes("AC Motor", listOf(AttributeUsage("phase", 5, listOf("1"))))
        only.parseCatalogBatch("some catalog text", emptyList())

        assertThat(sole.prompts).hasSize(2)
    }
}
