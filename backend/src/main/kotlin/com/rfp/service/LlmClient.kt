package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper

/** [truncated] is true when the provider stopped because it hit the output token limit. */
data class LlmResponse(val text: String, val truncated: Boolean)

interface LlmClient {
    fun call(systemPrompt: String, userMessage: String): String

    fun callDetailed(systemPrompt: String, userMessage: String): LlmResponse =
        LlmResponse(call(systemPrompt, userMessage), truncated = false)
}

private const val MAX_ERROR_DETAIL_CHARS = 300

/**
 * Builds an [LlmException] for a non-2xx provider response, keeping the provider's own
 * explanation (e.g. "credit balance is too low") so it reaches the ingest/tender error message.
 * Anthropic and OpenAI both return `{"error": {"message": "..."}}`.
 */
internal fun llmHttpError(code: Int, body: String?, mapper: ObjectMapper): LlmException {
    val raw = body?.trim().orEmpty()
    val detail = runCatching { mapper.readTree(raw)?.path("error")?.path("message")?.asText() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: raw
    if (detail.isBlank()) return LlmException("LLM API error $code")
    return LlmException("LLM API error $code: ${detail.take(MAX_ERROR_DETAIL_CHARS)}")
}
