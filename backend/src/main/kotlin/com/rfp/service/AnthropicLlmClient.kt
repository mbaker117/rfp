package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["rfp.llm.provider"], havingValue = "anthropic", matchIfMissing = true)
class AnthropicLlmClient(
    @Value("\${rfp.llm.api-key}") val apiKey: String,
    @Value("\${rfp.llm.model}") val model: String,
    @Value("\${rfp.llm.base-url:https://api.anthropic.com/v1/}") val baseUrl: String = "https://api.anthropic.com/v1/"
) : LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val mapper = ObjectMapper()

    private val effectiveBaseUrl = baseUrl.ifBlank { "https://api.anthropic.com/v1/" }

    override fun call(systemPrompt: String, userMessage: String): String {
        val body = mapper.writeValueAsString(mapOf(
            "model" to model,
            "max_tokens" to 4096,
            "temperature" to 0,
            "system" to systemPrompt,
            "messages" to listOf(mapOf("role" to "user", "content" to userMessage))
        ))
        val request = Request.Builder()
            .url("${effectiveBaseUrl}messages")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw LlmException("LLM API error: ${response.code}")
            val json = mapper.readTree(response.body!!.string())
            json["content"]?.get(0)?.get("text")?.asText()
                ?: throw LlmException("Empty LLM response")
        }
    }
}
