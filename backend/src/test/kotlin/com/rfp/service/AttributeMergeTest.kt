package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rfp.domain.*
import com.rfp.dto.*
import com.rfp.repository.*
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong

/** Merging spec keys that mean the same thing ("phase" / "phases") into one canonical key per class. */
class AttributeMergeTest {

    private val productClassRepo = mockk<ProductClassRepository>()
    private val attrDefRepo = mockk<AttributeDefRepository>()
    private val productRepo = mockk<ProductRepository>()
    private val aliasRepo = mockk<AttributeAliasRepository>()
    private val llmService = mockk<LlmService>()
    private val mapper = ObjectMapper().apply { findAndRegisterModules() }

    private val supplier = Supplier(id = 1L, name = "Grainger")
    private val motors = ProductClass(id = 10L, name = "AC Motor")
    private val defs = mutableListOf<AttributeDef>()
    private val products = mutableListOf<Product>()
    private val aliases = mutableListOf<AttributeAlias>()
    private val ids = AtomicLong(500)

    private fun service() = AttributeSchemaService(productClassRepo, attrDefRepo, productRepo, llmService, aliasRepo)
    private fun product(attrs: String) =
        Product(id = ids.incrementAndGet(), supplier = supplier, productClass = motors, name = "Motor", attributes = attrs, source = "upload")
    private fun def(name: String, datatype: String = "text") =
        AttributeDef(id = ids.incrementAndGet(), productClass = motors, name = name, label = name, datatype = datatype, matchOp = "eq")
    private fun attrsOf(p: Product): Map<String, Any?> = mapper.readValue(p.attributes)

    @BeforeEach
    fun setUp() {
        every { productClassRepo.findById(10L) } returns Optional.of(motors)
        every { productRepo.findByProductClassId(10L) } answers { products.toList() }
        every { productRepo.saveAll(any<Iterable<Product>>()) } answers {
            val saved = firstArg<Iterable<Product>>().toList()
            saved.forEach { s -> products.replaceAll { if (it.id == s.id) s else it } }
            saved
        }
        every { attrDefRepo.findByProductClassId(10L) } answers { defs.toList() }
        every { attrDefRepo.save(any()) } answers {
            val d = firstArg<AttributeDef>()
            val saved = if (d.id == 0L) d.copy(id = ids.incrementAndGet()) else d
            defs.removeIf { it.id == saved.id }; defs.add(saved); saved
        }
        every { attrDefRepo.deleteAll(any<Iterable<AttributeDef>>()) } answers {
            val gone = firstArg<Iterable<AttributeDef>>().map { it.id }.toSet(); defs.removeIf { it.id in gone }
        }
        every { aliasRepo.findByClassId(10L) } answers { aliases.toList() }
        every { aliasRepo.save(any()) } answers { firstArg<AttributeAlias>().also { aliases.add(it) } }
    }

    @Test
    fun `alias values move to the canonical key, values are translated and the alias def is removed`() {
        repeat(3) { products += product("""{"phase":"Single","power_hp":1}""") }
        repeat(2) { products += product("""{"phases":3,"power_hp":2}""") }
        defs += def("phase"); defs += def("phases", "numeric"); defs += def("power_hp", "numeric")
        every { llmService.findDuplicateAttributes("AC Motor", any()) } returns
            listOf(DuplicateGroup("phase", listOf("phases"), mapOf("Single" to "1", "Three" to "3")))

        val merged = service().mergeDuplicates(10L)

        assertThat(merged).isEqualTo(1)
        assertThat(products.map { attrsOf(it)["phase"].toString() }).containsExactly("1", "1", "1", "3", "3")
        assertThat(products).noneMatch { attrsOf(it).containsKey("phases") }
        assertThat(defs.map { it.name }).containsExactlyInAnyOrder("phase", "power_hp")
        assertThat(defs.single { it.name == "phase" }.valueAliases).contains("\"Single\":\"1\"")
        assertThat(aliases.map { it.alias to it.canonicalName }).containsExactly("phases" to "phase")
    }

    @Test
    fun `keys that disagree on the same products are different specs and are not merged`() {
        repeat(5) { products += product("""{"shaft_dia_in":0.625,"body_dia_in":5.5}""") }
        defs += def("shaft_dia_in", "numeric"); defs += def("body_dia_in", "numeric")
        every { llmService.findDuplicateAttributes(any(), any()) } returns listOf(DuplicateGroup("shaft_dia_in", listOf("body_dia_in")))

        val merged = service().mergeDuplicates(10L)

        assertThat(merged).isEqualTo(0)
        assertThat(products).allMatch { attrsOf(it).containsKey("body_dia_in") }
        assertThat(defs).hasSize(2)
        assertThat(aliases).isEmpty()
    }

    @Test
    fun `when both keys are on a product with the same value the canonical one is kept`() {
        products += product("""{"frame":"143TC","frame_designation":"143TC"}""")
        products += product("""{"frame_designation":"56C"}""")
        defs += def("frame"); defs += def("frame_designation")
        every { llmService.findDuplicateAttributes(any(), any()) } returns listOf(DuplicateGroup("frame", listOf("frame_designation")))

        service().mergeDuplicates(10L)

        assertThat(products.map { attrsOf(it) }).containsExactly(mapOf("frame" to "143TC"), mapOf("frame" to "56C"))
    }

