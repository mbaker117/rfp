package com.rfp.service

import com.rfp.domain.*
import com.rfp.dto.*
import com.rfp.repository.*
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong

class AttributeSchemaServiceTest {

    private val productClassRepo = mockk<ProductClassRepository>()
    private val attrDefRepo = mockk<AttributeDefRepository>()
    private val productRepo = mockk<ProductRepository>()
    private val llmService = mockk<LlmService>()

    private val supplier = Supplier(id = 1L, name = "Grainger")
    private val motors = ProductClass(id = 10L, name = "AC Motor")

    private val defs = mutableListOf<AttributeDef>()
    private val products = mutableListOf<Product>()
    private val ids = AtomicLong(500)

    private fun service() = AttributeSchemaService(productClassRepo, attrDefRepo, productRepo, llmService)

    private fun product(attrs: String) =
        Product(id = ids.incrementAndGet(), supplier = supplier, productClass = motors, name = "Motor", attributes = attrs, source = "upload")

    private fun def(name: String, datatype: String, matchOp: String, unit: String? = null) =
        AttributeDef(id = ids.incrementAndGet(), productClass = motors, name = name, label = name, datatype = datatype,
            matchOp = matchOp, canonicalUnit = unit)

    @BeforeEach
    fun setUp() {
        every { productClassRepo.findById(10L) } returns Optional.of(motors)
        every { productClassRepo.findAll() } returns listOf(motors)
        every { productRepo.findByProductClassId(10L) } answers { products.toList() }
        every { attrDefRepo.findByProductClassId(10L) } answers { defs.toList() }
        every { attrDefRepo.save(any()) } answers {
            val d = firstArg<AttributeDef>()
            val saved = if (d.id == 0L) d.copy(id = ids.incrementAndGet()) else d
            defs.removeIf { it.id == saved.id }
            defs.add(saved)
            saved
        }
        every { attrDefRepo.deleteAll(any<Iterable<AttributeDef>>()) } answers {
            val gone = firstArg<Iterable<AttributeDef>>().map { it.id }.toSet()
            defs.removeIf { it.id in gone }
        }
        every { llmService.defineAttributes(any(), any()) } answers {
            secondArg<List<AttributeSample>>().map { AttributeMeta(it.name, "Label ${it.name}", if (it.name.startsWith("max")) "gte" else "eq") }
        }
    }

    @Test
    fun `adds definitions for specs the products carry, typed from the data with the unit from the key`() {
        repeat(10) { products += product("""{"insulation_class":"F","max_ambient_temp_c":40,"power_hp":0.5}""") }
        defs += def("power_hp", "numeric", "gte", "hp")

        val result = service().syncClass(10L)

        assertThat(result.added).isEqualTo(2)
        val insulation = defs.single { it.name == "insulation_class" }
        assertThat(insulation.datatype).isEqualTo("text")
        assertThat(insulation.matchOp).isEqualTo("gte")   // a lettered scale with an order
        assertThat(insulation.canonicalUnit).isNull()
        assertThat(insulation.label).isEqualTo("Label insulation_class")
        val ambient = defs.single { it.name == "max_ambient_temp_c" }
        assertThat(ambient.datatype).isEqualTo("numeric")
        assertThat(ambient.matchOp).isEqualTo("gte")
        assertThat(ambient.canonicalUnit).isEqualTo("°C")
        verify(exactly = 1) { llmService.defineAttributes("AC Motor", match { s -> s.map { it.name }.toSet() == setOf("insulation_class", "max_ambient_temp_c") }) }
    }

    @Test
    fun `identifier keys never become definitions and existing ones are removed`() {
        repeat(5) { products += product("""{"item_no":"1K065","capacitor_item_no":"2MDV6","mpn":"X1","manualLink":"https://x","power_hp":1}""") }
        defs += def("power_hp", "numeric", "gte", "hp")
        defs += def("item_no", "text", "eq")

        val result = service().syncClass(10L)

        assertThat(defs.map { it.name }).containsExactly("power_hp")
        assertThat(result.removed).isEqualTo(1)
        assertThat(result.added).isEqualTo(0)
        verify(exactly = 0) { llmService.defineAttributes(any(), any()) }
    }

