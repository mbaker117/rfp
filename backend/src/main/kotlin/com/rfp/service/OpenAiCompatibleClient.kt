package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * The `/chat/completions` shape, which OpenAI and the providers that copy it (DeepSeek, and most self-hosted
 * gateways) all speak. A provider differs only in its base URL, its key, and whether its models think by default;
 * [OpenAiLlmClient] and [DeepSeekLlmClient] are those differences and nothing else.
 */
open class OpenAiCompatibleClient(
    private val apiKey: String,
    private val model: String,
    baseUrl: String,
    private val maxTokens: Int,
    /** Models that think by default spend the whole token budget reasoning and return no content. */
    thinkingOffByDefault: Boolean = false
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
    private val effectiveBaseUrl = baseUrl.ifBlank { "https://api.openai.com/v1/" }.let {
        if (it.endsWith("/")) it else "$it/"
    }

    @Volatile
    private var thinkingOff = thinkingOffByDefault

    override fun call(systemPrompt: String, userMessage: String): String = callDetailed(systemPrompt, userMessage).text

    override fun callDetailed(systemPrompt: String, userMessage: String): LlmResponse =
        try {
            post(systemPrompt, userMessage)
        } catch (e: LlmException) {
            // A provider whose models think by default: try once more with it switched off.
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
            .url("${effectiveBaseUrl}chat/completions")
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
