package com.rfp.service

import com.rfp.domain.*
import com.rfp.dto.*
import com.rfp.repository.*
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

class CatalogIngestChunkingTest {

    private val supplierRepo = mockk<SupplierRepository>()
    private val productClassRepo = mockk<ProductClassRepository>()
    private val attrDefRepo = mockk<AttributeDefRepository>()
    private val productRepo = mockk<ProductRepository>()
    private val productPriceRepo = mockk<ProductPriceRepository>()
    private val priceHistoryRepo = mockk<ProductPriceHistoryRepository>()
    private val ingestRepo = mockk<CatalogIngestRepository>()
    private val llmService = mockk<LlmService>()
    private val unitService = mockk<UnitNormalizationService>()
    private val docParser = mockk<DocumentParsingService>()

    private val supplier = Supplier(id = 1L, name = "Grainger")
    private val productClass = ProductClass(id = 10L, name = "Gas Detector")
    private val ingest = CatalogIngest(id = 7L, supplier = supplier, kind = "admin_upload")

    private val savedIngests = Collections.synchronizedList(mutableListOf<CatalogIngest>())
    private val savedProducts = Collections.synchronizedList(mutableListOf<Product>())
    private val existingProducts = mutableListOf<Product>()
    private val ids = AtomicLong(100)

    /** Four 40-char pages; with chunkChars=50 each page becomes its own chunk. */
    private val fourPages = (1..4).joinToString("\u000C") { "PAGE-$it ".padEnd(40, '.') }

    private fun service(maxChunks: Int = 150, parallelism: Int = 2, chunkChars: Int = 50) = CatalogIngestService(
        supplierRepo, productClassRepo, attrDefRepo, productRepo,
        productPriceRepo, priceHistoryRepo, ingestRepo, llmService, unitService, docParser,
        chunkChars = chunkChars, maxChunks = maxChunks, parallelism = parallelism
    )

    private fun productFor(chunk: String): List<ParsedProduct> {
        val page = Regex("PAGE-(\\d+)").find(chunk)!!.groupValues[1]
        return listOf(ParsedProduct("Gas Detector", "Detector $page", "GD-$page", null, "JOD", mapOf("range_ppm" to page.toDouble())))
    }

    /** One product per "ROW-n" line in the chunk. */
    private fun rowsIn(chunk: String): List<ParsedProduct> =
        Regex("ROW-(\\d+)").findAll(chunk).map { m ->
            val n = m.groupValues[1]
            ParsedProduct("Gas Detector", "Detector $n", "GD-$n", null, "JOD", emptyMap())
        }.toList()

    private fun ok(products: List<ParsedProduct>) = CatalogBatchResult(products, truncated = false)

    @BeforeEach
    fun setUp() {
        every { ingestRepo.save(any()) } answers { firstArg<CatalogIngest>().also { savedIngests.add(it) } }
        every { supplierRepo.save(any()) } answers { firstArg() }
        every { productClassRepo.findAll() } returns listOf(productClass)
        every { productClassRepo.findByNameIgnoreCase("Gas Detector") } returns productClass
        every { attrDefRepo.findByProductClassId(10L) } returns emptyList()
        every { unitService.normalizeAttributes(any(), any()) } answers { firstArg() }
        every { productRepo.findBySupplierIdAndMpnIgnoreCase(1L, any()) } returns null
        every { productRepo.findBySupplierIdAndNameIgnoreCase(1L, any()) } returns null
        every { productRepo.save(any()) } answers {
            val p = firstArg<Product>()
            (if (p.id == 0L) p.copy(id = ids.incrementAndGet()) else p).also { savedProducts.add(it) }
        }
        every { productRepo.findBySupplierId(1L) } answers { existingProducts.toList() }
    }

    @Test
    fun `every chunk of the document is sent to the llm and all products are saved`() {
        every { llmService.parseCatalogChunk(any(), any()) } answers { ok(productFor(firstArg())) }

        service().runIngest(ingest, fourPages, "upload")

        verify(exactly = 4) { llmService.parseCatalogChunk(any(), any()) }
        assertThat(savedProducts.map { it.mpn }).containsExactlyInAnyOrder("GD-1", "GD-2", "GD-3", "GD-4")
        val done = savedIngests.last()
        assertThat(done.status).isEqualTo("DONE")
        assertThat(done.itemsFound).isEqualTo(4)
        assertThat(done.stepLog).contains("4 chunk(s)").contains("Chunk 4/4")
    }

    @Test
    fun `products are saved in document order even when llm calls run in parallel`() {
        every { llmService.parseCatalogChunk(any(), any()) } answers {
            val chunk = firstArg<String>()
            if (chunk.contains("PAGE-1")) Thread.sleep(150) // first chunk finishes last
            ok(productFor(chunk))
        }

        service(parallelism = 4).runIngest(ingest, fourPages, "upload")

        assertThat(savedProducts.map { it.mpn }).containsExactly("GD-1", "GD-2", "GD-3", "GD-4")
    }