    @Test
    fun `a numeric definition whose values are not numbers becomes text`() {
        products += product("""{"frame":"56H"}"""); products += product("""{"frame":"143T"}"""); products += product("""{"frame":56}""")
        defs += def("frame", "numeric", "eq")

        val result = service().syncClass(10L)

        assertThat(defs.single().datatype).isEqualTo("text")
        assertThat(result.fixed).isEqualTo(1)
    }

    @Test
    fun `a wrong unit is corrected from the key suffix and the match rule is kept`() {
        repeat(3) { products += product("""{"overall_length_in":12.375,"voltage_v":230}""") }
        defs += def("overall_length_in", "numeric", "lte", "m")
        defs += def("voltage_v", "numeric", "eq", "V")

        service().syncClass(10L)

        val length = defs.single { it.name == "overall_length_in" }
        assertThat(length.canonicalUnit).isEqualTo("in")
        assertThat(length.matchOp).isEqualTo("lte")
        assertThat(defs.single { it.name == "voltage_v" }.canonicalUnit).isEqualTo("V")
    }

    @Test
    fun `rare keys below the share threshold are ignored`() {
        repeat(40) { products += product("""{"power_hp":1}""") }
        products += product("""{"power_hp":1,"conduit_box":true}""")
        defs += def("power_hp", "numeric", "gte", "hp")

        val result = service().syncClass(10L)

        assertThat(result.added).isEqualTo(0)
        assertThat(defs.map { it.name }).doesNotContain("conduit_box")
    }

    @Test
    fun `an llm failure still adds the definitions with safe defaults`() {
        repeat(3) { products += product("""{"insulation_class":"F"}""") }
        every { llmService.defineAttributes(any(), any()) } throws LlmException("LLM API error 529: Overloaded")

        service().syncClass(10L)

        val d = defs.single()
        assertThat(d.name).isEqualTo("insulation_class")
        assertThat(d.matchOp).isEqualTo("gte")
        assertThat(d.label).isEqualTo("Insulation Class")
    }

    @Test
    fun `boolean specs are typed bool`() {
        repeat(3) { products += product("""{"waterproof":true}""") }

        service().syncClass(10L)

        assertThat(defs.single().datatype).isEqualTo("bool")
    }

    @Test
    fun `fraction strings count as numbers when typing a spec`() {
        repeat(8) { products += product("""{"overall_length_in":12.375}""") }
        repeat(2) { products += product("""{"overall_length_in":"13 3/8"}""") }
        defs += def("overall_length_in", "text", "lte")

        service().syncClass(10L)

        val d = defs.single()
        assertThat(d.datatype).isEqualTo("numeric")
        assertThat(d.canonicalUnit).isEqualTo("in")
        assertThat(d.matchOp).isEqualTo("lte")
    }

    @Test
    fun `text specs only match exactly, even if the rule said gte`() {
        repeat(3) { products += product("""{"enclosure":"TEFC","motor_eff_group":"IE3"}""") }
        defs += def("enclosure", "text", "gte")
        every { llmService.defineAttributes(any(), any()) } answers {
            secondArg<List<AttributeSample>>().map { AttributeMeta(it.name, it.name, "gte") }
        }

        val result = service().syncClass(10L)

        assertThat(defs.single { it.name == "enclosure" }.matchOp).isEqualTo("eq")
        assertThat(defs.single { it.name == "motor_eff_group" }.matchOp).isEqualTo("eq")
        assertThat(result.fixed).isEqualTo(1)
    }

    @Test
    fun `a lettered scale keeps gte, so a higher class still answers a lower request`() {
        repeat(3) { products += product("""{"insulation_class":"F","motor_efficiency_group":"IE3"}""") }
        every { llmService.defineAttributes(any(), any()) } answers {
            // Even when the model asks for exact matching, these scales have an order.
            secondArg<List<AttributeSample>>().map { AttributeMeta(it.name, it.name, "eq") }
        }

        service().syncClass(10L)

        assertThat(defs.single { it.name == "insulation_class" }.matchOp).isEqualTo("gte")
        assertThat(defs.single { it.name == "motor_efficiency_group" }.matchOp).isEqualTo("gte")
    }

    @Test
    fun `a numeric definition without product data still gets its unit from the key`() {
        defs += def("accuracy_pct", "numeric", "lte")

        val result = service().syncClass(10L)

        assertThat(defs.single().canonicalUnit).isEqualTo("%")
        assertThat(result.fixed).isEqualTo(1)
    }
}
