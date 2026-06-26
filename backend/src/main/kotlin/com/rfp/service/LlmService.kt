package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rfp.domain.Instrument
import com.rfp.domain.enums.MatchStatus
import com.rfp.dto.ExtractedRequirement
import com.rfp.dto.MatchResult
import com.rfp.dto.ScrapedInstrument
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.security.MessageDigest

@Service
class LlmService(
    @Value("\${rfp.llm.api-key}") private val apiKey: String,
    @Value("\${rfp.llm.model}") private val model: String,
    @Value("\${rfp.llm.base-url:https://api.anthropic.com/v1/}") private val baseUrl: String = "https://api.anthropic.com/v1/"
) {
    private val client = OkHttpClient()
    private val mapper = ObjectMapper().apply { findAndRegisterModules() }
    private val cache = mutableMapOf<String, String>()

    private fun call(systemPrompt: String, userMessage: String): String {
        val cacheKey = sha256("$systemPrompt|$userMessage")
        cache[cacheKey]?.let { return it }

        val body = mapper.writeValueAsString(mapOf(
            "model" to model,
            "max_tokens" to 4096,
            "system" to systemPrompt,
            "messages" to listOf(mapOf("role" to "user", "content" to userMessage))
        ))
        val request = Request.Builder()
            .url("${baseUrl}messages")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .build()
        val response = client.newCall(request).execute()
        val json = mapper.readTree(response.body!!.string())
        val text = json["content"][0]["text"].asText()
        cache[cacheKey] = text
        return text
    }

    fun extractRequirements(documentText: String): List<ExtractedRequirement> {
        val system = """
            Extract all instrument requirements from the document text.
            Respond ONLY with valid JSON matching this schema:
            {"requirements": [{"rawText": string, "name": string, "quantity": number|null, "specs": {key: string}}]}
            Handle Arabic and English text. Do not add commentary.
        """.trimIndent()
        val json = mapper.readTree(call(system, documentText))
        return json["requirements"].map {
            ExtractedRequirement(
                rawText = it["rawText"].asText(),
                name = it["name"].asText(),
                quantity = it["quantity"]?.takeIf { n -> !n.isNull }?.asInt(),
                specs = mapper.readValue(it["specs"].toString())
            )
        }
    }

    fun structureScrapeData(rawHtml: String, companyName: String): List<ScrapedInstrument> {
        val system = """
            You are given raw HTML/text from $companyName's product catalog.
            Extract all instruments/products. Respond ONLY with valid JSON:
            {"instruments": [{"description": string, "normalizedName": string, "manualLink": string|null, "price": number|null, "currency": string}]}
            Currency default is JOD. Handle Arabic product names.
        """.trimIndent()
        val json = mapper.readTree(call(system, rawHtml.take(12000)))
        return json["instruments"].map {
            ScrapedInstrument(
                description = it["description"].asText(),
                normalizedName = it["normalizedName"].asText(),
                manualLink = it["manualLink"]?.takeIf { n -> !n.isNull }?.asText(),
                price = it["price"]?.takeIf { n -> !n.isNull }?.let { v -> BigDecimal(v.asText()) },
                currency = it["currency"]?.asText() ?: "JOD"
            )
        }
    }

    fun scoreMatch(requirement: ExtractedRequirement, candidates: List<Instrument>): MatchResult {
        if (candidates.isEmpty()) return MatchResult(null, 0, "No candidates", MatchStatus.NOT_FOUND)
        val candidateList = candidates.take(10).joinToString("\n") {
            "ID:${it.id} | ${it.normalizedName} | ${it.description}"
        }
        val system = """
            Match the required instrument to the best candidate from the list.
            Respond ONLY with valid JSON:
            {"matchedInstrumentId": number|null, "score": number (0-100), "reason": string, "status": "MATCHED"|"PARTIAL"|"NOT_FOUND"}
            MATCHED = score >= 80, PARTIAL = 40-79, NOT_FOUND = < 40.
        """.trimIndent()
        val user = "Required: ${requirement.name} specs=${requirement.specs}\n\nCandidates:\n$candidateList"
        val json = mapper.readTree(call(system, user))
        val status = when (json["status"].asText()) {
            "MATCHED" -> MatchStatus.MATCHED
            "PARTIAL" -> MatchStatus.PARTIAL
            else -> MatchStatus.NOT_FOUND
        }
        return MatchResult(
            matchedInstrumentId = json["matchedInstrumentId"]?.takeIf { !it.isNull }?.asLong(),
            score = json["score"].asInt(),
            reason = json["reason"].asText(),
            status = status
        )
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
