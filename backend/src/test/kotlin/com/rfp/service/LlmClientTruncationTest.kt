package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LlmClientTruncationTest {

    private lateinit var server: MockWebServer
    private val mapper = ObjectMapper()

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

    private fun anthropicReply(text: String, stopReason: String) =
        MockResponse().setBody(mapper.writeValueAsString(mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to text)),
            "stop_reason" to stopReason
        )))

    private fun openAiReply(text: String, finishReason: String) =
        MockResponse().setBody(mapper.writeValueAsString(mapOf(
            "choices" to listOf(mapOf("message" to mapOf("content" to text), "finish_reason" to finishReason))
        )))

    @Test
    fun `anthropic sends the configured max tokens`() {
        server.enqueue(anthropicReply("ok", "end_turn"))

        AnthropicLlmClient("key", "model", baseUrl(), maxTokens = 16000).call("sys", "user")

        val body = mapper.readTree(server.takeRequest().body.readUtf8())
        assertThat(body["max_tokens"].asInt()).isEqualTo(16000)
    }

    @Test
    fun `anthropic reports truncation when it stops at max tokens`() {
        server.enqueue(anthropicReply("""{"products":[{"name":"A"},{"na""", "max_tokens"))

        val res = AnthropicLlmClient("key", "model", baseUrl()).callDetailed("sys", "user")

        assertThat(res.truncated).isTrue()
        assertThat(res.text).startsWith("""{"products"""")
    }

    @Test
    fun `anthropic complete answer is not truncated`() {
        server.enqueue(anthropicReply("""{"products":[]}""", "end_turn"))

        assertThat(AnthropicLlmClient("key", "model", baseUrl()).callDetailed("sys", "user").truncated).isFalse()
    }

    @Test
    fun `openai sends the configured max tokens and reports length truncation`() {
        server.enqueue(openAiReply("""{"products":[{"na""", "length"))

        val res = OpenAiLlmClient("key", "model", baseUrl(), maxTokens = 12000).callDetailed("sys", "user")

        assertThat(res.truncated).isTrue()
        val body = mapper.readTree(server.takeRequest().body.readUtf8())
        assertThat(body["max_tokens"].asInt()).isEqualTo(12000)
    }

    @Test
    fun `openai complete answer is not truncated`() {
        server.enqueue(openAiReply("""{"products":[]}""", "stop"))

        assertThat(OpenAiLlmClient("key", "model", baseUrl()).callDetailed("sys", "user").truncated).isFalse()
    }
}
