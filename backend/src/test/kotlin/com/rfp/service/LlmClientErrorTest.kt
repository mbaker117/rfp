package com.rfp.service

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LlmClientErrorTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/v1/").toString()

    @Test
    fun `anthropic error includes the provider message`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}"""
        ))
        val client = AnthropicLlmClient("key", "model", baseUrl())

        assertThatThrownBy { client.call("sys", "user") }
            .isInstanceOf(LlmException::class.java)
            .hasMessage("LLM API error 400: Your credit balance is too low to access the Anthropic API.")
    }

    @Test
    fun `openai error includes the provider message`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody(
            """{"error":{"message":"You exceeded your current quota.","type":"insufficient_quota"}}"""
        ))
        val client = OpenAiLlmClient("key", "model", baseUrl())

        assertThatThrownBy { client.call("sys", "user") }
            .isInstanceOf(LlmException::class.java)
            .hasMessage("LLM API error 429: You exceeded your current quota.")
    }

    @Test
    fun `non-json error body falls back to a truncated raw body`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>" + "x".repeat(1000) + "</html>"))
        val client = AnthropicLlmClient("key", "model", baseUrl())

        assertThatThrownBy { client.call("sys", "user") }
            .isInstanceOf(LlmException::class.java)
            .satisfies({ e -> assertThat(e.message).startsWith("LLM API error 502: <html>").hasSizeLessThan(360) })
    }

    @Test
    fun `empty error body reports only the status`() {
        server.enqueue(MockResponse().setResponseCode(503))
        val client = AnthropicLlmClient("key", "model", baseUrl())

        assertThatThrownBy { client.call("sys", "user") }
            .isInstanceOf(LlmException::class.java)
            .hasMessage("LLM API error 503")
    }
}
