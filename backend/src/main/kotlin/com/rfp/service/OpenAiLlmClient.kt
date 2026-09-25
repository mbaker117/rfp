package com.rfp.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/** `rfp.llm.provider: openai`. Key from `RFP_LLM_OPENAI_API_KEY`, else the shared `RFP_LLM_API_KEY`. */
@Service
@ConditionalOnProperty(name = ["rfp.llm.provider"], havingValue = "openai")
class OpenAiLlmClient(
    @Value("\${rfp.llm.openai.api-key:}") openAiKey: String,
    @Value("\${rfp.llm.api-key:}") sharedKey: String,
    @Value("\${rfp.llm.model:}") model: String,
    @Value("\${rfp.llm.base-url:}") baseUrl: String,
    @Value("\${rfp.llm.max-tokens:16000}") maxTokens: Int = 16000
) : OpenAiCompatibleClient(
    apiKey = openAiKey.ifBlank { sharedKey },
    model = model.ifBlank { DEFAULT_MODEL },
    baseUrl = baseUrl.ifBlank { "https://api.openai.com/v1/" },
    maxTokens = maxTokens
) {
    private companion object {
        const val DEFAULT_MODEL = "gpt-5-mini"
    }
}
