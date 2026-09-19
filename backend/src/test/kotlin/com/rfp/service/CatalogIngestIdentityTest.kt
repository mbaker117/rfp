package com.rfp.service

import com.rfp.domain.*
import com.rfp.dto.*
import com.rfp.repository.*
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/** How extracted catalog products are matched to existing rows (item number > part number > name). */
class CatalogIngestIdentityTest {

    private val supplierRepo = mockk<SupplierRepository>()
    private val productClassRepo = mockk<ProductClassRepository>()
    private val attrDefRepo = mockk<AttributeDefRepository>()
    private val productRepo = mockk<ProductRepository>()
    private val ingestRepo = mockk<CatalogIngestRepository>()
    private val llmService = mockk<LlmService>()
    private val unitService = mockk<UnitNormalizationService>()

    private val supplier = Supplier(id = 1L, name = "Grainger")
    private val motors = ProductClass(id = 10L, name = "AC Motor")
    private val ingest = CatalogIngest(id = 7L, supplier = supplier, kind = "admin_upload")

    /** In-memory product table. */
    private val rows = mutableListOf<Product>()
    private val ids = AtomicLong(100)

    private fun service() = CatalogIngestService(
        supplierRepo, productClassRepo, attrDefRepo, productRepo,
        mockk(), mockk(), ingestRepo, llmService, unitService, mockk()
    )

    private fun itemNoOf(p: Product) = Regex("\"item_no\":\"([^\"]+)\"").find(p.attributes ?: "")?.groupValues?.get(1)

    private fun motor(name: String, mpn: String?, itemNo: String?, hp: Double) =
        ParsedProduct("AC Motor", name, mpn, null, "JOD",
            buildMap { itemNo?.let { put("item_no", it) }; put("power_hp", hp) })

    private fun ingestProducts(vararg products: ParsedProduct) {
        every { llmService.parseCatalogChunk(any(), any(), any()) } returns CatalogBatchResult(products.toList(), truncated = false)
        service().runIngest(ingest, "catalog page text", "upload")
    }

    @BeforeEach
    fun setUp() {
        every { ingestRepo.save(any()) } answers { firstArg() }
        every { supplierRepo.save(any()) } answers { firstArg() }
        every { productClassRepo.findAll() } returns listOf(motors)
        every { productClassRepo.findByNameIgnoreCase("AC Motor") } returns motors
        every { attrDefRepo.findByProductClassId(10L) } returns emptyList()
        every { unitService.normalizeAttributes(any(), any()) } answers { firstArg() }
        every { productRepo.findAllBySupplierIdAndItemNo(1L, any()) } answers {
            rows.filter { itemNoOf(it).equals(secondArg<String>(), ignoreCase = true) }
        }
        every { productRepo.findAllBySupplierIdAndMpnIgnoreCase(1L, any()) } answers {
            rows.filter { it.mpn.equals(secondArg<String>(), ignoreCase = true) }
        }
        every { productRepo.findAllBySupplierIdAndNameIgnoreCase(1L, any()) } answers {
            rows.filter { it.name.equals(secondArg<String>(), ignoreCase = true) }
        }
        every { productRepo.save(any()) } answers {
            val p = firstArg<Product>()
            val saved = if (p.id == 0L) p.copy(id = ids.incrementAndGet()) else p
            rows.removeIf { it.id == saved.id }
            rows.add(saved)
            saved
        }
        every { productRepo.findBySupplierId(1L) } answers { rows.toList() }
    }

    @Test
    fun `products sharing a generic name but with different item numbers stay separate`() {
        val name = "Capacitor-Start AC Motor 1/2 HP 1725 RPM 56C Frame"
        ingestProducts(motor(name, "1K079", "1K079", 0.5), motor(name, "21YZ39", "21YZ39", 0.5), motor(name, "6K342", "6K342", 0.5))

        assertThat(rows.map { itemNoOf(it) }).containsExactlyInAnyOrder("1K079", "21YZ39", "6K342")
        assertThat(rows.map { it.mpn }).containsExactlyInAnyOrder("1K079", "21YZ39", "6K342")
    }

    @Test
    fun `a product is updated in place when its item number already exists, including mpn and name`() {
        rows.add(Product(id = 1L, supplier = supplier, productClass = motors, name = "Old name", mpn = "WRONG",
            attributes = """{"item_no":"44D126","power_hp":0.25}""", source = "upload"))

        ingestProducts(motor("Leeson AC Motor 3/4 HP", "5KC46PN0015X", "44D126", 0.75))

        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row.id).isEqualTo(1L)
        assertThat(row.mpn).isEqualTo("5KC46PN0015X")
        assertThat(row.name).isEqualTo("Leeson AC Motor 3/4 HP")
        assertThat(row.attributes).contains("\"power_hp\":0.75")
    }

    @Test
    fun `a part number match with a different item number is a different product`() {
        rows.add(Product(id = 1L, supplier = supplier, productClass = motors, name = "Motor A", mpn = "VL3503",
            attributes = """{"item_no":"38G465"}""", source = "upload"))

        ingestProducts(motor("Motor B", "VL3503", "38G999", 0.5))

        assertThat(rows.map { itemNoOf(it) }).containsExactlyInAnyOrder("38G465", "38G999")
    }

    @Test
    fun `a part number match without an item number is adopted`() {
        rows.add(Product(id = 1L, supplier = supplier, productClass = motors, name = "Motor", mpn = "6K483",
            attributes = """{"power_hp":0.5}""", source = "upload"))

        ingestProducts(motor("Dayton Motor", "6K483", "6K483", 0.75))

        assertThat(rows).hasSize(1)
        assertThat(itemNoOf(rows.single())).isEqualTo("6K483")
    }

    @Test
    fun `a new part number never falls back to a name match`() {
        rows.add(Product(id = 1L, supplier = supplier, productClass = motors, name = "Generic Motor", mpn = "AAA1",
            attributes = """{"power_hp":0.5}""", source = "upload"))

        ingestProducts(motor("Generic Motor", "BBB2", null, 1.0))

        assertThat(rows.map { it.mpn }).containsExactlyInAnyOrder("AAA1", "BBB2")
    }

    @Test
    fun `products without item or part number are matched by name`() {
        rows.add(Product(id = 1L, supplier = supplier, productClass = motors, name = "Shaft Grounding Ring",
            attributes = """{"size_in":1}""", source = "upload"))

        ingestProducts(motor("Shaft Grounding Ring", null, null, 0.0))

        assertThat(rows).hasSize(1)
        assertThat(rows.single().id).isEqualTo(1L)
    }

    @Test
    fun `a product repeated within the same run is saved once`() {
        ingestProducts(motor("Dayton Motor", "6K483", "6K483", 0.5), motor("Dayton Motor", "6K483", "6K483", 0.5))

        assertThat(rows).hasSize(1)
    }
}
