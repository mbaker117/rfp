package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(javaClass)

    override fun call(systemPrompt: String, userMessage: String): String = callDetailed(systemPrompt, userMessage).text

    /**
     * DeepSeek's models think by default, and the reasoning comes out of the same token budget as the answer:
     * the whole budget is spent before any JSON is written and the content comes back empty. The API takes
     * OpenAI's shape otherwise, so only this one field differs.
     */
    @Volatile
    private var thinkingOff = model.startsWith("deepseek")

    override fun callDetailed(systemPrompt: String, userMessage: String): LlmResponse =
        try {
            post(systemPrompt, userMessage)
        } catch (e: LlmException) {
            // Another provider whose model thinks by default: try once more with it switched off.
            if (thinkingOff || e.message?.contains("Empty LLM response") != true) throw e
            log.info("Model '{}' answered with no content; retrying with thinking disabled", model)
            thinkingOff = true
            post(systemPrompt, userMessage)
        }

    private fun post(systemPrompt: String, userMessage: String): LlmResponse {
        val body = mapper.writeValueAsString(buildMap<String, Any> {
            put("model", model)
            put("temperature", 0)
            put("messages", listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userMessage)
            ))
            put("max_tokens", maxTokens)
            if (thinkingOff) put("thinking", mapOf("type" to "disabled"))
        })
        val request = Request.Builder()
            .url("${baseUrl}chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw llmHttpError(response.code, response.body?.string(), mapper)
            val choice = mapper.readTree(response.body!!.string())["choices"]?.get(0)
            val text = choice?.get("message")?.get("content")?.asText()?.takeIf { it.isNotBlank() }
                ?: throw LlmException("Empty LLM response")
            LlmResponse(text, truncated = choice.get("finish_reason")?.asText() == "length")
        }
    }
}
