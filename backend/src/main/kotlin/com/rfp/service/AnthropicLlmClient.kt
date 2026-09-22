package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["rfp.llm.provider"], havingValue = "anthropic", matchIfMissing = true)
class AnthropicLlmClient(
    @Value("\${rfp.llm.api-key}") val apiKey: String,
    @Value("\${rfp.llm.model}") val model: String,
    @Value("\${rfp.llm.base-url:https://api.anthropic.com/v1/}") val baseUrl: String = "https://api.anthropic.com/v1/",
    @Value("\${rfp.llm.max-tokens:16000}") val maxTokens: Int = 16000
) : LlmClient {

    // Non-streaming: nothing arrives until the whole answer is generated, so the read timeout
    // must cover a full max-tokens response.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val mapper = ObjectMapper()
    private val log = LoggerFactory.getLogger(javaClass)

    private val effectiveBaseUrl = baseUrl.ifBlank { "https://api.anthropic.com/v1/" }

    /**
     * Claude 5 models reject sampling parameters and think by default; thinking would spend the whole output
     * budget before any of the answer is written. The first rejection switches this client to their shape —
     * no temperature, thinking off — for the rest of its life, so a new model needs no code change here.
     */
    @Volatile
    private var claude5 = false

    override fun call(systemPrompt: String, userMessage: String): String = callDetailed(systemPrompt, userMessage).text

    override fun callDetailed(systemPrompt: String, userMessage: String): LlmResponse =
        try {
            post(systemPrompt, userMessage)
        } catch (e: LlmException) {
            if (claude5 || e.message?.contains("`temperature` is deprecated") != true) throw e
            log.info("Model '{}' rejects temperature; switching to Claude 5 request shape (thinking off)", model)
            claude5 = true
            post(systemPrompt, userMessage)
        }

    private fun post(systemPrompt: String, userMessage: String): LlmResponse {
        val body = mapper.writeValueAsString(buildMap<String, Any> {
            put("model", model)
            put("max_tokens", maxTokens)
            if (claude5) put("thinking", mapOf("type" to "disabled")) else put("temperature", 0)
            put("system", systemPrompt)
            put("messages", listOf(mapOf("role" to "user", "content" to userMessage)))
        })
        val request = Request.Builder()
            .url("${effectiveBaseUrl}messages")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw llmHttpError(response.code, response.body?.string(), mapper)
            val json = mapper.readTree(response.body!!.string())
            // The text block is not always first: a thinking block can precede it.
            val text = json["content"]?.firstOrNull { it["type"]?.asText() == "text" }?.get("text")?.asText()
                ?: throw LlmException("Empty LLM response")
            LlmResponse(text, truncated = json["stop_reason"]?.asText() == "max_tokens")
        }
    }
}
