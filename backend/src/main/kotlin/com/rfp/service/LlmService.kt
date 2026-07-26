package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rfp.dto.*
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.security.MessageDigest

class LlmException(message: String) : RuntimeException(message)

@Service
class LlmService(private val llmClient: LlmClient) {

    private val mapper = ObjectMapper().apply { findAndRegisterModules() }
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun call(systemPrompt: String, userMessage: String): String {
        val key = sha256("$systemPrompt|$userMessage")
        return cache.getOrPut(key) { llmClient.call(systemPrompt, userMessage) }
    }

    private fun parseJson(raw: String) = try {
        // Strip markdown code fences that some models add
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .trimStart().removeSuffix("```").trimEnd()
        mapper.readTree(cleaned)
    } catch (e: Exception) {
        throw LlmException("Failed to parse LLM JSON: ${raw.take(300)}")
    }

    // Task 1: parse raw catalog text into structured products
    fun parseCatalogBatch(rawText: String, knownClasses: List<ClassSchema>): List<ParsedProduct> {
        val classHint = if (knownClasses.isEmpty()) "No existing classes yet."
        else "Known classes and their attribute keys:\n" +
            knownClasses.joinToString("\n") { c ->
                "${c.name}: ${c.attributes.joinToString(", ") { "${it.name}(${it.datatype}${it.canonicalUnit?.let { u -> ", unit=$u" } ?: ""})" }}"
            }
        val system = """
            Extract all products from the raw catalog text.
            $classHint
            Use existing class names when the product fits. Create a new class_name only when none fit.
            Use existing attribute key names when the class matches; add new keys only when needed.
            Respond ONLY with valid JSON:
            {"products":[{"className":string,"name":string,"mpn":string|null,
              "price":number|null,"currency":string,"attributes":{key:value}}]}
            Handle Arabic and English. Do not add commentary.
        """.trimIndent()
        val json = parseJson(call(system, rawText.take(12000)))
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

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
