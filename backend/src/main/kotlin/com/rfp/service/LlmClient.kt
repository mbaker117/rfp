package com.rfp.service

interface LlmClient {
    fun call(systemPrompt: String, userMessage: String): String
}
