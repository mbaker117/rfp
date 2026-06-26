---
name: project-llm-provider
description: LLM provider abstraction — how to switch between Anthropic and OpenAI via env vars
metadata: 
  node_type: memory
  type: project
  originSessionId: 9cee62d9-e564-41c1-8a84-de2804b490e9
---

`LlmService` delegates HTTP to a `LlmClient` interface. Two implementations exist: `AnthropicLlmClient` (default) and `OpenAiLlmClient`. Selection is `@ConditionalOnProperty` on `rfp.llm.provider`.

**Why:** User asked for OpenAI/GPT switchability; refactored after initial Anthropic-only implementation.

**How to apply:** When the user asks about changing LLM or adding a new provider, only a new `LlmClient` implementation is needed — `LlmService` (prompts, caching, parsing) doesn't change.

## Switching providers — env vars only, no code changes

```bash
# Anthropic (default)
RFP_LLM_PROVIDER=anthropic
RFP_LLM_API_KEY=sk-ant-...
RFP_LLM_MODEL=claude-sonnet-4-6

# OpenAI / GPT
RFP_LLM_PROVIDER=openai
RFP_LLM_API_KEY=sk-...
RFP_LLM_MODEL=gpt-4o

# Optional: override base URL (Azure OpenAI, Ollama, etc.)
RFP_LLM_BASE_URL=https://my-azure-endpoint.openai.azure.com/openai/
```

## Wire format differences (implemented)

| | Anthropic | OpenAI |
|---|---|---|
| Auth header | `x-api-key` | `Authorization: Bearer` |
| Extra header | `anthropic-version: 2023-06-01` | none |
| Request shape | `{system, messages:[{user}]}` | `{messages:[{system},{user}]}` |
| Endpoint | `{baseUrl}messages` | `{baseUrl}chat/completions` |
| Response path | `content[0].text` | `choices[0].message.content` |