    @Test
    fun `progress is recorded while the ingest is still running`() {
        every { llmService.parseCatalogChunk(any(), any()) } answers { ok(productFor(firstArg())) }

        service(parallelism = 1).runIngest(ingest, fourPages, "upload")

        val running = savedIngests.filter { it.status != "DONE" && it.itemsFound != null }
        assertThat(running.map { it.itemsFound }).contains(1, 2, 3)
    }

    @Test
    fun `chunk cap stops early, says so, and does not mark unseen products stale`() {
        every { llmService.parseCatalogChunk(any(), any()) } answers { ok(productFor(firstArg())) }
        existingProducts.add(Product(id = 1L, supplier = supplier, productClass = productClass, name = "Old", source = "upload"))

        service(maxChunks = 2).runIngest(ingest, fourPages, "upload")

        verify(exactly = 2) { llmService.parseCatalogChunk(any(), any()) }
        assertThat(savedIngests.last().stepLog).contains("capped")
        assertThat(savedProducts).noneMatch { it.name == "Old" && it.isStale }
    }

    @Test
    fun `full clean run still marks unseen upload products stale`() {
        every { llmService.parseCatalogChunk(any(), any()) } answers { ok(productFor(firstArg())) }
        existingProducts.add(Product(id = 1L, supplier = supplier, productClass = productClass, name = "Old", source = "upload"))

        service().runIngest(ingest, fourPages, "upload")

        assertThat(savedProducts).anyMatch { it.name == "Old" && it.isStale }
    }

    @Test
    fun `a failing chunk is logged and skipped without marking products stale`() {
        every { llmService.parseCatalogChunk(any(), any()) } answers {
            val chunk = firstArg<String>()
            if (chunk.contains("PAGE-2")) throw LlmException("LLM API error 529: Overloaded")
            ok(productFor(chunk))
        }
        existingProducts.add(Product(id = 1L, supplier = supplier, productClass = productClass, name = "Old", source = "upload"))

        service().runIngest(ingest, fourPages, "upload")

        val done = savedIngests.last()
        assertThat(done.status).isEqualTo("DONE")
        assertThat(done.itemsFound).isEqualTo(3)
        assertThat(done.stepLog).contains("Chunk 2/4 failed: LLM API error 529: Overloaded")
        assertThat(savedProducts).noneMatch { it.name == "Old" && it.isStale }
    }

    @Test
    fun `a credit or auth error stops the ingest immediately`() {
        every { llmService.parseCatalogChunk(any(), any()) } throws
            LlmException("LLM API error 400: Your credit balance is too low to access the Anthropic API.")

        assertThatThrownBy { service(parallelism = 1).runIngest(ingest, fourPages, "upload") }
            .isInstanceOf(LlmException::class.java)
            .hasMessageContaining("credit balance is too low")

        verify(exactly = 1) { llmService.parseCatalogChunk(any(), any()) }
    }

    @Test
    fun `three consecutive chunk failures stop the ingest`() {
        every { llmService.parseCatalogChunk(any(), any()) } throws LlmException("LLM API error 529: Overloaded")

        assertThatThrownBy { service(parallelism = 1).runIngest(ingest, fourPages, "upload") }
            .isInstanceOf(LlmException::class.java)
            .hasMessageContaining("3 consecutive chunks failed")

        verify(exactly = 3) { llmService.parseCatalogChunk(any(), any()) }
    }

    /** Eight 40-char table rows on one page, fitting in a single 500-char chunk. */
    private val denseTable = (1..8).joinToString("\n") { "ROW-$it ".padEnd(40, '.') }

    @Test
    fun `a chunk whose answer hit the output limit is split and every row is re-extracted`() {
        // The model can only fit 4 rows per answer: bigger chunks come back cut off after 3.
        every { llmService.parseCatalogChunk(any(), any()) } answers {
            val rows = rowsIn(firstArg())
            if (rows.size > 4) CatalogBatchResult(rows.take(3), truncated = true) else ok(rows)
        }

        service(chunkChars = 500).runIngest(ingest, denseTable, "upload")

        assertThat(savedProducts.map { it.mpn }).containsExactly("GD-1", "GD-2", "GD-3", "GD-4", "GD-5", "GD-6", "GD-7", "GD-8")
        verify(exactly = 3) { llmService.parseCatalogChunk(any(), any()) }
        val done = savedIngests.last()
        assertThat(done.stepLog).contains("Chunk 1/1: 8 product(s) (answer hit the output limit; re-extracted in 2 parts)")
        assertThat(done.stepLog).doesNotContain("WARNING")
        assertThat(done.status).isEqualTo("DONE")
    }

    @Test
    fun `a chunk that still hits the limit after splitting is flagged and does not stale products`() {
        every { llmService.parseCatalogChunk(any(), any()) } answers {
            CatalogBatchResult(rowsIn(firstArg()).take(1), truncated = true)
        }
        existingProducts.add(Product(id = 1L, supplier = supplier, productClass = productClass, name = "Old", source = "upload"))

        service(chunkChars = 500).runIngest(ingest, denseTable, "upload")

        val done = savedIngests.last()
        assertThat(done.stepLog).contains("WARNING: some rows may be missing")
        assertThat(savedProducts).noneMatch { it.name == "Old" && it.isStale }
    }
}
