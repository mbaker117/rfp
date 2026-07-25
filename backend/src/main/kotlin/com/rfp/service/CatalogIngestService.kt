// backend/src/main/kotlin/com/rfp/service/CatalogIngestService.kt
package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.rfp.domain.*
import com.rfp.dto.*
import com.rfp.repository.*
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

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
    @Lazy private val scrapeService: ScrapeService
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
            ingestRepo.save(ingest.copy(status = "FAILED", errorMsg = e.message, finishedAt = Instant.now()))
        }
    }

    @Async("taskExecutor")
    open fun ingestScrape(supplierId: Long) {
        val supplier = supplierRepo.findById(supplierId).orElseThrow()
        val ingest = ingestRepo.save(CatalogIngest(supplier = supplier, kind = "scrape", status = "RUNNING", startedAt = Instant.now()))
        try {
            val html = scrapeService.crawlWebsite(supplier.officialWebsite
                ?: throw IllegalStateException("No website for supplier ${supplier.name}"))
            runIngest(ingest, html, "scrape")
        } catch (e: Exception) {
            ingestRepo.save(ingest.copy(status = "FAILED", errorMsg = e.message, finishedAt = Instant.now()))
        }
    }

    // Internal — package-private for tests
    fun runIngest(ingest: CatalogIngest, rawText: String, source: String) {
        val supplier = ingest.supplier
        val allClasses = productClassRepo.findAll()
        val knownClasses = allClasses.map { pc ->
            ClassSchema(pc.name, attrDefRepo.findByProductClassId(pc.id).map {
                AttrSchema(it.name, it.datatype, it.canonicalUnit)
            })
        }

        val parsed = llmService.parseCatalogBatch(rawText, knownClasses)
        val seenIds = mutableSetOf<Long>()

        parsed.forEach { p ->
            val productClass = resolveOrCreateClass(p.className, parsed
                .filter { it.className == p.className }
                .take(5).map { it.name + " " + it.attributes.toString() })

            val defs = attrDefRepo.findByProductClassId(productClass.id)
            val normalizedAttrs = unitService.normalizeAttributes(p.attributes, defs)
            val attrsJson = mapper.writeValueAsString(normalizedAttrs)

            val existing = (p.mpn?.let { productRepo.findBySupplierIdAndMpnIgnoreCase(supplier.id, it) }
                ?: productRepo.findBySupplierIdAndNameIgnoreCase(supplier.id, p.name))

            val product = if (existing != null) {
                seenIds.add(existing.id)
                productRepo.save(existing.copy(attributes = attrsJson, isStale = false, updatedAt = Instant.now()))
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

            // Handle price separately — never in LLM context
            if (p.price != null) {
                val existingPrice = productPriceRepo.findById(product.id).orElse(null)
                if (existingPrice != null && existingPrice.price != null &&
                    existingPrice.price.compareTo(p.price) != 0) {
                    priceHistoryRepo.save(ProductPriceHistory(product = product,
                        price = existingPrice.price, currency = existingPrice.currency))
                }
                productPriceRepo.save(ProductPrice(productId = product.id, product = product,
                    price = p.price, currency = p.currency))
            }
        }

        // Mark products not seen in this run as stale
        productRepo.findBySupplierId(supplier.id)
            .filter { it.id !in seenIds && !it.isStale }
            .forEach { productRepo.save(it.copy(isStale = true)) }

        ingestRepo.save(ingest.copy(status = "DONE", finishedAt = Instant.now()))
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
}
