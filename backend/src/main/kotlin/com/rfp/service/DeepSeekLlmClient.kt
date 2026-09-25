package com.rfp.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * `rfp.llm.provider: deepseek`. Key from `RFP_LLM_DEEPSEEK_API_KEY`, else the shared `RFP_LLM_API_KEY`.
 *
 * DeepSeek speaks OpenAI's `/chat/completions`, so only three things differ: the base URL, the key, and that its
 * models think by default — the reasoning comes out of the answer's token budget, so thinking is switched off.
 * Its prices double during peak hours (01:00-04:00 and 06:00-10:00 UTC on weekdays).
 */
@Service
@ConditionalOnProperty(name = ["rfp.llm.provider"], havingValue = "deepseek")
class DeepSeekLlmClient(
    @Value("\${rfp.llm.deepseek.api-key:}") deepSeekKey: String,
    @Value("\${rfp.llm.api-key:}") sharedKey: String,
    @Value("\${rfp.llm.model:}") model: String,
    @Value("\${rfp.llm.base-url:}") baseUrl: String,
    @Value("\${rfp.llm.max-tokens:16000}") maxTokens: Int = 16000
) : OpenAiCompatibleClient(
    apiKey = deepSeekKey.ifBlank { sharedKey },
    model = model.ifBlank { DEFAULT_MODEL },
    baseUrl = baseUrl.ifBlank { "https://api.deepseek.com/" },
    maxTokens = maxTokens,
    thinkingOffByDefault = true
) {
    private companion object {
        /** deepseek-flash matched Claude's product coverage at a fraction of the cost; deepseek-v4-pro is the other. */
        const val DEFAULT_MODEL = "deepseek-flash"
    }
}
