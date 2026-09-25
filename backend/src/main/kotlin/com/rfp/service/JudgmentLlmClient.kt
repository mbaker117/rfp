package com.rfp.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wraps the client used for the schema-judgment tasks (see [LlmService.judge]). It is a wrapper rather than
 * another `LlmClient` bean so that injecting `LlmClient` stays unambiguous.
 */
class JudgmentLlmClient(val client: LlmClient)

/**
 * Lets the judgment tasks run on a different model than extraction: cheap models read catalogs well but judge
 * them badly, and the judgment tasks are a few dozen calls per import rather than thousands.
 *
 * `rfp.llm.judgment.provider` picks it (`anthropic` | `openai` | `deepseek`), `rfp.llm.judgment.model` the model.
 * Each provider uses its own key, exactly as the main client does. Without the property nothing is created and
 * [LlmService] uses the main client for everything.
 */
@Configuration
@ConditionalOnProperty(name = ["rfp.llm.judgment.provider"])
class JudgmentLlmConfig {

    @Bean
    fun judgmentLlmClient(
        @Value("\${rfp.llm.judgment.provider:}") provider: String,
        @Value("\${rfp.llm.judgment.model:}") model: String,
        @Value("\${rfp.llm.anthropic.api-key:}") anthropicKey: String,
        @Value("\${rfp.llm.openai.api-key:}") openAiKey: String,
        @Value("\${rfp.llm.deepseek.api-key:}") deepSeekKey: String,
        @Value("\${rfp.llm.api-key:}") sharedKey: String,
        @Value("\${rfp.llm.max-tokens:16000}") maxTokens: Int
    ): JudgmentLlmClient = JudgmentLlmClient(
        when (val p = provider.trim().lowercase()) {
            "anthropic" -> AnthropicLlmClient(anthropicKey, sharedKey, model, "", maxTokens)
            "openai" -> OpenAiLlmClient(openAiKey, sharedKey, model, "", maxTokens)
            "deepseek" -> DeepSeekLlmClient(deepSeekKey, sharedKey, model, "", maxTokens)
            else -> throw IllegalStateException(
                "rfp.llm.judgment.provider is '$p'; expected anthropic, openai or deepseek"
            )
        }
    )
}
