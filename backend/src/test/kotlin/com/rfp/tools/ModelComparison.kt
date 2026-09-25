package com.rfp.tools

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rfp.dto.ClassSchema
import com.rfp.dto.ParsedProduct
import com.rfp.service.CatalogChunker
import com.rfp.service.LlmClient
import com.rfp.service.LlmException
import com.rfp.service.LlmResponse
import com.rfp.service.LlmService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Extracts the same catalog pages with several models and writes the products of each to disk, so their coverage
 * and spec quality can be compared. Uses the production prompt ([LlmService.parseCatalogChunk]), chunking and
 * continuation loop, so only the model differs. Not part of the suite — it calls the paid API. Run with:
 *
 * RFP_MODEL_COMPARE=1 RFP_LLM_API_KEY=... mvn test -Dtest=ModelComparison
 *
 * Env: RFP_COMPARE_PDF, RFP_COMPARE_PAGES ("17-30"), RFP_COMPARE_MODELS (comma separated),
 * RFP_COMPARE_CLASSES (JSON file of the known classes, as the importer would pass them), RFP_COMPARE_OUT.
 */
@EnabledIfEnvironmentVariable(named = "RFP_MODEL_COMPARE", matches = ".+")
class ModelComparison {

    private val mapper = ObjectMapper().apply { findAndRegisterModules() }

