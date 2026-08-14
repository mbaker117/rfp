package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rfp.dto.*
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.security.MessageDigest

class LlmException(message: String) : RuntimeException(message)

data class AcceptanceEstimate(val probability: Int, val reasoning: String)

@Service
class LlmService(private val llmClient: LlmClient) {

    private val mapper = ObjectMapper().apply { findAndRegisterModules() }
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun call(systemPrompt: String, userMessage: String): String {
        val key = sha256("$systemPrompt|$userMessage")
        return cache.getOrPut(key) { llmClient.call(systemPrompt, userMessage) }
    }

    private fun parseJson(raw: String) = try {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .trimStart().removeSuffix("```").trimEnd()
        mapper.readTree(cleaned)
    } catch (_: Exception) {
        // Try to salvage truncated JSON: find all complete top-level objects in a "products" array
        repairTruncatedProductsJson(raw)
            ?: throw LlmException("Failed to parse LLM JSON: ${raw.take(300)}")
    }

    /**
     * When a large catalog response is truncated mid-JSON, reconstruct a valid document
     * from all complete product objects found so far.
     */
    private fun repairTruncatedProductsJson(raw: String): com.fasterxml.jackson.databind.JsonNode? {
        return try {
            val cleaned = raw.trim().removePrefix("```json").removePrefix("```").trimStart()
            // Find the products array start
            val arrayStart = cleaned.indexOf("[", cleaned.indexOf("\"products\"").takeIf { it >= 0 } ?: return null)
            if (arrayStart < 0) return null
            // Walk char-by-char to collect complete objects at depth 1
            val products = StringBuilder("[")
            var depth = 0
            var objStart = -1
            var addedCount = 0
            var i = arrayStart + 1
            while (i < cleaned.length) {
                when (cleaned[i]) {
                    '{' -> { if (depth == 0) objStart = i; depth++ }
                    '}' -> {
                        depth--
                        if (depth == 0 && objStart >= 0) {
                            if (addedCount > 0) products.append(",")
                            products.append(cleaned.substring(objStart, i + 1))
                            addedCount++
                            objStart = -1
                        }
                    }
                    ']' -> if (depth == 0) break
                }
                i++
            }
            products.append("]")
            if (addedCount == 0) null
            else mapper.readTree("{\"products\":$products}")
        } catch (_: Exception) { null }
    }

    // Task 1: parse raw catalog text into structured products
    fun parseCatalogBatch(rawText: String, knownClasses: List<ClassSchema>): List<ParsedProduct> {
        val classHint = if (knownClasses.isEmpty()) "No existing classes yet."
        else "Known classes and their attribute keys:\n" +
            knownClasses.joinToString("\n") { c ->
                "${c.name}: ${c.attributes.joinToString(", ") { "${it.name}(${it.datatype}${it.canonicalUnit?.let { u -> ", unit=$u" } ?: ""})" }}"
            }
        val system = """
            Extract all products from the raw catalog text (may be Arabic, English, or both).
            $classHint
            Use existing class names when the product fits. Create a new class_name only when none fit.
            Use existing attribute key names when the class matches; add new keys only when needed.
            For EVERY product, also capture inside "attributes":
              - "description": a short plain-text summary of the product (1-2 sentences, in English)
              - "manualLink": the URL to the product datasheet or manual page, if found on the page (null if not present)
            Respond ONLY with valid JSON — no markdown, no commentary:
            {"products":[{"className":string,"name":string,"mpn":string|null,
              "price":number|null,"currency":string,"attributes":{"description":string,"manualLink":string|null,...otherKeys}}]}
        """.trimIndent()
        val json = parseJson(llmClient.call(system, rawText.take(20000)))   // skip cache — site content varies
        val products = json["products"] ?: throw LlmException("LLM response missing 'products' key")
        return products.map { p ->
            ParsedProduct(
                className = p["className"].asText(),
                name = p["name"].asText(),
                mpn = p["mpn"]?.takeIf { !it.isNull }?.asText(),
                price = p["price"]?.takeIf { !it.isNull }?.let { BigDecimal(it.asText()) },
                currency = p["currency"]?.asText() ?: "JOD",
                attributes = mapper.readValue(p["attributes"].toString())
            )
        }
    }

