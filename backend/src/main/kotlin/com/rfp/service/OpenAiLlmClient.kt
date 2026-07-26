package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["rfp.llm.provider"], havingValue = "openai")
class OpenAiLlmClient(
    @Value("\${rfp.llm.api-key}") val apiKey: String,
    @Value("\${rfp.llm.model}") val model: String,
    @Value("\${rfp.llm.base-url:https://api.openai.com/v1/}") val baseUrl: String = "https://api.openai.com/v1/"
) : LlmClient {

    private val client = OkHttpClient()
    private val mapper = ObjectMapper()

    override fun call(systemPrompt: String, userMessage: String): String {
        val body = mapper.writeValueAsString(mapOf(
            "model" to model,
            "temperature" to 0,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userMessage)
            ),
            "max_tokens" to 4096
        ))
        val request = Request.Builder()
            .url("${baseUrl}chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw LlmException("LLM API error: ${response.code}")
            val json = mapper.readTree(response.body!!.string())
            json["choices"]?.get(0)?.get("message")?.get("content")?.asText()
                ?: throw LlmException("Empty LLM response")
        }
    }
}
