package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
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
    @Value("\${rfp.llm.base-url:https://api.openai.com/v1/}") val baseUrl: String = "https://api.openai.com/v1/",
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

    override fun call(systemPrompt: String, userMessage: String): String = callDetailed(systemPrompt, userMessage).text

    override fun callDetailed(systemPrompt: String, userMessage: String): LlmResponse {
        val body = mapper.writeValueAsString(mapOf(
            "model" to model,
            "temperature" to 0,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userMessage)
            ),
            "max_tokens" to maxTokens
        ))
        val request = Request.Builder()
            .url("${baseUrl}chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw llmHttpError(response.code, response.body?.string(), mapper)
            val choice = mapper.readTree(response.body!!.string())["choices"]?.get(0)
            val text = choice?.get("message")?.get("content")?.asText()
                ?: throw LlmException("Empty LLM response")
            LlmResponse(text, truncated = choice.get("finish_reason")?.asText() == "length")
        }
    }
}
