package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Claude 5 models take a different request shape than Sonnet 4.6 and earlier; the client adapts to it. */
class LlmClientModelShapeTest {

    private lateinit var server: MockWebServer
    private val mapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun baseUrl() = server.url("/v1/").toString()

    private fun temperatureRejected() = MockResponse().setResponseCode(400).setBody(
        """{"type":"error","error":{"type":"invalid_request_error","message":"`temperature` is deprecated for this model."}}"""
    )

    private fun reply(vararg blocks: Map<String, String>) = MockResponse().setBody(
        mapper.writeValueAsString(mapOf("content" to blocks.toList(), "stop_reason" to "end_turn"))
    )

    private fun textBlock(text: String) = mapOf("type" to "text", "text" to text)

    @Test
    fun `a model that rejects temperature is retried without it and with thinking off`() {
        server.enqueue(temperatureRejected())
        server.enqueue(reply(textBlock("""{"products":[]}""")))

        val client = AnthropicLlmClient("key", "claude-sonnet-5", baseUrl())
        assertThat(client.call("sys", "user")).isEqualTo("""{"products":[]}""")

        val first = mapper.readTree(server.takeRequest().body.readUtf8())
        assertThat(first["temperature"]).isNotNull
        assertThat(first["thinking"]).isNull()
        val second = mapper.readTree(server.takeRequest().body.readUtf8())
        assertThat(second["temperature"]).isNull()
        assertThat(second["thinking"]["type"].asText()).isEqualTo("disabled")
    }

    @Test
    fun `the new shape is remembered, so later calls skip the rejected request`() {
        server.enqueue(temperatureRejected())
        server.enqueue(reply(textBlock("one")))
        server.enqueue(reply(textBlock("two")))

        val client = AnthropicLlmClient("key", "claude-sonnet-5", baseUrl())
        client.call("sys", "user")
        assertThat(client.call("sys", "user again")).isEqualTo("two")

        assertThat(server.requestCount).isEqualTo(3)
        repeat(2) { server.takeRequest() }
        assertThat(mapper.readTree(server.takeRequest().body.readUtf8())["temperature"]).isNull()
    }

    @Test
    fun `the answer is read from the text block even when a thinking block comes first`() {
        server.enqueue(reply(mapOf("type" to "thinking", "thinking" to "considering"), textBlock("the answer")))

        assertThat(AnthropicLlmClient("key", "model", baseUrl()).call("sys", "user")).isEqualTo("the answer")
    }

    @Test
    fun `a response without a text block is an error, not a silent empty answer`() {
        server.enqueue(reply(mapOf("type" to "thinking", "thinking" to "still considering")))

        assertThatThrownBy { AnthropicLlmClient("key", "model", baseUrl()).call("sys", "user") }
            .isInstanceOf(LlmException::class.java)
    }

    @Test
    fun `other errors are not retried`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid key"}}"""))

        assertThatThrownBy { AnthropicLlmClient("key", "claude-sonnet-5", baseUrl()).call("sys", "user") }
            .isInstanceOf(LlmException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }
}
