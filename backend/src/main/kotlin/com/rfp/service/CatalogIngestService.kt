// backend/src/main/kotlin/com/rfp/service/CatalogIngestService.kt
package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rfp.domain.*
import com.rfp.dto.*
import com.rfp.repository.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

@Service
open class CatalogIngestService(
    private val supplierRepo: SupplierRepository,
    private val productClassRepo: ProductClassRepository,
    private val attrDefRepo: AttributeDefRepository,
    private val productRepo: ProductRepository,
    private val productPriceRepo: ProductPriceRepository,
    private val priceHistoryRepo: ProductPriceHistoryRepository,
    private val ingestRepo: CatalogIngestRepository,
    private val llmService: LlmService,
    private val unitService: UnitNormalizationService,
    private val docParser: DocumentParsingService,
    @Value("\${rfp.catalog.chunk-chars:12000}") private val chunkChars: Int = 12_000,
    @Value("\${rfp.catalog.max-chunks:150}") private val maxChunks: Int = 150,
    @Value("\${rfp.catalog.parallelism:4}") private val parallelism: Int = 4,
    private val chunkCacheRepo: CatalogChunkCacheRepository? = null,
    private val schemaService: AttributeSchemaService? = null,
    @Value("\${rfp.llm.model:}") private val model: String = ""
) {
    private val mapper = ObjectMapper().apply { findAndRegisterModules() }

    @Async("taskExecutor")
    open fun ingestFile(supplierId: Long, bytes: ByteArray, fileType: String, kind: String) {
        val supplier = supplierRepo.findById(supplierId).orElseThrow()
        val ingest = ingestRepo.save(CatalogIngest(supplier = supplier, kind = kind, filename = fileType, status = "RUNNING", startedAt = Instant.now()))
        try {
            val rawText = docParser.extractText(bytes, fileType)
            runIngest(ingest, rawText, "upload")
        } catch (e: Exception) {
            // Reload so the step log and product count written during the run are kept.
            val latest = ingestRepo.findById(ingest.id).orElse(ingest)
            ingestRepo.save(latest.copy(status = "FAILED", errorMsg = e.message, finishedAt = Instant.now()))
            supplierRepo.save(supplier.copy(scrapeStatus = "FAILED"))
        }
    }

    /**
     * Sends the document to the LLM chunk by chunk (up to [parallelism] calls at a time) and saves
     * products in document order. Unseen products are marked stale only when every chunk of the
     * whole document was processed, so a capped or partially failed run never stales products it
     * simply did not read.
     */
    // Internal — package-private for tests
    fun runIngest(ingest: CatalogIngest, rawText: String, source: String) {
        val supplier = ingest.supplier
        val chunks = CatalogChunker.chunk(rawText, chunkChars)
        if (chunks.isEmpty()) throw IllegalStateException("No text could be extracted from the document (is it a scanned PDF?)")
        val toProcess = chunks.take(maxChunks)
        val capped = chunks.size > toProcess.size

        var current = ingest
        fun log(line: String, itemsFound: Int? = current.itemsFound) {
            current = ingestRepo.save(current.copy(
                stepLog = current.stepLog?.trimEnd()?.let { "$it\n$line" } ?: line,
                itemsFound = itemsFound
            ))
        }

        log("Document: ${rawText.length} chars in ${chunks.size} chunk(s)" +
            if (capped) "; processing the first ${toProcess.size} only (capped by rfp.catalog.max-chunks, the rest of the document is skipped)" else "")

        val seenIds = mutableSetOf<Long>()
        val touchedClassIds = mutableSetOf<Long>()
        var failedChunks = 0
        var incompleteChunks = 0
        var consecutiveFailures = 0
        val workers = parallelism.coerceAtLeast(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            toProcess.withIndex().chunked(workers).forEach { wave ->
                val knownClasses = knownClassSchemas()
                val futures = wave.map { (i, text) -> i to pool.submit(Callable { extractCached(text, knownClasses) }) }
                for ((i, future) in futures) {
                    val label = "Chunk ${i + 1}/${toProcess.size}"
                    val outcome = try {
                        future.get()
                    } catch (e: ExecutionException) {
                        val cause = e.cause ?: e
                        failedChunks++
                        consecutiveFailures++
                        log("$label failed: ${cause.message}")
                        if (isFatal(cause)) {
                            futures.forEach { it.second.cancel(true) }
                            throw cause
                        }
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            futures.forEach { it.second.cancel(true) }
                            throw LlmException("Stopped after $MAX_CONSECUTIVE_FAILURES consecutive chunks failed. Last error: ${cause.message}")
                        }
                        continue
                    }
                    consecutiveFailures = 0
                    if (outcome.stillTruncated) incompleteChunks++
                    saveProducts(outcome.products, supplier, source, seenIds, touchedClassIds)
                    log("$label: ${outcome.products.size} product(s)" + when {
                        outcome.fromCache -> " (from cache)"
                        outcome.stillTruncated -> " (WARNING: some rows may be missing, the answer was still cut off after ${outcome.calls} calls)"
                        outcome.calls > 1 -> " (answer hit the output limit; continued in ${outcome.calls} calls)"
                        else -> ""
                    }, seenIds.size)
                }
            }
        } finally {
            pool.shutdownNow()
        }
        if (failedChunks == toProcess.size) throw LlmException("All ${toProcess.size} chunk(s) failed")

        if (!capped && failedChunks == 0 && incompleteChunks == 0) {
            // Mark ingest-sourced products not seen in this run as stale.
            // Exclude crawler-discovered products (canonicalSourceUrl != null) to prevent
            // a PDF upload from staling products that were found by the adaptive crawler.
            productRepo.findBySupplierId(supplier.id)
                .filter { it.id !in seenIds && !it.isStale && it.canonicalSourceUrl == null }
                .forEach { productRepo.save(it.copy(isStale = true)) }
        } else {
            log("Existing products were not marked stale because the document was only partially processed")
        }

        schemaService?.let { schema ->
            // Never fail an import over its class schemas; the admin endpoint can re-run the sync.
            try {
                val r = schema.syncClasses(touchedClassIds)
                if (r.added + r.fixed + r.removed + r.merged + r.values > 0)
                    log("Attribute definitions: ${r.added} added, ${r.fixed} corrected, ${r.removed} identifier(s) removed" +
                        (if (r.merged > 0) ", ${r.merged} duplicate spec name(s) merged" else "") +
                        (if (r.values > 0) ", ${r.values} value spelling(s) merged" else ""))
            } catch (e: Exception) {
                log("Attribute definitions were not updated: ${e.message}")
            }
        }

        log("Finished: ${seenIds.size} product(s) from ${toProcess.size - failedChunks}/${toProcess.size} chunk(s)", seenIds.size)
        ingestRepo.save(current.copy(status = "DONE", itemsFound = seenIds.size, finishedAt = Instant.now()))
        // Mark the supplier as freshly scraped so CatalogRefreshJob picks up the right cutoff
        supplierRepo.save(ingest.supplier.copy(scrapeStatus = "DONE", lastScrapedAt = Instant.now()))
    }

    private data class ChunkOutcome(
        val products: List<ParsedProduct>,
        val calls: Int,
        val stillTruncated: Boolean,
        val fromCache: Boolean = false
    )

    /** Serves the chunk from [chunkCacheRepo] when the same text was already extracted with this prompt and model. */
    private fun extractCached(text: String, knownClasses: List<ClassSchema>): ChunkOutcome {
        val repo = chunkCacheRepo ?: return extractCompletely(text, knownClasses)
        val key = sha256("$CATALOG_PROMPT_VERSION|$model|$text")
        repo.findByCacheKey(key)?.let { cached ->
            return ChunkOutcome(mapper.readValue(cached.products), calls = 0, stillTruncated = false, fromCache = true)
        }
        val outcome = extractCompletely(text, knownClasses)
        if (!outcome.stillTruncated) {
            try {
                repo.save(CatalogChunkCache(cacheKey = key, products = mapper.writeValueAsString(outcome.products),
                    productCount = outcome.products.size))
            } catch (_: DataIntegrityViolationException) { /* same chunk cached concurrently */ }
        }
        return outcome
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * Extracts [text]. While the answer is cut off at the output token limit, asks again for the same whole
     * text (so every table heading stays in view) listing what was already extracted, up to
     * [MAX_CALLS_PER_CHUNK] calls. Stops early if a call adds nothing new.
     */
    private fun extractCompletely(text: String, knownClasses: List<ClassSchema>): ChunkOutcome {
        val products = mutableListOf<ParsedProduct>()
        val seen = linkedSetOf<String>()
        var calls = 0
        var truncated: Boolean
        do {
            val result = try {
                llmService.parseCatalogChunk(text, knownClasses, seen.toList())
            } catch (e: LlmException) {
                // A failed continuation must not throw away what the earlier calls extracted:
                // keep it and flag the chunk as incomplete. The first call, and fatal errors, still fail the chunk.
                if (calls == 0 || isFatal(e)) throw e
                return ChunkOutcome(products, calls + 1, stillTruncated = true)
            }
            calls++
            val fresh = result.products.filter { seen.add(identity(it)) }
            products += fresh
            truncated = result.truncated
        } while (truncated && fresh.isNotEmpty() && calls < MAX_CALLS_PER_CHUNK)
        return ChunkOutcome(products, calls, stillTruncated = truncated)
    }

    /** The supplier's item number is the most specific identifier in a catalog, then the part number. */
    private fun identity(p: ParsedProduct): String =
        (p.attributes["item_no"]?.toString()?.takeIf { it.isNotBlank() } ?: p.mpn?.takeIf { it.isNotBlank() } ?: p.name).trim()

    private fun knownClassSchemas(): List<ClassSchema> =
        productClassRepo.findAll().map { pc ->
            ClassSchema(pc.name, attrDefRepo.findByProductClassId(pc.id).map {
                AttrSchema(it.name, it.datatype, it.canonicalUnit)
            })
        }

    /** Credit, auth and permission errors fail every chunk the same way, so stop at the first one. */
    private fun isFatal(e: Throwable): Boolean =
        e is LlmException && e.message?.let { FATAL_LLM_ERROR.containsMatchIn(it) } == true

    /**
     * Matches an extracted product to an existing row: supplier item number, then part number (only a row with no
     * conflicting item number), and by name only when the product has neither identifier. Catalog names are generic
     * ("Capacitor-Start AC Motor 1/2 HP 56C"), so a name match for an identified product would merge different items.
     */
    private fun findExisting(p: ParsedProduct, supplierId: Long): Product? {
        val itemNo = p.attributes["item_no"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val mpn = p.mpn?.trim()?.takeIf { it.isNotEmpty() }
        itemNo?.let { productRepo.findAllBySupplierIdAndItemNo(supplierId, it).firstOrNull()?.let { found -> return found } }
        if (mpn != null) {
            return productRepo.findAllBySupplierIdAndMpnIgnoreCase(supplierId, mpn)
                .firstOrNull { row -> itemNo == null || storedItemNo(row).let { it == null || it.equals(itemNo, ignoreCase = true) } }
        }
        if (itemNo != null) return null
        return productRepo.findAllBySupplierIdAndNameIgnoreCase(supplierId, p.name).firstOrNull()
    }

    private fun storedItemNo(product: Product): String? =
        product.attributes?.let { runCatching { mapper.readTree(it).path("item_no").asText(null) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    private fun saveProducts(
        parsed: List<ParsedProduct>, supplier: Supplier, source: String,
        seenIds: MutableSet<Long>, touchedClassIds: MutableSet<Long> = mutableSetOf()
    ) {
        parsed.forEach { p ->
            val productClass = resolveOrCreateClass(p.className, parsed
                .filter { it.className == p.className }
                .take(5).map { it.name + " " + it.attributes.toString() })
            touchedClassIds += productClass.id

            val defs = attrDefRepo.findByProductClassId(productClass.id)
            val canonicalAttrs = schemaService?.canonicalize(productClass.id, p.attributes) ?: p.attributes
            val normalizedAttrs = unitService.normalizeAttributes(canonicalAttrs, defs)
            val attrsJson = mapper.writeValueAsString(normalizedAttrs)

            val existing = findExisting(p, supplier.id)

            val product = if (existing != null) {
                seenIds.add(existing.id)
                // Identifiers and name are refreshed too, which also repairs rows an older name-based match merged.
                productRepo.save(existing.copy(
                    name = p.name,
                    mpn = p.mpn?.takeIf { it.isNotBlank() } ?: existing.mpn,
                    productClass = productClass,
                    attributes = attrsJson,
                    isStale = false,
                    updatedAt = Instant.now()
                ))
            } else {
                val saved = productRepo.save(Product(
                    supplier = supplier,
                    productClass = productClass,
                    name = p.name,
                    mpn = p.mpn,
                    attributes = attrsJson,
                    source = source
                ))
                seenIds.add(saved.id)
                saved
            }

            // Handle price separately — never in LLM context; isolated try so one bad price
            // doesn't abort the whole ingest
            if (p.price != null) {
                try {
                    val existingPrice = productPriceRepo.findById(product.id).orElse(null)
                    if (existingPrice != null && existingPrice.price != null &&
                        existingPrice.price.compareTo(p.price) != 0) {
                        priceHistoryRepo.save(ProductPriceHistory(product = product,
                            price = existingPrice.price, currency = existingPrice.currency))
                    }
                    productPriceRepo.save(ProductPrice(productId = product.id,
                        price = p.price, currency = p.currency))
                } catch (_: Exception) { /* price save failed; product already saved, continue */ }
            }
        }
    }

    private fun resolveOrCreateClass(className: String, samples: List<String>): ProductClass {
        productClassRepo.findByNameIgnoreCase(className)?.let { return it }
        val definition = llmService.defineClass(className, samples)
        val productClass = productClassRepo.save(ProductClass(name = definition.className, autoCreated = true))
        definition.attributeDefs.forEach { d ->
            attrDefRepo.save(AttributeDef(
                productClass = productClass,
                name = d.name, label = d.label,
                datatype = d.datatype, matchOp = d.matchOp,
                canonicalUnit = d.canonicalUnit,
                allowedValues = d.allowedValues.toTypedArray()
            ))
        }
        return productClass
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val MAX_CALLS_PER_CHUNK = 8
        val FATAL_LLM_ERROR = Regex("""LLM API error (400|401|403)\b""")
    }
}