    // Task 2: define a new product class schema
    fun defineClass(className: String, sampleProducts: List<String>): ClassDefinition {
        val system = """
            Define the attribute schema for a new product class named "$className".
            Based on the sample products, identify all relevant attributes.
            For each attribute, decide:
            - datatype: numeric | text | bool | enum
            - matchOp: eq (must match exactly) | gte (product must be >= required) | lte (product must be <= required)
            - canonicalUnit: SI unit for numeric attributes, null otherwise
            - allowedValues: list of values for enum type, empty otherwise
            Respond ONLY with valid JSON:
            {"className":string,"attributeDefs":[{"name":string,"label":string,
              "datatype":string,"matchOp":string,"canonicalUnit":string|null,"allowedValues":[]}]}
        """.trimIndent()
        val user = "Class: $className\nSamples:\n${sampleProducts.joinToString("\n")}"
        val json = parseJson(call(system, user))
        val attrDefs = json["attributeDefs"] ?: throw LlmException("LLM response missing 'attributeDefs' key")
        return ClassDefinition(
            className = json["className"]?.asText() ?: throw LlmException("LLM response missing 'className' key"),
            attributeDefs = attrDefs.map { d ->
                AttributeDefDto(
                    name = d["name"].asText(),
                    label = d["label"].asText(),
                    datatype = d["datatype"].asText(),
                    matchOp = d["matchOp"].asText(),
                    canonicalUnit = d["canonicalUnit"]?.takeIf { !it.isNull }?.asText(),
                    allowedValues = d["allowedValues"]?.map { it.asText() } ?: emptyList()
                )
            }
        )
    }

    // Task 2b: given navigation links extracted from homepage, find product catalog URLs
    fun identifyProductUrls(baseUrl: String, navigationLinks: String): List<String> {
        val system = """
            You are analyzing navigation links extracted from a supplier website.
            Each line is "link label -> href" or just a URL/path.
            Identify all URLs or paths that lead to product catalog pages, product listings, or product category pages.
            Ignore: contact, about, blog, news, login, register, social media, privacy, terms, FAQ, careers.
            Return ONLY a JSON array of up to 8 URLs (absolute or relative paths starting with / or http):
            ["url1", "url2"]
            If no product pages are found return [].
            Do not add commentary or markdown.
        """.trimIndent()
        val user = "Base URL: $baseUrl\n\nNavigation links:\n${navigationLinks.take(8000)}"
        return try {
            val raw = llmClient.call(system, user)   // skip cache — each site is unique
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .trimStart().removeSuffix("```").trimEnd()
            val node = mapper.readTree(cleaned)
            if (node.isArray) node.map { it.asText() }.filter { it.isNotBlank() }
            else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Task 3: parse RFP/tender document into structured requirement lines
    fun parseTenderLines(rawText: String, knownClasses: List<ClassSchema>): List<ParsedTenderLine> {
        val classHint = if (knownClasses.isEmpty()) "No known classes yet — use descriptive class names."
        else "Known product classes and their attribute keys:\n" +
            knownClasses.joinToString("\n") { c ->
                "${c.name}: ${c.attributes.joinToString(", ") { it.name }}"
            }
        val system = """
            Extract all requirement lines from this RFP/tender document.
            $classHint
            Match each line to the closest known class. Use its attribute key names in output.
            For each line emit the required attribute values (not what the product offers — what is required).
            Respond ONLY with valid JSON:
            {"lines":[{"className":string,"description":string,"qty":number|null,
              "qtyUnit":string|null,"attributes":{key:value}}]}
            Handle Arabic and English. Do not add commentary.
        """.trimIndent()
        val json = parseJson(call(system, rawText.take(12000)))
        val lines = json["lines"] ?: throw LlmException("LLM response missing 'lines' key")
        return lines.map { l ->
            ParsedTenderLine(
                className = l["className"].asText(),
                description = l["description"].asText(),
                qty = l["qty"]?.takeIf { !it.isNull }?.let { BigDecimal(it.asText()) },
                qtyUnit = l["qtyUnit"]?.takeIf { !it.isNull }?.asText(),
                attributes = mapper.readValue(l["attributes"].toString())
            )
        }
    }

    // Task 4: estimate procurement acceptance probability for a matched product
    fun estimateAcceptance(
        lineDescription: String,
        lineAttrs: Map<String, Any>,
        productName: String,
        productMpn: String?,
        productAttrs: Map<String, Any>,
        verdictsStr: String,
        price: java.math.BigDecimal?,
        currency: String
    ): AcceptanceEstimate {
        val system = """
            You are a procurement analyst. Estimate the probability (0–100) that a
            procurement reviewer would accept the offered product as fulfilling the
            stated requirement. Consider: technical compliance (attribute verdicts),
            price competitiveness, brand reputation and market acceptance, and whether
            this is a reasonable substitution. Return ONLY valid JSON:
            {"probability": <integer 0-100>, "reasoning": "<one sentence>"}
        """.trimIndent()
        val priceStr = if (price != null) "$price $currency" else "not available"
        val lineAttrsStr = lineAttrs.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        val productAttrsStr = productAttrs.entries
            .filter { it.key !in setOf("description", "manualLink") }
            .joinToString(", ") { "${it.key}: ${it.value}" }
        val user = """
            Requirement: $lineDescription
            Required attributes: $lineAttrsStr
            Offered product: $productName${if (productMpn != null) " ($productMpn)" else ""}
            Offered attributes: $productAttrsStr
            Attribute verdicts: $verdictsStr
            Price: $priceStr
        """.trimIndent()
        val json = parseJson(call(system, user))
        return AcceptanceEstimate(
            probability = json["probability"]?.asInt() ?: 0,
            reasoning = json["reasoning"]?.asText() ?: ""
        )
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