    /**
     * Same shape as [com.rfp.service.OpenAiLlmClient], but also records token usage. Newer OpenAI models reject
     * `temperature` and `max_tokens`, so the first rejection switches this model to `max_completion_tokens` and
     * no temperature.
     */
    private class OpenAiUsageRecordingClient(
        val apiKey: String, val model: String, val maxTokens: Int = 16000
    ) : UsageRecording {
        val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(600, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val mapper = ObjectMapper()
        override var inputTokens = 0L
        override var outputTokens = 0L
        /**
         * GPT-5 models are reasoning models: they take `max_completion_tokens` rather than `max_tokens`, and
         * without a minimal reasoning effort they spend the whole budget thinking and return empty content.
         */
        var newStyle = model.startsWith("gpt-5-")
        var lowReasoning = model.startsWith("gpt-5-")

        override fun call(systemPrompt: String, userMessage: String) = callDetailed(systemPrompt, userMessage).text

        override fun callDetailed(systemPrompt: String, userMessage: String): LlmResponse =
            try {
                post(systemPrompt, userMessage)
            } catch (e: LlmException) {
                val m = e.message.orEmpty()
                val paramProblem = m.contains("max_tokens") || m.contains("max_completion_tokens") || m.contains("temperature")
                val allReasoning = m.contains("Empty response (finish_reason length)")
                when {
                    paramProblem && !newStyle -> newStyle = true
                    allReasoning && !lowReasoning -> { lowReasoning = true; newStyle = true }
                    else -> throw e
                }
                post(systemPrompt, userMessage)
            }

        private fun post(systemPrompt: String, userMessage: String): LlmResponse {
            val body = mapper.writeValueAsString(buildMap<String, Any> {
                put("model", model)
                if (newStyle) {
                    // Reasoning tokens come out of the same budget as the answer, so give a reasoning model room.
                    put("max_completion_tokens", if (lowReasoning) maxTokens * 2 else maxTokens)
                } else {
                    put("max_tokens", maxTokens); put("temperature", 0)
                }
                if (lowReasoning) put("reasoning_effort", System.getenv("RFP_COMPARE_REASONING") ?: "low")
                put("messages", listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userMessage)
                ))
            })
            val request = Request.Builder().url("https://api.openai.com/v1/chat/completions")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $apiKey").build()
            return http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw LlmException("HTTP ${response.code}: ${raw.take(400)}")
                val json = mapper.readTree(raw)
                inputTokens += json["usage"]?.get("prompt_tokens")?.asLong() ?: 0
                outputTokens += json["usage"]?.get("completion_tokens")?.asLong() ?: 0
                val choice = json["choices"]?.get(0)
                val text = choice?.get("message")?.get("content")?.asText()?.takeIf { it.isNotBlank() }
                LlmResponse(
                    text ?: throw LlmException("Empty response (finish_reason ${choice?.get("finish_reason")?.asText()})"),
                    truncated = choice.get("finish_reason")?.asText() == "length"
                )
            }
        }
    }

    private interface UsageRecording : LlmClient {
        var inputTokens: Long
        var outputTokens: Long
    }

    /** Same shape as [com.rfp.service.AnthropicLlmClient], but also records token usage per call. */
    private class UsageRecordingClient(val apiKey: String, val model: String, val maxTokens: Int = 16000) : UsageRecording {
        val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(600, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val mapper = ObjectMapper()
        override var inputTokens = 0L
        override var outputTokens = 0L
        /**
         * Claude 5 models reject sampling parameters and think by default, which would spend the whole output
         * budget before any JSON is written. The first rejection switches this model to that shape: no
         * temperature, thinking off — the same request the older models get.
         */
        var claude5 = false

        override fun call(systemPrompt: String, userMessage: String) = callDetailed(systemPrompt, userMessage).text

        override fun callDetailed(systemPrompt: String, userMessage: String): LlmResponse =
            try {
                post(systemPrompt, userMessage)
            } catch (e: LlmException) {
                if (claude5 || e.message?.contains("`temperature` is deprecated") != true) throw e
                claude5 = true
                post(systemPrompt, userMessage)
            }

        private fun post(systemPrompt: String, userMessage: String): LlmResponse {
            val body = mapper.writeValueAsString(buildMap<String, Any> {
                put("model", model); put("max_tokens", maxTokens)
                if (claude5) put("thinking", mapOf("type" to "disabled")) else put("temperature", 0)
                put("system", systemPrompt)
                put("messages", listOf(mapOf("role" to "user", "content" to userMessage)))
            })
            val request = Request.Builder().url("https://api.anthropic.com/v1/messages")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01").build()
            return http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw LlmException("HTTP ${response.code}: ${raw.take(400)}")
                val json = mapper.readTree(raw)
                inputTokens += json["usage"]?.get("input_tokens")?.asLong() ?: 0
                outputTokens += json["usage"]?.get("output_tokens")?.asLong() ?: 0
                // The text block is not always first: a thinking block can precede it.
                val text = json["content"]?.firstOrNull { it["type"]?.asText() == "text" }?.get("text")?.asText()
                LlmResponse(
                    text ?: throw LlmException("No text block in response (stop_reason ${json["stop_reason"]?.asText()})"),
                    truncated = json["stop_reason"]?.asText() == "max_tokens"
                )
            }
        }
    }

    @Test
    fun `extract the same pages with each model`() {
        val pdf = File(env("RFP_COMPARE_PDF"))
        val (first, last) = env("RFP_COMPARE_PAGES", "17-30").split("-").map { it.toInt() }
        val models = env("RFP_COMPARE_MODELS", "claude-sonnet-4-6").split(",").map { it.trim() }
        val outDir = File(env("RFP_COMPARE_OUT", "target/model-comparison")).apply { mkdirs() }
        val apiKey = env("RFP_LLM_API_KEY")
        val knownClasses: List<ClassSchema> = System.getenv("RFP_COMPARE_CLASSES")
            ?.let { mapper.readValue(File(it)) } ?: emptyList()

        val text = Loader.loadPDF(pdf).use { doc ->
            PDFTextStripper().apply { pageEnd = "\u000C"; startPage = first; endPage = last }.getText(doc)
        }
        val chunks = CatalogChunker.chunk(text, 12_000)
        println("pages $first-$last -> ${chunks.size} chunk(s), ${text.length} chars, ${knownClasses.size} known class(es)")

        models.forEach { model ->
            val client: UsageRecording =
                if (model.startsWith("gpt")) OpenAiUsageRecordingClient(env("OPENAI_API_KEY"), model)
                else UsageRecordingClient(apiKey, model)
            val llm = LlmService(client)
            val products = mutableListOf<ParsedProduct>()
            var calls = 0
            var failed = 0
            var truncatedChunks = 0
            val started = System.currentTimeMillis()
            chunks.forEachIndexed { i, chunk ->
                val seen = linkedSetOf<String>()
                val before = products.size
                var truncated = false
                var chunkCalls = 0
                do {
                    val result = try {
                        llm.parseCatalogChunk(chunk, knownClasses, seen.toList())
                    } catch (e: Exception) {
                        println("  [$model] chunk ${i + 1} call ${chunkCalls + 1} failed: ${e.message?.take(200)}")
                        failed++
                        break
                    }
                    chunkCalls++
                    val fresh = result.products.filter { seen.add(identity(it)) }
                    products += fresh
                    truncated = result.truncated
                } while (truncated && fresh.isNotEmpty() && chunkCalls < 8)
                if (truncated) truncatedChunks++
                calls += chunkCalls
                println("  [$model] chunk ${i + 1}/${chunks.size}: ${products.size - before} product(s) in $chunkCalls call(s)")
            }
            val seconds = (System.currentTimeMillis() - started) / 1000
            File(outDir, "$model.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(products))
            File(outDir, "$model.summary.tsv").writeText(
                "model\tproducts\tcalls\tfailed_chunks\ttruncated_chunks\tinput_tokens\toutput_tokens\tseconds\n" +
                    "$model\t${products.size}\t$calls\t$failed\t$truncatedChunks\t${client.inputTokens}\t${client.outputTokens}\t$seconds\n"
            )
            println("[$model] ${products.size} products, $calls calls, ${client.inputTokens} in / ${client.outputTokens} out tokens, ${seconds}s")
        }
    }

    private fun identity(p: ParsedProduct): String =
        (p.attributes["item_no"]?.toString()?.takeIf { it.isNotBlank() } ?: p.mpn?.takeIf { it.isNotBlank() } ?: p.name).trim()

    private fun env(name: String, default: String? = null): String =
        System.getenv(name) ?: default ?: error("$name is required")
}
