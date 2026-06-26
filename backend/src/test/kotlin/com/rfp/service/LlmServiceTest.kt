package com.rfp.service

import com.rfp.dto.ExtractedRequirement
import com.rfp.domain.Company
import com.rfp.domain.Instrument
import com.rfp.domain.enums.MatchStatus
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LlmServiceTest {
    private val server = MockWebServer()

    @BeforeEach fun start() { server.start() }
    @AfterEach fun stop() { server.shutdown() }

    private fun makeService(): LlmService =
        LlmService(
            apiKey = "test-key",
            model = "claude-sonnet-4-6",
            baseUrl = server.url("/").toString()
        )

    @Test
    fun `extractRequirements parses Claude JSON response`() {
        server.enqueue(MockResponse().setBody("""
            {
              "content": [{
                "text": "{\"requirements\":[{\"rawText\":\"Oscilloscope 200MHz\",\"name\":\"Oscilloscope\",\"quantity\":2,\"specs\":{\"bandwidth\":\"200MHz\"}}]}"
              }]
            }
        """).addHeader("Content-Type", "application/json"))

        val result = makeService().extractRequirements("Oscilloscope 200MHz x2")
        assertEquals(1, result.size)
        assertEquals("Oscilloscope", result[0].name)
        assertEquals(2, result[0].quantity)
        assertEquals("200MHz", result[0].specs["bandwidth"])
    }

    @Test
    fun `scoreMatch returns MATCHED with high score when good candidate exists`() {
        server.enqueue(MockResponse().setBody("""
            {
              "content": [{
                "text": "{\"matchedInstrumentId\":7,\"score\":92,\"reason\":\"Same model family\",\"status\":\"MATCHED\"}"
              }]
            }
        """).addHeader("Content-Type", "application/json"))

        val company = Company(id = 1L, name = "Tektronix")
        val instrument = Instrument(id = 7L, company = company, description = "Oscilloscope 200MHz", normalizedName = "Oscilloscope 200MHz")
        val req = ExtractedRequirement("Oscilloscope 200MHz", "Oscilloscope", 1, mapOf("bandwidth" to "200MHz"))

        val result = makeService().scoreMatch(req, listOf(instrument))
        assertEquals(7L, result.matchedInstrumentId)
        assertEquals(92, result.score)
        assertEquals(MatchStatus.MATCHED, result.status)
    }

    @Test
    fun `structureScrapeData parses instrument list from Claude response`() {
        server.enqueue(MockResponse().setBody(
            """{"content":[{"text":"{\"instruments\":[{\"description\":\"Digital Oscilloscope 200MHz\",\"normalizedName\":\"Oscilloscope 200MHz\",\"manualLink\":\"https://tek.com/manual.pdf\",\"price\":1200.00,\"currency\":\"JOD\"}]}"}]}"""
        ).addHeader("Content-Type", "application/json"))

        val result = makeService().structureScrapeData("<html>Oscilloscope 200MHz</html>", "Tektronix")
        assertEquals(1, result.size)
        assertEquals("Oscilloscope 200MHz", result[0].normalizedName)
        assertEquals(java.math.BigDecimal("1200.0"), result[0].price)
    }

    @Test
    fun `scoreMatch returns NOT_FOUND immediately when candidates list is empty`() {
        val req = ExtractedRequirement("test", "test", null, emptyMap())
        val result = makeService().scoreMatch(req, emptyList())
        assertNull(result.matchedInstrumentId)
        assertEquals(0, result.score)
        assertEquals("No candidates", result.reason)
        assertEquals(MatchStatus.NOT_FOUND, result.status)
        assertEquals(0, server.requestCount)
    }
}