    @Test
    fun `canonicalize translates alias keys and values for new products and tender lines`() {
        aliases += AttributeAlias(classId = 10L, alias = "phases", canonicalName = "phase")
        defs += def("phase").copy(valueAliases = """{"single":"1","three":"3"}""")

        val out = service().canonicalize(10L, mapOf("phases" to "Three", "power_hp" to 1.0))

        assertThat(out).isEqualTo(mapOf("phase" to "3", "power_hp" to 1.0))
    }

    @Test
    fun `merge is skipped without an llm call when a class has fewer than two spec keys`() {
        products += product("""{"power_hp":1}""")
        defs += def("power_hp", "numeric")

        assertThat(service().mergeDuplicates(10L)).isEqualTo(0)
        verify(exactly = 0) { llmService.findDuplicateAttributes(any(), any()) }
    }

    @Test
    fun `groups naming unknown keys are ignored`() {
        products += product("""{"phase":"1","phases":1}""")
        defs += def("phase"); defs += def("phases")
        every { llmService.findDuplicateAttributes(any(), any()) } returns listOf(DuplicateGroup("phase", listOf("invented")))

        assertThat(service().mergeDuplicates(10L)).isEqualTo(0)
        assertThat(defs).hasSize(2)
    }

    @Test
    fun `value spellings are rewritten to the canonical spelling and recorded for canonicalize`() {
        repeat(4) { products += product("""{"motor_type":"Permanent Split Capacitor"}""") }
        products += product("""{"motor_type":"PSC"}""")
        products += product("""{"motor_type":"3-Phase"}""")
        products += product("""{"motor_type":"Three-Phase"}""")
        defs += def("motor_type")
        every { llmService.findValueSynonyms("AC Motor", any()) } returns
            mapOf("motor_type" to mapOf("PSC" to "Permanent Split Capacitor", "3-Phase" to "Three-Phase"))

        assertThat(service().mergeValueSpellings(10L)).isEqualTo(2)

        assertThat(products.map { attrsOf(it)["motor_type"] }.distinct())
            .containsExactlyInAnyOrder("Permanent Split Capacitor", "Three-Phase")
        assertThat(service().canonicalize(10L, mapOf("motor_type" to "psc")))
            .isEqualTo(mapOf("motor_type" to "Permanent Split Capacitor"))
    }

    @Test
    fun `value spellings sends counts, follows chains and rejects different numbers`() {
        products += product("""{"enclosure":"TEFC","voltage":"115"}""")
        products += product("""{"enclosure":"Totally Enclosed Fan Cooled","voltage":"230"}""")
        products += product("""{"enclosure":"Tot. Encl. Fan Cooled","voltage":"115"}""")
        defs += def("enclosure"); defs += def("voltage"); defs += def("power_hp", "numeric")
        val sent = slot<List<ValueUsage>>()
        every { llmService.findValueSynonyms(any(), capture(sent)) } returns mapOf(
            "enclosure" to mapOf("Tot. Encl. Fan Cooled" to "TEFC", "TEFC" to "Totally Enclosed Fan Cooled"),
            "voltage" to mapOf("115" to "230")
        )

        assertThat(service().mergeValueSpellings(10L)).isEqualTo(2)

        assertThat(sent.captured.map { it.name }).containsExactlyInAnyOrder("enclosure", "voltage")
        assertThat(sent.captured.single { it.name == "voltage" }.values).isEqualTo(mapOf("115" to 2, "230" to 1))
        assertThat(products.map { attrsOf(it)["enclosure"] }.distinct()).containsExactly("Totally Enclosed Fan Cooled")
        assertThat(products.map { attrsOf(it)["voltage"] }).containsExactly("115", "230", "115")
    }

    @Test
    fun `value spellings are not checked for specs with a single value or no text specs`() {
        repeat(3) { products += product("""{"enclosure":"TEFC","power_hp":1}""") }
        defs += def("enclosure"); defs += def("power_hp", "numeric")

        assertThat(service().mergeValueSpellings(10L)).isEqualTo(0)
        verify(exactly = 0) { llmService.findValueSynonyms(any(), any()) }
    }

    @Test
    fun `merging repeats until a pass finds nothing new`() {
        products += product("""{"phase":"1","motor_type":"PSC","power_hp":1}""")
        products += product("""{"phases":3,"motor_subtype":"Split-Phase","power_hp":2}""")
        defs += def("phase"); defs += def("phases"); defs += def("motor_type"); defs += def("motor_subtype"); defs += def("power_hp", "numeric")
        every { llmService.findDuplicateAttributes(any(), any()) } returnsMany listOf(
            listOf(DuplicateGroup("phase", listOf("phases"))),
            listOf(DuplicateGroup("motor_type", listOf("motor_subtype"))),
            emptyList()
        )

        assertThat(service().mergeUntilStable(10L)).isEqualTo(2)
        verify(exactly = 3) { llmService.findDuplicateAttributes(any(), any()) }
        assertThat(defs.map { it.name }).containsExactlyInAnyOrder("phase", "motor_type", "power_hp")
    }
}
