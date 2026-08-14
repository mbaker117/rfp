# Proposal Intelligence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add LLM-estimated acceptance probability, three proposal variants per tender (Perfect/BestAcceptance/Cheapest), and an interactive product-override UI so customers can maximise their proposal's acceptance rate.

**Architecture:** A new `ProposalService` generates three `Proposal` records (one per variant) asynchronously after matching, each with a `ProposalLine` per tender line selecting the best product under that variant's rule. Acceptance probability per line is estimated via a new `LlmService.estimateAcceptance()` call and rolled up as a mean. The frontend polls proposals until `READY`, renders a 3-card `ProposalPanel`, and lets users swap products in a `ProposalDetail` view that re-estimates only the changed line.

**Tech Stack:** Spring Boot 3.2.5 + Kotlin 1.9.25 (Java 21), Spring Data JPA, Flyway, MockK tests. Next.js 16 + TypeScript + Tailwind CSS v4 frontend. Maven at `C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd`. Java at `C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot`.

## Global Constraints

- Flyway owns all schema changes — add only as `V{N}__description.sql`; current latest is V6
- `@Async` self-call trap: if a method in a bean calls its own `@Async` method, use `@Lazy` self-injection (pattern already in `ScrapeService`)
- All LLM responses parsed with `parseJson()` and cached via `call()` (sha256 key) — use the caching `call()`, not `llmClient.call()`, for `estimateAcceptance`
- Do not backfill existing DB rows — enrichment and proposals apply to new operations only
- Backend tests use MockK (`io.mockk`) + JUnit 5; controller tests use `@WebMvcTest` + `MockkBean`
- Run backend tests: `cd backend && C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd test -Dtest=<ClassName>`
- Run frontend tests: `cd frontend && npm test -- <path>`

---

## File Map

**New backend files:**
- `backend/src/main/resources/db/migration/V7__proposals.sql`
- `backend/src/main/kotlin/com/rfp/domain/Proposal.kt`
- `backend/src/main/kotlin/com/rfp/domain/ProposalLine.kt`
- `backend/src/main/kotlin/com/rfp/repository/ProposalRepository.kt`
- `backend/src/main/kotlin/com/rfp/repository/ProposalLineRepository.kt`
- `backend/src/main/kotlin/com/rfp/service/ProposalService.kt`
- `backend/src/main/kotlin/com/rfp/controller/ProposalController.kt`
- `backend/src/test/kotlin/com/rfp/service/ProposalServiceTest.kt`
- `backend/src/test/kotlin/com/rfp/controller/ProposalControllerTest.kt`

**Modified backend files:**
- `backend/src/main/kotlin/com/rfp/service/LlmService.kt` — add `AcceptanceEstimate` + `estimateAcceptance()`
- `backend/src/main/kotlin/com/rfp/service/MatchingEngineService.kt` — inject `ProductPriceRepository`, enrich alternatives JSON
- `backend/src/main/kotlin/com/rfp/service/ReportService.kt` — add `exportXlsx(tenderId, proposalId?)` + `exportPdf(tenderId, proposalId?)` overloads
- `backend/src/main/kotlin/com/rfp/controller/RfpController.kt` — add `proposalId` param to export endpoint
- `backend/src/test/kotlin/com/rfp/service/LlmServiceTest.kt` — add `estimateAcceptance` test
- `backend/src/test/kotlin/com/rfp/service/MatchingEngineServiceTest.kt` — add `productPriceRepo` mock, update alternatives test

**New frontend files:**
- `frontend/src/components/ProposalPanel.tsx`
- `frontend/src/components/ProposalDetail.tsx`

**Modified frontend files:**
- `frontend/src/lib/types.ts` — add `SelectedProduct`, `ProposalAlternative`, `ProposalLine`, `Proposal`
- `frontend/src/lib/api.ts` — add proposal API calls
- `frontend/src/app/rfp/[id]/page.tsx` — Generate button, ProposalPanel, proposal-aware export

---

## Task 1: DB Migration + Proposal Domain + Repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__proposals.sql`
- Create: `backend/src/main/kotlin/com/rfp/domain/Proposal.kt`
- Create: `backend/src/main/kotlin/com/rfp/domain/ProposalLine.kt`
- Create: `backend/src/main/kotlin/com/rfp/repository/ProposalRepository.kt`
- Create: `backend/src/main/kotlin/com/rfp/repository/ProposalLineRepository.kt`

**Interfaces:**
- Produces: `Proposal` entity with fields `id, tender, variant, acceptanceRate, matchScore, isComplete, status, createdAt, updatedAt`
- Produces: `ProposalLine` entity with fields `id, proposal, line, selectedProduct, matchScore, acceptanceProbability, llmReasoning, isOverridden, createdAt, updatedAt`
- Produces: `ProposalRepository.findByTenderId(Long)`, `ProposalRepository.deleteByTenderId(Long)`
- Produces: `ProposalLineRepository.findByProposalId(Long)`, `ProposalLineRepository.findByProposalIdAndLineId(Long, Long)`

- [ ] **Step 1: Write migration SQL**

```sql
-- backend/src/main/resources/db/migration/V7__proposals.sql
CREATE TABLE proposal (
    id               BIGSERIAL PRIMARY KEY,
    tender_id        BIGINT NOT NULL REFERENCES tender(id),
    variant          VARCHAR(32) NOT NULL,
    acceptance_rate  NUMERIC(5,2),
    match_score      NUMERIC(5,2),
    is_complete      BOOLEAN NOT NULL DEFAULT FALSE,
    status           VARCHAR(32) NOT NULL DEFAULT 'GENERATING',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE proposal_line (
    id                      BIGSERIAL PRIMARY KEY,
    proposal_id             BIGINT NOT NULL REFERENCES proposal(id) ON DELETE CASCADE,
    line_id                 BIGINT NOT NULL REFERENCES tender_line(id),
    selected_product_id     BIGINT REFERENCES product(id),
    match_score             NUMERIC(5,2),
    acceptance_probability  NUMERIC(5,2),
    llm_reasoning           TEXT,
    is_overridden           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (proposal_id, line_id)
);
```

- [ ] **Step 2: Write Proposal entity**

```kotlin
// backend/src/main/kotlin/com/rfp/domain/Proposal.kt
package com.rfp.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity @Table(name = "proposal")
data class Proposal(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tender_id", nullable = false)
    val tender: Tender,
    val variant: String,
    val acceptanceRate: BigDecimal? = null,
    val matchScore: BigDecimal? = null,
    val isComplete: Boolean = false,
    val status: String = "GENERATING",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
```

- [ ] **Step 3: Write ProposalLine entity**

```kotlin
// backend/src/main/kotlin/com/rfp/domain/ProposalLine.kt
package com.rfp.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity @Table(name = "proposal_line")
data class ProposalLine(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposal_id", nullable = false)
    val proposal: Proposal,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_id", nullable = false)
    val line: TenderLine,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_product_id")
    val selectedProduct: Product? = null,
    val matchScore: BigDecimal? = null,
    val acceptanceProbability: BigDecimal? = null,
    val llmReasoning: String? = null,
    val isOverridden: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
```

- [ ] **Step 4: Write repositories**

```kotlin
// backend/src/main/kotlin/com/rfp/repository/ProposalRepository.kt
package com.rfp.repository

import com.rfp.domain.Proposal
import org.springframework.data.jpa.repository.JpaRepository

interface ProposalRepository : JpaRepository<Proposal, Long> {
    fun findByTenderId(tenderId: Long): List<Proposal>
    fun deleteByTenderId(tenderId: Long)
}
```

```kotlin
// backend/src/main/kotlin/com/rfp/repository/ProposalLineRepository.kt
package com.rfp.repository

import com.rfp.domain.ProposalLine
import org.springframework.data.jpa.repository.JpaRepository

interface ProposalLineRepository : JpaRepository<ProposalLine, Long> {
    fun findByProposalId(proposalId: Long): List<ProposalLine>
    fun findByProposalIdAndLineId(proposalId: Long, lineId: Long): ProposalLine?
}
```

- [ ] **Step 5: Verify migration runs**

Start the backend — Flyway should apply V7 with no errors. Check logs for:
```
Successfully applied 1 migration to schema "public", now at version v7
```

If the Codex agent has already added a V7 in this repo, rename this file to `V8__proposals.sql` and update all references in this plan to V8.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V7__proposals.sql \
        backend/src/main/kotlin/com/rfp/domain/Proposal.kt \
        backend/src/main/kotlin/com/rfp/domain/ProposalLine.kt \
        backend/src/main/kotlin/com/rfp/repository/ProposalRepository.kt \
        backend/src/main/kotlin/com/rfp/repository/ProposalLineRepository.kt
git commit -m "feat: proposal + proposal_line schema and JPA entities"
```

---

## Task 2: LlmService.estimateAcceptance()

**Files:**
- Modify: `backend/src/main/kotlin/com/rfp/service/LlmService.kt`
- Modify: `backend/src/test/kotlin/com/rfp/service/LlmServiceTest.kt`

**Interfaces:**
- Consumes: existing `call(system, user)` (caching), `parseJson()`
- Produces: `data class AcceptanceEstimate(val probability: Int, val reasoning: String)` — defined at top of `LlmService.kt`
- Produces: `LlmService.estimateAcceptance(lineDescription, lineAttrs, productName, productMpn, productAttrs, verdictsStr, price, currency): AcceptanceEstimate`

- [ ] **Step 1: Write the failing test**

Add to `backend/src/test/kotlin/com/rfp/service/LlmServiceTest.kt`:

```kotlin
@Test
fun `estimateAcceptance parses probability and reasoning`() {
    val llmClient = mockk<LlmClient>()
    val service = LlmService(llmClient)
    every { llmClient.call(any(), any()) } returns """{"probability": 82, "reasoning": "Good spec compliance and competitive price."}"""

    val result = service.estimateAcceptance(
        lineDescription = "True RMS multimeter 1000V",
        lineAttrs = mapOf("max_voltage" to 1000.0),
        productName = "Fluke 179",
        productMpn = "FL179",
        productAttrs = mapOf("max_voltage" to 1000.0, "has_trms" to true),
        verdictsStr = "max_voltage: COMPLIANT",
        price = java.math.BigDecimal("320.00"),
        currency = "JOD"
    )

    assertThat(result.probability).isEqualTo(82)
    assertThat(result.reasoning).isEqualTo("Good spec compliance and competitive price.")
}
```

- [ ] **Step 2: Run test to verify it fails**

```
cd backend && C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd test -Dtest=LlmServiceTest#estimateAcceptance*
```
Expected: FAIL — `estimateAcceptance` not defined.

- [ ] **Step 3: Add AcceptanceEstimate data class and method to LlmService.kt**

Add before the class declaration:
```kotlin
data class AcceptanceEstimate(val probability: Int, val reasoning: String)
```

Add as a new method in `LlmService`:
```kotlin
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
```

- [ ] **Step 4: Run all LlmService tests**

```
cd backend && C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd test -Dtest=LlmServiceTest
```
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/rfp/service/LlmService.kt \
        backend/src/test/kotlin/com/rfp/service/LlmServiceTest.kt
git commit -m "feat: LlmService.estimateAcceptance() with probability and reasoning"
```

---

## Task 3: Enrich Alternatives JSON in MatchingEngineService

**Files:**
- Modify: `backend/src/main/kotlin/com/rfp/service/MatchingEngineService.kt`
- Modify: `backend/src/test/kotlin/com/rfp/service/MatchingEngineServiceTest.kt`

**Interfaces:**
- Consumes: `ProductPriceRepository` (new constructor dependency)
- Produces: `matchResult.alternatives` JSON now contains `price`, `currency`, `description`, `manualLink`, `supplierName` per alternative

- [ ] **Step 1: Write the failing test**

Add to `MatchingEngineServiceTest.kt`. First add `productPriceRepo` mock to the class fields and `service()` factory:

```kotlin
private val productPriceRepo = mockk<ProductPriceRepository>()

private fun service() = MatchingEngineService(
    tenderRepo, tenderLineRepo, tenderSupplierRepo, productRepo,
    attrDefRepo, matchResultRepo, productPriceRepo
)
```

Add test:
```kotlin
@Test
fun `alternatives JSON includes price and supplierName`() {
    val line = TenderLine(id = 6L, tender = tender, rawText = "multimeter",
        description = "multimeter", productClass = productClass,
        attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0)))
    val attrDefs = listOf(
        AttributeDef(id = 1L, productClass = productClass, name = "max_voltage",
            label = "Max V", datatype = "numeric", matchOp = "gte", canonicalUnit = "V")
    )
    val best = Product(id = 1L, supplier = supplier, productClass = productClass,
        name = "P1", source = "upload",
        attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1200.0,
            "description" to "A fine tool", "manualLink" to null)))
    val alt = Product(id = 2L, supplier = supplier, productClass = productClass,
        name = "P2", source = "upload",
        attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1100.0,
            "description" to "Alt tool", "manualLink" to "https://example.com")))

    every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
    every { productRepo.findBySupplierIdInAndIsStaleAndNameIgnoreCase(any(), false, any()) } returns emptyList()
    every { productRepo.findBySupplierIdInAndIsStaleAndMpnIgnoreCase(any(), false, any()) } returns emptyList()
    every { productRepo.findBySupplierIdInAndIsStaleAndProductClassId(any(), false, 10L) } returns listOf(best, alt)
    every { attrDefRepo.findByProductClassId(10L) } returns attrDefs
    every { matchResultRepo.findByLineId(6L) } returns null
    every { productPriceRepo.findAllById(any<Iterable<Long>>()) } returns listOf(
        com.rfp.domain.ProductPrice(productId = 2L, price = java.math.BigDecimal("99.50"), currency = "JOD")
    )
    val saved = mutableListOf<com.rfp.domain.MatchResult>()
    every { matchResultRepo.save(capture(saved)) } answers { firstArg() }

    service().matchTender(tender, listOf(1L))

    val altJson = mapper.readTree(saved.last().alternatives)
    val altNode = altJson[0]
    assertEquals("P2", altNode["name"].asText())
    assertEquals(99.50, altNode["price"].asDouble(), 0.01)
    assertEquals("Acme", altNode["supplierName"].asText())
    assertEquals("Alt tool", altNode["description"].asText())
}
```

- [ ] **Step 2: Run test to verify it fails**

```
cd backend && C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd test -Dtest=MatchingEngineServiceTest#alternatives*
```
Expected: FAIL — compile error (constructor mismatch / missing `productPriceRepo`).

- [ ] **Step 3: Add ProductPriceRepository to MatchingEngineService constructor and enrich alternatives**

In `MatchingEngineService.kt`, update the class declaration:
```kotlin
@Service
open class MatchingEngineService(
    private val tenderRepo: TenderRepository,
    private val tenderLineRepo: TenderLineRepository,
    private val tenderSupplierRepo: TenderSupplierRepository,
    private val productRepo: ProductRepository,
    private val attrDefRepo: AttributeDefRepository,
    private val matchResultRepo: MatchResultRepository,
    private val productPriceRepo: ProductPriceRepository
)
```

Replace the `alternatives = mapper.writeValueAsString(...)` block in `matchLine()`:

```kotlin
val alternatives = scored.drop(1).filter { it.score >= 40 }.take(5)

// Fetch prices for all alternatives in one query
val altProductIds = alternatives.map { it.productId }
val priceMap = productPriceRepo.findAllById(altProductIds).associateBy { it.productId }

matchResultRepo.save(MatchResult(
    id = existingId,
    line = line,
    product = bestProduct,
    matchType = "spec",
    score = best.score,
    attributeVerdicts = mapper.writeValueAsString(best.verdicts),
    status = status,
    alternatives = mapper.writeValueAsString(alternatives.map { alt ->
        val altProduct = candidates.first { c -> c.id == alt.productId }
        val altPrice = priceMap[alt.productId]
        val altAttrs: Map<String, Any> = mapper.readValue(altProduct.attributes)
        mapOf(
            "productId" to alt.productId,
            "name" to altProduct.name,
            "mpn" to altProduct.mpn,
            "score" to alt.score,
            "attributeVerdicts" to alt.verdicts,
            "price" to altPrice?.price,
            "currency" to (altPrice?.currency ?: "JOD"),
            "description" to (altAttrs["description"] as? String),
            "manualLink" to (altAttrs["manualLink"] as? String),
            "supplierName" to altProduct.supplier.name
        )
    })
))
```

Also update the existing `alternatives are capped at 5` test — add mock for `productPriceRepo.findAllById`:
```kotlin
every { productPriceRepo.findAllById(any<Iterable<Long>>()) } returns emptyList()
```
Add this line to ALL existing tests in `MatchingEngineServiceTest` that call `service().matchTender(...)` where the spec match path is taken. For exact-match tests, `productPriceRepo` is never called so no mock needed there, but add it to any test that triggers the spec path.

- [ ] **Step 4: Run all MatchingEngineService tests**

```
cd backend && C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd test -Dtest=MatchingEngineServiceTest
```
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/rfp/service/MatchingEngineService.kt \
        backend/src/test/kotlin/com/rfp/service/MatchingEngineServiceTest.kt
git commit -m "feat: enrich match alternatives with price, description, and supplier"
```

---

## Task 4: ProposalService — generateProposals() and overrideLine()

**Files:**
- Create: `backend/src/main/kotlin/com/rfp/service/ProposalService.kt`
- Create: `backend/src/test/kotlin/com/rfp/service/ProposalServiceTest.kt`

**Interfaces:**
- Consumes: `ProposalRepository`, `ProposalLineRepository`, `TenderLineRepository`, `MatchResultRepository`, `ProductRepository`, `ProductPriceRepository`, `AttrDefRepository`, `LlmService`
- Produces: `ProposalService.generateProposals(tenderId: Long)` `@Async("taskExecutor")`
- Produces: `ProposalService.overrideLine(proposalId: Long, lineId: Long, newProductId: Long)` `@Async("taskExecutor")`
- Produces: `ProposalService.getProposals(tenderId: Long): List<ProposalDto>` (sync, for controller)

Helper types (define at top of ProposalService.kt):
```kotlin
data class EnrichedAlt(
    val productId: Long, val name: String, val mpn: String?,
    val score: Int, val attributeVerdicts: List<AttributeVerdict>,
    val price: java.math.BigDecimal?, val currency: String,
    val description: String?, val manualLink: String?, val supplierName: String
)

data class SelectedProductDto(
    val id: Long, val name: String, val mpn: String?,
    val price: java.math.BigDecimal?, val currency: String,
    val supplierName: String, val description: String?, val manualLink: String?
)

data class ProposalLineDto(
    val lineId: Long, val description: String?, val qty: java.math.BigDecimal?,
    val matchScore: java.math.BigDecimal?,
    val selectedProduct: SelectedProductDto?,
    val acceptanceProbability: java.math.BigDecimal?,
    val llmReasoning: String?, val isOverridden: Boolean,
    val alternatives: List<EnrichedAlt>
)

data class ProposalDto(
    val id: Long, val variant: String, val status: String,
    val acceptanceRate: java.math.BigDecimal?, val matchScore: java.math.BigDecimal?,
    val isComplete: Boolean, val lines: List<ProposalLineDto>
)
```

- [ ] **Step 1: Write failing tests**

```kotlin
// backend/src/test/kotlin/com/rfp/service/ProposalServiceTest.kt
package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.rfp.domain.*
import com.rfp.repository.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ProposalServiceTest {

    private val mapper = ObjectMapper()
    private val proposalRepo = mockk<ProposalRepository>(relaxed = true)
    private val proposalLineRepo = mockk<ProposalLineRepository>(relaxed = true)
    private val tenderRepo = mockk<TenderRepository>()
    private val tenderLineRepo = mockk<TenderLineRepository>()
    private val matchResultRepo = mockk<MatchResultRepository>()
    private val productRepo = mockk<ProductRepository>()
    private val productPriceRepo = mockk<ProductPriceRepository>()
    private val attrDefRepo = mockk<AttributeDefRepository>()
    private val llmService = mockk<LlmService>()

    private val supplier = Supplier(id = 1L, name = "Acme")
    private val productClass = ProductClass(id = 10L, name = "Multimeter")
    private val tender = Tender(id = 1L, userId = 1L, filename = "rfp.pdf", fileType = "pdf")

    private fun service() = ProposalService(
        proposalRepo, proposalLineRepo, tenderRepo, tenderLineRepo,
        matchResultRepo, productRepo, productPriceRepo, attrDefRepo, llmService
    )

    @Test
    fun `PERFECT variant selects the best-score product from matchResult`() {
        val product = Product(id = 5L, supplier = supplier, productClass = productClass,
            name = "Fluke 179", mpn = "FL179", source = "upload",
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0)))
        val line = TenderLine(id = 1L, tender = tender, rawText = "multimeter",
            description = "multimeter 1000V", productClass = productClass,
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0)))
        val matchResult = MatchResult(id = 1L, line = line, product = product,
            matchType = "spec", score = 90, status = "matched",
            attributeVerdicts = mapper.writeValueAsString(
                listOf(mapOf("attr" to "max_voltage", "required" to 1000.0,
                    "offered" to 1000.0, "verdict" to "COMPLIANT"))),
            alternatives = "[]")

        every { tenderRepo.findById(1L) } returns java.util.Optional.of(tender)
        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { matchResultRepo.findByLineId(1L) } returns matchResult
        every { proposalRepo.deleteByTenderId(1L) } just Runs
        every { productPriceRepo.findById(5L) } returns java.util.Optional.of(
            ProductPrice(productId = 5L, price = BigDecimal("320.00"), currency = "JOD"))
        every { proposalRepo.save(any()) } answers { firstArg<Proposal>().copy(id = 10L) }
        every { proposalLineRepo.save(any()) } answers { firstArg() }
        every { llmService.estimateAcceptance(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            AcceptanceEstimate(probability = 88, reasoning = "Good fit.")

        service().generateProposals(1L)

        // PERFECT proposal line should use product id=5
        verify {
            proposalLineRepo.save(match { pl ->
                pl.selectedProduct?.id == 5L && pl.proposal.variant == "PERFECT"
            })
        }
    }

    @Test
    fun `CHEAPEST variant selects lowest-price candidate among score-gt-0 products`() {
        val expensiveProduct = Product(id = 5L, supplier = supplier, productClass = productClass,
            name = "Expensive", source = "upload",
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1200.0)))
        val cheapProduct = Product(id = 6L, supplier = supplier, productClass = productClass,
            name = "Cheap", source = "upload",
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1100.0)))
        val line = TenderLine(id = 1L, tender = tender, rawText = "x",
            description = "x", productClass = productClass,
            attributes = mapper.writeValueAsString(mapOf("max_voltage" to 1000.0)))
        val altJson = mapper.writeValueAsString(listOf(
            mapOf("productId" to 6L, "name" to "Cheap", "mpn" to null,
                "score" to 80, "attributeVerdicts" to emptyList<Any>(),
                "price" to 99.50, "currency" to "JOD",
                "description" to null, "manualLink" to null, "supplierName" to "Acme")))
        val matchResult = MatchResult(id = 1L, line = line, product = expensiveProduct,
            matchType = "spec", score = 90, status = "matched",
            attributeVerdicts = "[]", alternatives = altJson)

        every { tenderRepo.findById(1L) } returns java.util.Optional.of(tender)
        every { tenderLineRepo.findByTenderId(1L) } returns listOf(line)
        every { matchResultRepo.findByLineId(1L) } returns matchResult
        every { proposalRepo.deleteByTenderId(1L) } just Runs
        every { productRepo.findById(6L) } returns java.util.Optional.of(cheapProduct)
        every { productPriceRepo.findById(5L) } returns java.util.Optional.of(
            ProductPrice(productId = 5L, price = BigDecimal("500.00"), currency = "JOD"))
        every { productPriceRepo.findById(6L) } returns java.util.Optional.of(
            ProductPrice(productId = 6L, price = BigDecimal("99.50"), currency = "JOD"))
        every { proposalRepo.save(any()) } answers { firstArg<Proposal>().copy(id = 10L) }
        every { proposalLineRepo.save(any()) } answers { firstArg() }
        every { llmService.estimateAcceptance(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            AcceptanceEstimate(probability = 75, reasoning = "Budget option.")

        service().generateProposals(1L)

        verify {
            proposalLineRepo.save(match { pl ->
                pl.selectedProduct?.id == 6L && pl.proposal.variant == "CHEAPEST"
            })
        }
    }

    @Test
    fun `overrideLine updates selectedProduct and clears probability`() {
        val proposal = Proposal(id = 10L, tender = tender, variant = "PERFECT",
            status = "READY", isComplete = true)
        val product = Product(id = 5L, supplier = supplier, productClass = productClass,
            name = "Original", source = "upload", attributes = "{}")
        val newProduct = Product(id = 7L, supplier = supplier, productClass = productClass,
            name = "Override", source = "upload", attributes = "{}")
        val line = TenderLine(id = 1L, tender = tender, rawText = "x",
            description = "x", productClass = productClass, attributes = "{}")
        val existingPL = ProposalLine(id = 100L, proposal = proposal, line = line,
            selectedProduct = product, matchScore = BigDecimal("90"),
            acceptanceProbability = BigDecimal("88"), llmReasoning = "old")
        val allLines = listOf(existingPL)

        every { proposalRepo.findById(10L) } returns java.util.Optional.of(proposal)
        every { proposalLineRepo.findByProposalIdAndLineId(10L, 1L) } returns existingPL
        every { productRepo.findById(7L) } returns java.util.Optional.of(newProduct)
        every { productPriceRepo.findById(7L) } returns java.util.Optional.empty()
        every { tenderLineRepo.findById(1L) } returns java.util.Optional.of(line)
        every { attrDefRepo.findByProductClassId(any()) } returns emptyList()
        every { proposalLineRepo.save(any()) } answers { firstArg() }
        every { proposalLineRepo.findByProposalId(10L) } returns allLines
        every { proposalRepo.save(any()) } answers { firstArg() }
        every { llmService.estimateAcceptance(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            AcceptanceEstimate(probability = 71, reasoning = "Overridden product.")

        service().overrideLine(10L, 1L, 7L)

        verify { proposalLineRepo.save(match { pl ->
            pl.selectedProduct?.id == 7L && pl.isOverridden && pl.acceptanceProbability == null
        }) }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
cd backend && C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd test -Dtest=ProposalServiceTest
```
Expected: FAIL — `ProposalService` class not found.

- [ ] **Step 3: Write ProposalService**

```kotlin
// backend/src/main/kotlin/com/rfp/service/ProposalService.kt
package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rfp.domain.*
import com.rfp.repository.*
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

data class EnrichedAlt(
    val productId: Long, val name: String, val mpn: String?,
    val score: Int, val attributeVerdicts: List<Map<String, Any?>>,
    val price: BigDecimal?, val currency: String,
    val description: String?, val manualLink: String?, val supplierName: String
)

data class SelectedProductDto(
    val id: Long, val name: String, val mpn: String?,
    val price: BigDecimal?, val currency: String,
    val supplierName: String, val description: String?, val manualLink: String?
)

data class ProposalLineDto(
    val lineId: Long, val description: String?, val qty: BigDecimal?,
    val matchScore: BigDecimal?, val selectedProduct: SelectedProductDto?,
    val acceptanceProbability: BigDecimal?, val llmReasoning: String?,
    val isOverridden: Boolean, val alternatives: List<EnrichedAlt>
)

data class ProposalDto(
    val id: Long, val variant: String, val status: String,
    val acceptanceRate: BigDecimal?, val matchScore: BigDecimal?,
    val isComplete: Boolean, val lines: List<ProposalLineDto>
)

@Service
open class ProposalService(
    private val proposalRepo: ProposalRepository,
    private val proposalLineRepo: ProposalLineRepository,
    private val tenderRepo: TenderRepository,
    private val tenderLineRepo: TenderLineRepository,
    private val matchResultRepo: MatchResultRepository,
    private val productRepo: ProductRepository,
    private val productPriceRepo: ProductPriceRepository,
    private val attrDefRepo: AttributeDefRepository,
    private val llmService: LlmService
) {
    private val mapper = ObjectMapper().apply { findAndRegisterModules() }

    @Async("taskExecutor")
    open fun generateProposals(tenderId: Long) {
        val tender = tenderRepo.findById(tenderId).orElseThrow()
        val lines = tenderLineRepo.findByTenderId(tenderId)
        proposalRepo.deleteByTenderId(tenderId)

        val variants = listOf("PERFECT", "BEST_ACCEPTANCE", "CHEAPEST")
        val proposals = variants.map { variant ->
            proposalRepo.save(Proposal(tender = tender, variant = variant))
        }

        proposals.forEach { proposal ->
            try {
                generateVariantLines(proposal, lines)
                val pLines = proposalLineRepo.findByProposalId(proposal.id)
                val avgAcceptance = pLines.mapNotNull { it.acceptanceProbability }
                    .takeIf { it.isNotEmpty() }
                    ?.let { list -> list.reduce(BigDecimal::add).divide(BigDecimal(list.size), 2, RoundingMode.HALF_UP) }
                val avgScore = pLines.mapNotNull { it.matchScore }
                    .takeIf { it.isNotEmpty() }
                    ?.let { list -> list.reduce(BigDecimal::add).divide(BigDecimal(list.size), 2, RoundingMode.HALF_UP) }
                val isComplete = pLines.none { it.selectedProduct == null || it.acceptanceProbability == BigDecimal.ZERO }
                proposalRepo.save(proposal.copy(
                    acceptanceRate = avgAcceptance, matchScore = avgScore,
                    isComplete = isComplete, status = "READY",
                    updatedAt = java.time.Instant.now()
                ))
            } catch (e: Exception) {
                proposalRepo.save(proposal.copy(status = "FAILED", updatedAt = java.time.Instant.now()))
            }
        }
    }

    private fun generateVariantLines(proposal: Proposal, lines: List<TenderLine>) {
        lines.forEach { line ->
            val matchResult = matchResultRepo.findByLineId(line.id)
            val alts: List<EnrichedAlt> = if (matchResult?.alternatives != null && matchResult.alternatives != "[]")
                mapper.readValue(matchResult.alternatives) else emptyList()

            val (selectedProduct, matchScore) = when (proposal.variant) {
                "PERFECT" -> Pair(matchResult?.product, matchResult?.score?.toBigDecimal())
                "BEST_ACCEPTANCE" -> selectBestAcceptance(line, matchResult, alts)
                "CHEAPEST" -> selectCheapest(matchResult, alts)
                else -> Pair(matchResult?.product, matchResult?.score?.toBigDecimal())
            }

            val estimate = if (selectedProduct != null) {
                computeEstimate(line, matchResult, selectedProduct, alts)
            } else null

            proposalLineRepo.save(ProposalLine(
                proposal = proposal, line = line,
                selectedProduct = selectedProduct,
                matchScore = matchScore,
                acceptanceProbability = estimate?.probability?.toBigDecimal(),
                llmReasoning = estimate?.reasoning
            ))
        }
    }

    private fun selectBestAcceptance(
        line: TenderLine,
        matchResult: MatchResult?,
        alts: List<EnrichedAlt>
    ): Pair<Product?, BigDecimal?> {
        if (matchResult?.product == null) return Pair(null, null)
        val candidates = mutableListOf<Pair<Product, Int>>()
        candidates.add(Pair(matchResult.product, matchResult.score))
        alts.forEach { alt ->
            productRepo.findById(alt.productId).orElse(null)?.let { p ->
                candidates.add(Pair(p, alt.score))
            }
        }
        val lineAttrs: Map<String, Any> = mapper.readValue(line.attributes)
        val best = candidates.maxByOrNull { (product, score) ->
            val productAttrs: Map<String, Any> = mapper.readValue(product.attributes)
            val price = productPriceRepo.findById(product.id).orElse(null)
            val verdictsStr = buildVerdictsStr(matchResult, alts, product.id)
            try {
                llmService.estimateAcceptance(
                    lineDescription = line.description ?: line.rawText,
                    lineAttrs = lineAttrs,
                    productName = product.name,
                    productMpn = product.mpn,
                    productAttrs = productAttrs,
                    verdictsStr = verdictsStr,
                    price = price?.price,
                    currency = price?.currency ?: "JOD"
                ).probability
            } catch (_: Exception) { score }
        }
        return Pair(best?.first, best?.second?.toBigDecimal())
    }

    private fun selectCheapest(
        matchResult: MatchResult?,
        alts: List<EnrichedAlt>
    ): Pair<Product?, BigDecimal?> {
        if (matchResult?.product == null) return Pair(null, null)
        data class Candidate(val product: Product, val score: Int, val price: BigDecimal?)
        val candidates = mutableListOf<Candidate>()
        productPriceRepo.findById(matchResult.product.id).orElse(null).let { p ->
            candidates.add(Candidate(matchResult.product, matchResult.score, p?.price))
        }
        alts.filter { it.score > 0 }.forEach { alt ->
            productRepo.findById(alt.productId).orElse(null)?.let { p ->
                candidates.add(Candidate(p, alt.score, alt.price))
            }
        }
        val cheapest = candidates
            .sortedWith(compareBy(nullsLast()) { it.price })
            .firstOrNull()
        return Pair(cheapest?.product, cheapest?.score?.toBigDecimal())
    }

    private fun computeEstimate(
        line: TenderLine,
        matchResult: MatchResult?,
        product: Product,
        alts: List<EnrichedAlt>
    ): AcceptanceEstimate? {
        return try {
            val lineAttrs: Map<String, Any> = mapper.readValue(line.attributes)
            val productAttrs: Map<String, Any> = mapper.readValue(product.attributes)
            val price = productPriceRepo.findById(product.id).orElse(null)
            val verdictsStr = buildVerdictsStr(matchResult, alts, product.id)
            llmService.estimateAcceptance(
                lineDescription = line.description ?: line.rawText,
                lineAttrs = lineAttrs,
                productName = product.name,
                productMpn = product.mpn,
                productAttrs = productAttrs,
                verdictsStr = verdictsStr,
                price = price?.price,
                currency = price?.currency ?: "JOD"
            )
        } catch (_: Exception) { null }
    }

    private fun buildVerdictsStr(matchResult: MatchResult?, alts: List<EnrichedAlt>, productId: Long): String {
        if (matchResult?.product?.id == productId) {
            val verdicts: List<Map<String, Any?>> = if (matchResult.attributeVerdicts != "[]")
                mapper.readValue(matchResult.attributeVerdicts) else emptyList()
            return verdicts.joinToString(", ") { "${it["attr"]}: ${it["verdict"]}" }
        }
        val alt = alts.firstOrNull { it.productId == productId }
        return alt?.attributeVerdicts?.joinToString(", ") { "${it["attr"]}: ${it["verdict"]}" } ?: ""
    }

    @Async("taskExecutor")
    open fun overrideLine(proposalId: Long, lineId: Long, newProductId: Long) {
        val proposal = proposalRepo.findById(proposalId).orElseThrow()
        val pl = proposalLineRepo.findByProposalIdAndLineId(proposalId, lineId) ?: return
        val newProduct = productRepo.findById(newProductId).orElseThrow()

        // Clear and save with overridden product but null probability (computing)
        proposalLineRepo.save(pl.copy(
            selectedProduct = newProduct, isOverridden = true,
            acceptanceProbability = null, llmReasoning = null,
            updatedAt = java.time.Instant.now()
        ))

        // Compute estimate for new product
        val line = pl.line
        val alts: List<EnrichedAlt> = try {
            val mr = matchResultRepo.findByLineId(lineId)
            if (mr?.alternatives != null && mr.alternatives != "[]")
                mapper.readValue(mr.alternatives) else emptyList()
        } catch (_: Exception) { emptyList() }

        val estimate = computeEstimate(line, matchResultRepo.findByLineId(lineId), newProduct, alts)
        val attrDefs = attrDefRepo.findByProductClassId(line.productClass?.id ?: 0)
        val productAttrs: Map<String, Any> = mapper.readValue(newProduct.attributes)
        val lineAttrs: Map<String, Any> = mapper.readValue(line.attributes)
        // Compute match score for new product
        val defMap = attrDefs.associateBy { it.name }
        val compliant = lineAttrs.count { (k, req) ->
            val def = defMap[k] ?: return@count false
            val offered = productAttrs[k] ?: return@count false
            evalCompliant(def, req, offered)
        }
        val newMatchScore = if (lineAttrs.isEmpty()) BigDecimal.ZERO
            else (compliant * 100 / lineAttrs.size).toBigDecimal()

        proposalLineRepo.save(pl.copy(
            selectedProduct = newProduct, isOverridden = true,
            matchScore = newMatchScore,
            acceptanceProbability = estimate?.probability?.toBigDecimal(),
            llmReasoning = estimate?.reasoning,
            updatedAt = java.time.Instant.now()
        ))

        // Recompute proposal rollup
        val allLines = proposalLineRepo.findByProposalId(proposalId)
        val avgAcceptance = allLines.mapNotNull { it.acceptanceProbability }
            .takeIf { it.isNotEmpty() }
            ?.let { list -> list.reduce(BigDecimal::add).divide(BigDecimal(list.size), 2, RoundingMode.HALF_UP) }
        val avgScore = allLines.mapNotNull { it.matchScore }
            .takeIf { it.isNotEmpty() }
            ?.let { list -> list.reduce(BigDecimal::add).divide(BigDecimal(list.size), 2, RoundingMode.HALF_UP) }
        val isComplete = allLines.none { it.selectedProduct == null || it.acceptanceProbability == BigDecimal.ZERO }
        proposalRepo.save(proposal.copy(
            acceptanceRate = avgAcceptance, matchScore = avgScore,
            isComplete = isComplete, updatedAt = java.time.Instant.now()
        ))
    }

    private fun evalCompliant(def: AttributeDef, required: Any, offered: Any): Boolean {
        fun toDouble(v: Any) = when (v) { is Number -> v.toDouble(); else -> v.toString().toDoubleOrNull() }
        return when (def.matchOp) {
            "eq" -> when (def.datatype) {
                "numeric" -> {
                    val r = toDouble(required) ?: return false
                    val o = toDouble(offered) ?: return false
                    kotlin.math.abs(r - o) < 1e-9
                }
                else -> required.toString().trim().equals(offered.toString().trim(), ignoreCase = true)
            }
            "gte" -> { val r = toDouble(required) ?: return false; val o = toDouble(offered) ?: return false; o >= r }
            "lte" -> { val r = toDouble(required) ?: return false; val o = toDouble(offered) ?: return false; o <= r }
            else -> false
        }
    }

    fun getProposals(tenderId: Long): List<ProposalDto> {
        return proposalRepo.findByTenderId(tenderId).map { proposal ->
            val pLines = proposalLineRepo.findByProposalId(proposal.id)
            val lineDtos = pLines.map { pl ->
                val mr = matchResultRepo.findByLineId(pl.line.id)
                val alts: List<EnrichedAlt> = if (mr?.alternatives != null && mr.alternatives != "[]")
                    try { mapper.readValue(mr.alternatives) } catch (_: Exception) { emptyList() }
                    else emptyList()
                val sp = pl.selectedProduct?.let { p ->
                    val price = productPriceRepo.findById(p.id).orElse(null)
                    val attrs: Map<String, Any> = mapper.readValue(p.attributes)
                    SelectedProductDto(
                        id = p.id, name = p.name, mpn = p.mpn,
                        price = price?.price, currency = price?.currency ?: "JOD",
                        supplierName = p.supplier.name,
                        description = attrs["description"] as? String,
                        manualLink = attrs["manualLink"] as? String
                    )
                }
                ProposalLineDto(
                    lineId = pl.line.id,
                    description = pl.line.description,
                    qty = pl.line.qty,
                    matchScore = pl.matchScore,
                    selectedProduct = sp,
                    acceptanceProbability = pl.acceptanceProbability,
                    llmReasoning = pl.llmReasoning,
                    isOverridden = pl.isOverridden,
                    alternatives = alts
                )
            }
            ProposalDto(
                id = proposal.id, variant = proposal.variant, status = proposal.status,
                acceptanceRate = proposal.acceptanceRate, matchScore = proposal.matchScore,
                isComplete = proposal.isComplete, lines = lineDtos
            )
        }
    }
}
```

- [ ] **Step 4: Run ProposalService tests**

```
cd backend && C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd test -Dtest=ProposalServiceTest
```
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/rfp/service/ProposalService.kt \
        backend/src/test/kotlin/com/rfp/service/ProposalServiceTest.kt
git commit -m "feat: ProposalService generates three proposal variants with acceptance estimation"
```

---

## Task 5: ProposalController

**Files:**
- Create: `backend/src/main/kotlin/com/rfp/controller/ProposalController.kt`
- Create: `backend/src/test/kotlin/com/rfp/controller/ProposalControllerTest.kt`

**Interfaces:**
- Consumes: `ProposalService.generateProposals()`, `ProposalService.getProposals()`, `ProposalService.overrideLine()`
- Produces: `POST /rfp/{id}/proposals` → `{status: "generating"}`
- Produces: `GET /rfp/{id}/proposals` → `List<ProposalDto>` as JSON
- Produces: `PATCH /rfp/{id}/proposals/{proposalId}/lines/{lineId}` body `{productId}` → `{status: "ok"}`
- Produces: `GET /rfp/{id}/proposals/{proposalId}/lines/{lineId}/search?q=` → `List<SearchResultDto>`

- [ ] **Step 1: Write failing controller test**

```kotlin
// backend/src/test/kotlin/com/rfp/controller/ProposalControllerTest.kt
package com.rfp.controller

import com.ninjasquad.springmockk.MockkBean
import com.rfp.security.JwtUtil
import com.rfp.service.ProposalDto
import com.rfp.service.ProposalService
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(ProposalController::class)
class ProposalControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @MockkBean lateinit var proposalService: ProposalService
    @MockkBean lateinit var jwtUtil: JwtUtil

    @Test
    @WithMockUser
    fun `POST proposals triggers generation and returns generating status`() {
        every { proposalService.generateProposals(1L) } just Runs

        mvc.perform(post("/rfp/1/proposals").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("generating"))
    }

    @Test
    @WithMockUser
    fun `GET proposals returns list`() {
        every { proposalService.getProposals(1L) } returns listOf(
            ProposalDto(id = 1L, variant = "PERFECT", status = "READY",
                acceptanceRate = java.math.BigDecimal("89.00"),
                matchScore = java.math.BigDecimal("94.00"),
                isComplete = true, lines = emptyList())
        )

        mvc.perform(get("/rfp/1/proposals"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].variant").value("PERFECT"))
            .andExpect(jsonPath("$[0].status").value("READY"))
            .andExpect(jsonPath("$[0].acceptanceRate").value(89.0))
    }

    @Test
    @WithMockUser
    fun `PATCH override returns ok`() {
        every { proposalService.overrideLine(1L, 5L, 42L) } just Runs

        mvc.perform(patch("/rfp/1/proposals/1/lines/5")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"productId": 42}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ok"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
cd backend && C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd test -Dtest=ProposalControllerTest
```
Expected: FAIL — `ProposalController` not found.

- [ ] **Step 3: Write ProposalController**

```kotlin
// backend/src/main/kotlin/com/rfp/controller/ProposalController.kt
package com.rfp.controller

import com.rfp.repository.MatchResultRepository
import com.rfp.repository.ProductRepository
import com.rfp.repository.TenderLineRepository
import com.rfp.repository.TenderSupplierRepository
import com.rfp.repository.ProductPriceRepository
import com.rfp.repository.AttributeDefRepository
import com.rfp.service.ProposalService
import com.rfp.service.ProposalDto
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class OverrideRequest(val productId: Long)

data class SearchResultDto(
    val productId: Long, val name: String, val mpn: String?,
    val score: Int, val price: java.math.BigDecimal?, val currency: String,
    val supplierName: String
)

@RestController
@RequestMapping("/rfp/{rfpId}/proposals")
class ProposalController(
    private val proposalService: ProposalService,
    private val tenderLineRepo: TenderLineRepository,
    private val matchResultRepo: MatchResultRepository,
    private val productRepo: ProductRepository,
    private val productPriceRepo: ProductPriceRepository,
    private val attrDefRepo: AttributeDefRepository,
    private val tenderSupplierRepo: TenderSupplierRepository
) {
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper().apply { findAndRegisterModules() }

    @PostMapping
    fun generate(@PathVariable rfpId: Long): ResponseEntity<Map<String, String>> {
        proposalService.generateProposals(rfpId)
        return ResponseEntity.ok(mapOf("status" to "generating"))
    }

    @GetMapping
    fun list(@PathVariable rfpId: Long): ResponseEntity<List<ProposalDto>> =
        ResponseEntity.ok(proposalService.getProposals(rfpId))

    @PatchMapping("/{proposalId}/lines/{lineId}")
    fun override(
        @PathVariable rfpId: Long,
        @PathVariable proposalId: Long,
        @PathVariable lineId: Long,
        @RequestBody req: OverrideRequest
    ): ResponseEntity<Map<String, String>> {
        proposalService.overrideLine(proposalId, lineId, req.productId)
        return ResponseEntity.ok(mapOf("status" to "ok"))
    }

    @GetMapping("/{proposalId}/lines/{lineId}/search")
    fun search(
        @PathVariable rfpId: Long,
        @PathVariable proposalId: Long,
        @PathVariable lineId: Long,
        @RequestParam q: String
    ): ResponseEntity<List<SearchResultDto>> {
        val line = tenderLineRepo.findById(lineId).orElseThrow()
        val supplierIds = tenderSupplierRepo.findSupplierIdsByTenderId(rfpId)
        val classId = line.productClass?.id
        val attrDefs = if (classId != null) attrDefRepo.findByProductClassId(classId) else emptyList()
        val lineAttrs: Map<String, Any> = mapper.readValue(line.attributes, mapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java))

        val page = productRepo.searchByNameOrMpn(q, PageRequest.of(0, 10))
        val filtered = page.content.filter { p ->
            supplierIds.contains(p.supplier.id) && !p.isStale
        }

        val results = filtered.map { p ->
            val productAttrs: Map<String, Any> = mapper.readValue(p.attributes, mapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java))
            val defMap = attrDefs.associateBy { it.name }
            val compliant = lineAttrs.count { (k, req) ->
                val def = defMap[k] ?: return@count false
                val offered = productAttrs[k] ?: return@count false
                evalCompliant(def, req, offered)
            }
            val score = if (lineAttrs.isEmpty()) 0 else (compliant * 100 / lineAttrs.size)
            val price = productPriceRepo.findById(p.id).orElse(null)
            SearchResultDto(
                productId = p.id, name = p.name, mpn = p.mpn, score = score,
                price = price?.price, currency = price?.currency ?: "JOD",
                supplierName = p.supplier.name
            )
        }.sortedByDescending { it.score }.take(10)

        return ResponseEntity.ok(results)
    }

    private fun evalCompliant(def: com.rfp.domain.AttributeDef, required: Any, offered: Any): Boolean {
        fun toDouble(v: Any) = when (v) { is Number -> v.toDouble(); else -> v.toString().toDoubleOrNull() }
        return when (def.matchOp) {
            "eq" -> when (def.datatype) {
                "numeric" -> { val r = toDouble(required) ?: return false; val o = toDouble(offered) ?: return false; kotlin.math.abs(r - o) < 1e-9 }
                else -> required.toString().trim().equals(offered.toString().trim(), ignoreCase = true)
            }
            "gte" -> { val r = toDouble(required) ?: return false; val o = toDouble(offered) ?: return false; o >= r }
            "lte" -> { val r = toDouble(required) ?: return false; val o = toDouble(offered) ?: return false; o <= r }
            else -> false
        }
    }
}
```

- [ ] **Step 4: Run ProposalController tests**

```
cd backend && C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd test -Dtest=ProposalControllerTest
```
Expected: all PASS.

- [ ] **Step 5: Run all backend tests**

```
cd backend && C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd test
```
Expected: all PASS (fix any failures before proceeding).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/com/rfp/controller/ProposalController.kt \
        backend/src/test/kotlin/com/rfp/controller/ProposalControllerTest.kt
git commit -m "feat: ProposalController with generate, list, override, and search endpoints"
```

---

## Task 6: ReportService + RfpController — Proposal-Aware Export

**Files:**
- Modify: `backend/src/main/kotlin/com/rfp/service/ReportService.kt`
- Modify: `backend/src/main/kotlin/com/rfp/controller/RfpController.kt`
- Modify: `backend/src/test/kotlin/com/rfp/service/ReportServiceTest.kt`
- Modify: `backend/src/test/kotlin/com/rfp/controller/RfpControllerTest.kt`

**Interfaces:**
- Consumes: `ProposalRepository`, `ProposalLineRepository` (new deps for `ReportService`)
- Produces: `ReportService.exportXlsx(tenderId, proposalId?)`, `ReportService.exportPdf(tenderId, proposalId?)`
- Produces: `GET /rfp/{id}/report/export?format=xlsx&proposalId={id}` — when `proposalId` present, uses proposal selections

- [ ] **Step 1: Write failing test for proposal-aware export**

Add to `backend/src/test/kotlin/com/rfp/service/ReportServiceTest.kt` (read the existing file first to see its current structure, then add this test following the same pattern):

```kotlin
@Test
fun `exportXlsx with proposalId uses proposal selected products`() {
    // proposalId present → report uses proposal_line.selectedProduct instead of matchResult.product
    // The test verifies that the xlsx bytes are non-empty and the method doesn't throw
    val proposalId = 10L
    val bytes = service.exportXlsx(tenderId = 1L, proposalId = proposalId)
    assertThat(bytes).isNotEmpty()
}
```
(The existing `ReportServiceTest` has mocks for `tenderRepo`, `tenderLineRepo`, `matchResultRepo` — add mocks for `proposalRepo` and `proposalLineRepo` alongside those.)

Read `ReportServiceTest.kt` before modifying to ensure you follow the exact same test setup pattern.

- [ ] **Step 2: Update ReportService to accept optional proposalId**

In `ReportService.kt`, add `ProposalRepository` and `ProposalLineRepository` to the constructor:

```kotlin
@Service
class ReportService(
    private val tenderRepo: TenderRepository,
    private val tenderLineRepo: TenderLineRepository,
    private val matchResultRepo: MatchResultRepository,
    private val proposalRepo: com.rfp.repository.ProposalRepository,
    private val proposalLineRepo: com.rfp.repository.ProposalLineRepository
)
```

Change `exportXlsx(tenderId: Long)` signature to `exportXlsx(tenderId: Long, proposalId: Long? = null)`. At the top of the method:

```kotlin
fun exportXlsx(tenderId: Long, proposalId: Long? = null): ByteArray {
    tenderRepo.findById(tenderId).orElseThrow { NoSuchElementException("Tender $tenderId not found") }
    // If proposalId present, build product map from proposal lines
    val proposalProductMap: Map<Long, com.rfp.domain.Product?> = if (proposalId != null) {
        proposalLineRepo.findByProposalId(proposalId)
            .associate { it.line.id to it.selectedProduct }
    } else emptyMap()

    val results = matchResultRepo.findByLineTenderId(tenderId)
    XSSFWorkbook().use { wb ->
        val sheet = wb.createSheet("Report")
        val header = sheet.createRow(0)
        listOf("Line", "Description", "Qty", "Matched Product", "MPN",
            "Match Type", "Score", "Status", "Price", "Currency", "Alternatives Count")
            .forEachIndexed { i, h -> header.createCell(i).setCellValue(h) }
        results.forEachIndexed { idx, r ->
            // When proposalId present, use proposal's selected product instead of r.product
            val product = if (proposalId != null) proposalProductMap[r.line.id] else r.product
            val row = sheet.createRow(idx + 1)
            row.createCell(0).setCellValue(r.line.lineNo ?: (idx + 1).toString())
            row.createCell(1).setCellValue(r.line.description ?: r.line.rawText)
            row.createCell(2).setCellValue(r.line.qty?.toDouble() ?: 0.0)
            row.createCell(3).setCellValue(product?.name ?: "")
            row.createCell(4).setCellValue(product?.mpn ?: "")
            row.createCell(5).setCellValue(r.matchType ?: "")
            row.createCell(6).setCellValue(r.score.toDouble())
            row.createCell(7).setCellValue(r.status)
            row.createCell(8)
            row.createCell(9).setCellValue("JOD")
        }
        val out = java.io.ByteArrayOutputStream()
        wb.write(out)
        return out.toByteArray()
    }
}
```

Apply same pattern to `exportPdf(tenderId: Long, proposalId: Long? = null)` — use `proposalProductMap[r.line.id]` instead of `r.product` when proposalId is present.

- [ ] **Step 3: Update RfpController export endpoint**

In `RfpController.kt`, change the `export` function signature:

```kotlin
@GetMapping("/{id}/report/export")
fun export(
    @PathVariable id: Long,
    @RequestParam format: String,
    @RequestParam(required = false) proposalId: Long?
): ResponseEntity<ByteArray> =
    when (format.lowercase()) {
        "xlsx" -> ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=report-$id.xlsx")
            .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .body(reportService.exportXlsx(id, proposalId))
        "pdf" -> ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=report-$id.pdf")
            .header("Content-Type", "application/pdf")
            .body(reportService.exportPdf(id, proposalId))
        else -> ResponseEntity.badRequest().build()
    }
```

Also add `ProposalRepository` and `ProposalLineRepository` as `@MockkBean` in `RfpControllerTest.kt` since `ReportService` now has those dependencies (Spring needs them for `@WebMvcTest`).

- [ ] **Step 4: Run all tests**

```
cd backend && C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd test
```
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/rfp/service/ReportService.kt \
        backend/src/main/kotlin/com/rfp/controller/RfpController.kt \
        backend/src/test/kotlin/com/rfp/service/ReportServiceTest.kt \
        backend/src/test/kotlin/com/rfp/controller/RfpControllerTest.kt
git commit -m "feat: proposal-aware XLSX/PDF export via optional proposalId param"
```

---

## Task 7: Frontend Types and API

**Files:**
- Modify: `frontend/src/lib/types.ts`
- Modify: `frontend/src/lib/api.ts`

**Interfaces:**
- Produces: TypeScript types `SelectedProduct`, `ProposalAlternative`, `ProposalLine`, `Proposal`
- Produces: `api.proposals.generate(rfpId, token)`, `api.proposals.list(rfpId, token)`, `api.proposals.override(rfpId, proposalId, lineId, productId, token)`, `api.proposals.search(rfpId, proposalId, lineId, q, token)`

- [ ] **Step 1: Add types to types.ts**

Append to `frontend/src/lib/types.ts`:

```typescript
export interface SelectedProduct {
  id: number;
  name: string;
  mpn: string | null;
  price: number | null;
  currency: string;
  supplierName: string;
  description: string | null;
  manualLink: string | null;
}

export interface ProposalAlternative {
  productId: number;
  name: string;
  mpn: string | null;
  score: number;
  price: number | null;
  currency: string;
  description: string | null;
  supplierName: string;
}

export interface ProposalLine {
  lineId: number;
  description: string | null;
  qty: number | null;
  matchScore: number | null;
  selectedProduct: SelectedProduct | null;
  acceptanceProbability: number | null;
  llmReasoning: string | null;
  isOverridden: boolean;
  alternatives: ProposalAlternative[];
}

export interface Proposal {
  id: number;
  variant: 'PERFECT' | 'BEST_ACCEPTANCE' | 'CHEAPEST';
  status: 'GENERATING' | 'READY' | 'FAILED';
  acceptanceRate: number | null;
  matchScore: number | null;
  isComplete: boolean;
  lines: ProposalLine[];
}

export interface ProductSearchResult {
  productId: number;
  name: string;
  mpn: string | null;
  score: number;
  price: number | null;
  currency: string;
  supplierName: string;
}
```

- [ ] **Step 2: Add proposal API calls to api.ts**

Add `proposals` namespace inside the `api` object in `frontend/src/lib/api.ts`:

```typescript
proposals: {
  async generate(rfpId: number, token: string): Promise<{ status: string }> {
    const res = await fetch(`${BASE}/rfp/${rfpId}/proposals`, {
      method: 'POST',
      headers: authHeaders(token),
    });
    return json<{ status: string }>(res);
  },
  async list(rfpId: number, token: string): Promise<import('./types').Proposal[]> {
    const res = await fetch(`${BASE}/rfp/${rfpId}/proposals`, {
      headers: authHeaders(token),
    });
    return json<import('./types').Proposal[]>(res);
  },
  async override(
    rfpId: number, proposalId: number, lineId: number, productId: number, token: string
  ): Promise<{ status: string }> {
    const res = await fetch(`${BASE}/rfp/${rfpId}/proposals/${proposalId}/lines/${lineId}`, {
      method: 'PATCH',
      headers: authHeaders(token),
      body: JSON.stringify({ productId }),
    });
    return json<{ status: string }>(res);
  },
  async search(
    rfpId: number, proposalId: number, lineId: number, q: string, token: string
  ): Promise<import('./types').ProductSearchResult[]> {
    const params = new URLSearchParams({ q });
    const res = await fetch(
      `${BASE}/rfp/${rfpId}/proposals/${proposalId}/lines/${lineId}/search?${params}`,
      { headers: authHeaders(token) }
    );
    return json<import('./types').ProductSearchResult[]>(res);
  },
},
```

- [ ] **Step 3: Run frontend type check**

```
cd frontend && npm run build 2>&1 | tail -20
```
Expected: no TypeScript errors related to the new types.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/lib/types.ts frontend/src/lib/api.ts
git commit -m "feat: Proposal TypeScript types and API client methods"
```

---

## Task 8: ProposalPanel Component

**Files:**
- Create: `frontend/src/components/ProposalPanel.tsx`

**Interfaces:**
- Consumes: `Proposal` type from `types.ts`
- Produces: `<ProposalPanel proposals={Proposal[]} onSelect={(p: Proposal) => void} />` — renders 3 cards, calls `onSelect` when "View & Edit" is clicked

- [ ] **Step 1: Write ProposalPanel**

```tsx
// frontend/src/components/ProposalPanel.tsx
'use client';
import type { Proposal } from '@/src/lib/types';

const VARIANT_LABELS: Record<string, string> = {
  PERFECT: 'Perfect Match',
  BEST_ACCEPTANCE: 'Best Acceptance',
  CHEAPEST: 'Cheapest',
};

const VARIANT_DESCRIPTIONS: Record<string, string> = {
  PERFECT: 'Highest technical compliance',
  BEST_ACCEPTANCE: 'Most likely to be accepted',
  CHEAPEST: 'Lowest total cost',
};

function RateBar({ value, color }: { value: number; color: string }) {
  return (
    <div className="flex items-center gap-2 mt-1">
      <div className="flex-1 h-2 bg-slate-100 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${value}%` }} />
      </div>
      <span className="text-sm font-semibold w-10 text-right">{value.toFixed(0)}%</span>
    </div>
  );
}

function ProposalCard({ proposal, onSelect }: { proposal: Proposal; onSelect: () => void }) {
  const isGenerating = proposal.status === 'GENERATING';
  const isFailed = proposal.status === 'FAILED';
  const gapCount = proposal.lines.filter(l => l.selectedProduct === null).length;

  const acceptanceColor =
    (proposal.acceptanceRate ?? 0) >= 70 ? 'bg-emerald-500' :
    (proposal.acceptanceRate ?? 0) >= 40 ? 'bg-amber-400' : 'bg-red-400';

  const scoreColor =
    (proposal.matchScore ?? 0) >= 80 ? 'bg-emerald-500' :
    (proposal.matchScore ?? 0) >= 40 ? 'bg-amber-400' : 'bg-slate-300';

  return (
    <div className={`flex flex-col border rounded-xl p-5 bg-white shadow-sm gap-3 ${
      isFailed ? 'border-red-200' : 'border-slate-200'
    }`}>
      <div>
        <p className="text-xs font-medium uppercase tracking-wide text-slate-400">
          {VARIANT_DESCRIPTIONS[proposal.variant]}
        </p>
        <h3 className="text-base font-semibold text-slate-900 mt-0.5">
          {VARIANT_LABELS[proposal.variant] ?? proposal.variant}
        </h3>
      </div>

      {isGenerating ? (
        <div className="space-y-3 animate-pulse">
          <div className="h-4 bg-slate-100 rounded w-3/4" />
          <div className="h-4 bg-slate-100 rounded w-2/3" />
          <p className="text-xs text-slate-400 mt-1">Computing…</p>
        </div>
      ) : isFailed ? (
        <p className="text-sm text-red-600">Generation failed</p>
      ) : (
        <div className="space-y-2">
          <div>
            <p className="text-xs text-slate-500">Acceptance rate</p>
            <RateBar value={proposal.acceptanceRate ?? 0} color={acceptanceColor} />
          </div>
          <div>
            <p className="text-xs text-slate-500">Match score</p>
            <RateBar value={proposal.matchScore ?? 0} color={scoreColor} />
          </div>
          <div className="flex items-center gap-1.5 text-xs mt-1">
            {proposal.isComplete ? (
              <span className="text-emerald-600 font-medium">✓ Complete</span>
            ) : (
              <span className="text-amber-600 font-medium">⚠ {gapCount} gap{gapCount !== 1 ? 's' : ''}</span>
            )}
          </div>
        </div>
      )}

      <button
        disabled={isGenerating || isFailed}
        onClick={onSelect}
        className="mt-auto text-sm bg-indigo-600 text-white px-3 py-1.5 rounded-md
          hover:bg-indigo-700 disabled:opacity-40 transition-colors"
      >
        View &amp; Edit
      </button>
    </div>
  );
}

interface Props {
  proposals: Proposal[];
  onSelect: (p: Proposal) => void;
}

export function ProposalPanel({ proposals, onSelect }: Props) {
  const order = ['PERFECT', 'BEST_ACCEPTANCE', 'CHEAPEST'];
  const sorted = order.map(v => proposals.find(p => p.variant === v)).filter(Boolean) as Proposal[];

  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
      {sorted.map(p => (
        <ProposalCard key={p.id} proposal={p} onSelect={() => onSelect(p)} />
      ))}
    </div>
  );
}
```

- [ ] **Step 2: Build check**

```
cd frontend && npm run build 2>&1 | grep -i error | head -10
```
Expected: no errors for ProposalPanel.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/ProposalPanel.tsx
git commit -m "feat: ProposalPanel 3-card variant display"
```

---

## Task 9: ProposalDetail Component + Report Page Integration

**Files:**
- Create: `frontend/src/components/ProposalDetail.tsx`
- Modify: `frontend/src/app/rfp/[id]/page.tsx`

**Interfaces:**
- Consumes: `Proposal`, `ProposalLine`, `ProductSearchResult` types; `api.proposals.*`
- Produces: `<ProposalDetail rfpId proposal token onProposalChange />` — interactive line table with Change/override flow
- Report page gains: Generate Proposals button, ProposalPanel, proposal-aware export dropdown

- [ ] **Step 1: Write ProposalDetail component**

```tsx
// frontend/src/components/ProposalDetail.tsx
'use client';
import { Fragment, useState, useCallback } from 'react';
import type { Proposal, ProposalLine, ProposalAlternative, ProductSearchResult } from '@/src/lib/types';
import { api } from '@/src/lib/api';

function AcceptBar({ value }: { value: number | null }) {
  if (value === null) return <span className="text-xs text-slate-400 animate-pulse">computing…</span>;
  const color = value >= 70 ? 'bg-emerald-500' : value >= 40 ? 'bg-amber-400' : 'bg-red-400';
  return (
    <div className="flex items-center gap-2">
      <div className="w-16 h-1.5 bg-slate-100 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${value}%` }} />
      </div>
      <span className="text-xs text-slate-600">{value.toFixed(0)}%</span>
    </div>
  );
}

function ScoreBar({ value }: { value: number | null }) {
  if (value === null) return <span className="text-xs text-slate-400">—</span>;
  const color = value >= 80 ? 'bg-emerald-500' : value >= 40 ? 'bg-amber-400' : 'bg-slate-300';
  return (
    <div className="flex items-center gap-2">
      <div className="w-16 h-1.5 bg-slate-100 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${value}%` }} />
      </div>
      <span className="text-xs text-slate-600">{value.toFixed(0)}</span>
    </div>
  );
}

function AlternativeCard({
  alt,
  onSelect,
}: {
  alt: ProposalAlternative | ProductSearchResult;
  onSelect: () => void;
}) {
  const productId = 'productId' in alt ? alt.productId : alt.productId;
  return (
    <div
      key={productId}
      onClick={onSelect}
      className="border border-slate-200 rounded-lg p-3 cursor-pointer hover:border-indigo-400 hover:bg-indigo-50 transition-colors"
    >
      <p className="text-sm font-medium text-slate-900">{alt.name}</p>
      {alt.mpn && <p className="text-xs text-slate-400 font-mono">{alt.mpn}</p>}
      <div className="flex items-center gap-3 mt-1 text-xs text-slate-500">
        <span>Score: {'score' in alt ? alt.score : '?'}</span>
        {alt.price !== null && alt.price !== undefined && (
          <span>{alt.price} {alt.currency}</span>
        )}
        <span>{alt.supplierName}</span>
      </div>
      {'description' in alt && alt.description && (
        <p className="text-xs text-slate-500 mt-1 italic">{alt.description}</p>
      )}
    </div>
  );
}

interface ChangePanel {
  rfpId: number;
  proposalId: number;
  line: ProposalLine;
  token: string;
  onOverride: (lineId: number, productId: number) => void;
}

function ChangePanel({ rfpId, proposalId, line, token, onOverride }: ChangePanel) {
  const [q, setQ] = useState('');
  const [searchResults, setSearchResults] = useState<ProductSearchResult[]>([]);
  const [searching, setSearching] = useState(false);

  const handleSearch = async (query: string) => {
    setQ(query);
    if (query.length < 2) { setSearchResults([]); return; }
    setSearching(true);
    try {
      const results = await api.proposals.search(rfpId, proposalId, line.lineId, query, token);
      setSearchResults(results);
    } finally { setSearching(false); }
  };

  return (
    <div className="p-4 space-y-3">
      {line.alternatives.length > 0 && (
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-slate-400 mb-2">
            Pre-computed alternatives
          </p>
          <div className="space-y-2">
            {line.alternatives.map(alt => (
              <AlternativeCard
                key={alt.productId}
                alt={alt}
                onSelect={() => onOverride(line.lineId, alt.productId)}
              />
            ))}
          </div>
        </div>
      )}
      <div>
        <p className="text-xs font-medium uppercase tracking-wide text-slate-400 mb-2">
          Search all supplier products
        </p>
        <input
          value={q}
          onChange={e => handleSearch(e.target.value)}
          placeholder="Search by name or MPN…"
          className="border border-slate-300 rounded-md px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
        {searching && <p className="text-xs text-slate-400 mt-1">Searching…</p>}
        {searchResults.length > 0 && (
          <div className="mt-2 space-y-2">
            {searchResults.map(r => (
              <AlternativeCard
                key={r.productId}
                alt={r}
                onSelect={() => onOverride(line.lineId, r.productId)}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

interface Props {
  rfpId: number;
  proposal: Proposal;
  token: string;
  onProposalChange: () => void;
}

export function ProposalDetail({ rfpId, proposal, token, onProposalChange }: Props) {
  const [expandedLineId, setExpandedLineId] = useState<number | null>(null);
  const [changingLineId, setChangingLineId] = useState<number | null>(null);
  const [overridingLineId, setOverridingLineId] = useState<number | null>(null);

  const handleOverride = useCallback(async (lineId: number, productId: number) => {
    setOverridingLineId(lineId);
    setChangingLineId(null);
    try {
      await api.proposals.override(rfpId, proposal.id, lineId, productId, token);
      onProposalChange();
    } finally {
      setOverridingLineId(null);
    }
  }, [rfpId, proposal.id, token, onProposalChange]);

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-3 mb-4">
        <div className="text-sm text-slate-500">
          Acceptance rate:
          <span className="font-semibold text-slate-900 ml-1">
            {proposal.acceptanceRate?.toFixed(1) ?? '—'}%
          </span>
        </div>
        <div className="text-sm text-slate-500">
          Match score:
          <span className="font-semibold text-slate-900 ml-1">
            {proposal.matchScore?.toFixed(1) ?? '—'}%
          </span>
        </div>
        {!proposal.isComplete && (
          <span className="text-xs text-amber-600 font-medium">
            ⚠ {proposal.lines.filter(l => l.selectedProduct === null).length} gap(s)
          </span>
        )}
      </div>

      <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 border-b border-slate-200">
            <tr>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Requirement</th>
              <th className="text-right px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Qty</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Selected Product</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Price</th>
              <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Match</th>
              <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Acceptance</th>
              <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {proposal.lines.map(line => (
              <Fragment key={line.lineId}>
                <tr
                  onClick={() => setExpandedLineId(expandedLineId === line.lineId ? null : line.lineId)}
                  className="hover:bg-slate-50 cursor-pointer transition-colors"
                >
                  <td className="px-4 py-3 text-slate-900 max-w-xs">
                    <span className="line-clamp-2">{line.description}</span>
                    {line.isOverridden && (
                      <span className="ml-1 text-xs text-indigo-500 font-medium">edited</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-right text-slate-600">{line.qty ?? '—'}</td>
                  <td className="px-4 py-3 text-slate-700">
                    {line.selectedProduct
                      ? <span>{line.selectedProduct.name}{line.selectedProduct.mpn && <span className="text-slate-400 font-mono text-xs ml-1">({line.selectedProduct.mpn})</span>}</span>
                      : <span className="text-slate-400 italic">not found</span>
                    }
                  </td>
                  <td className="px-4 py-3 text-slate-600 text-xs">
                    {line.selectedProduct?.price != null
                      ? `${line.selectedProduct.price} ${line.selectedProduct.currency}`
                      : '—'}
                  </td>
                  <td className="px-4 py-3"><ScoreBar value={line.matchScore} /></td>
                  <td className="px-4 py-3">
                    {overridingLineId === line.lineId
                      ? <span className="text-xs text-slate-400 animate-pulse">updating…</span>
                      : <AcceptBar value={line.acceptanceProbability} />
                    }
                  </td>
                  <td className="px-4 py-3" onClick={e => e.stopPropagation()}>
                    <button
                      onClick={() => setChangingLineId(changingLineId === line.lineId ? null : line.lineId)}
                      className="text-xs border border-slate-300 rounded px-2 py-1 hover:bg-slate-50 transition-colors"
                    >
                      {changingLineId === line.lineId ? 'Cancel' : 'Change'}
                    </button>
                  </td>
                </tr>

                {expandedLineId === line.lineId && line.llmReasoning && (
                  <tr className="bg-slate-50">
                    <td colSpan={7} className="px-6 py-2 text-xs text-slate-500 italic">
                      {line.llmReasoning}
                    </td>
                  </tr>
                )}

                {changingLineId === line.lineId && (
                  <tr className="bg-indigo-50">
                    <td colSpan={7} className="px-4 pb-4">
                      <ChangePanel
                        rfpId={rfpId}
                        proposalId={proposal.id}
                        line={line}
                        token={token}
                        onOverride={handleOverride}
                      />
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Update report page to integrate proposals**

In `frontend/src/app/rfp/[id]/page.tsx`:

1. Add imports:
```tsx
import type { Proposal } from '@/src/lib/types';
import { ProposalPanel } from '@/src/components/ProposalPanel';
import { ProposalDetail } from '@/src/components/ProposalDetail';
```

2. Add state inside `ReportPage`:
```tsx
const [proposals, setProposals] = useState<Proposal[]>([]);
const [activeProposal, setActiveProposal] = useState<Proposal | null>(null);
const [generatingProposals, setGeneratingProposals] = useState(false);
```

3. Add proposal polling effect (alongside the existing report fetch):
```tsx
const fetchProposals = useCallback(async () => {
  if (!token) return;
  try {
    const ps = await api.proposals.list(Number(id), token);
    setProposals(ps);
    // If active proposal exists, refresh it too
    if (activeProposal) {
      const updated = ps.find(p => p.id === activeProposal.id);
      if (updated) setActiveProposal(updated);
    }
  } catch { /* ignore */ }
}, [token, id, activeProposal]);

useEffect(() => {
  if (!token) return;
  fetchProposals();
}, [token, id]); // eslint-disable-line react-hooks/exhaustive-deps

// Poll every 3s while any proposal is GENERATING
useEffect(() => {
  const hasGenerating = proposals.some(p => p.status === 'GENERATING');
  if (!hasGenerating) { setGeneratingProposals(false); return; }
  const t = setTimeout(fetchProposals, 3000);
  return () => clearTimeout(t);
}, [proposals, fetchProposals]);
```

4. Add `handleGenerateProposals` function:
```tsx
const handleGenerateProposals = async () => {
  if (!token) return;
  setGeneratingProposals(true);
  try {
    await api.proposals.generate(Number(id), token);
    await fetchProposals();
  } catch { setGeneratingProposals(false); }
};
```

5. Update `handleExport` to accept optional proposalId:
```tsx
const handleExport = async (format: 'pdf' | 'xlsx', proposalId?: number) => {
  const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';
  const params = new URLSearchParams({ format });
  if (proposalId) params.set('proposalId', String(proposalId));
  const res = await fetch(`${apiUrl}/rfp/${id}/report/export?${params}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) return;
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `report-${id}.${format}`;
  a.click();
  URL.revokeObjectURL(url);
};
```

6. Replace the nav export buttons with a dropdown:
```tsx
{/* Replace the two export buttons with: */}
<div className="ml-auto flex gap-2 relative">
  <div className="flex gap-1">
    <button
      onClick={() => handleExport('xlsx')}
      className="text-xs bg-emerald-600 text-white px-3 py-1.5 rounded-md hover:bg-emerald-700 transition-colors"
    >
      Export Excel
    </button>
    {proposals.filter(p => p.status === 'READY').map(p => (
      <button
        key={p.id}
        onClick={() => handleExport('xlsx', p.id)}
        className="text-xs bg-emerald-700 text-white px-2 py-1.5 rounded-md hover:bg-emerald-800 transition-colors"
        title={`Export ${p.variant} proposal`}
      >
        {p.variant === 'PERFECT' ? 'PF' : p.variant === 'BEST_ACCEPTANCE' ? 'BA' : 'CH'}
      </button>
    ))}
  </div>
  <button
    onClick={() => handleExport('pdf')}
    className="text-xs bg-slate-600 text-white px-3 py-1.5 rounded-md hover:bg-slate-700 transition-colors"
  >
    Export PDF
  </button>
</div>
```

7. Add proposal section to the page body (before `<ReportTable>`):
```tsx
{report.status === 'done' && (
  <div className="space-y-4">
    {proposals.length === 0 ? (
      <button
        onClick={handleGenerateProposals}
        disabled={generatingProposals}
        className="text-sm bg-indigo-600 text-white px-4 py-2 rounded-md hover:bg-indigo-700 disabled:opacity-40 transition-colors"
      >
        {generatingProposals ? 'Generating proposals…' : 'Generate Proposals'}
      </button>
    ) : (
      <div className="space-y-4">
        <ProposalPanel proposals={proposals} onSelect={setActiveProposal} />
        {activeProposal && (
          <div className="space-y-2">
            <div className="flex items-center gap-3">
              <h3 className="text-base font-semibold text-slate-900">
                {activeProposal.variant === 'PERFECT' ? 'Perfect Match' :
                 activeProposal.variant === 'BEST_ACCEPTANCE' ? 'Best Acceptance' : 'Cheapest'} Proposal
              </h3>
              <button
                onClick={() => setActiveProposal(null)}
                className="text-xs text-slate-500 hover:text-slate-700"
              >
                ✕ Close
              </button>
            </div>
            <ProposalDetail
              rfpId={Number(id)}
              proposal={activeProposal}
              token={token!}
              onProposalChange={fetchProposals}
            />
          </div>
        )}
      </div>
    )}
  </div>
)}
```

- [ ] **Step 3: Build check**

```
cd frontend && npm run build 2>&1 | grep -iE "error|Error" | grep -v "node_modules" | head -20
```
Expected: no TypeScript errors. Fix any type errors before proceeding.

- [ ] **Step 4: Start dev server and verify visually**

```
cd frontend && npm run dev
```

Navigate to an existing report page (`http://localhost:3000/rfp/{id}` where status is `done`). Verify:
- "Generate Proposals" button appears
- After clicking, spinner cards appear
- Once READY, 3 cards show with rates and scores
- "View & Edit" opens ProposalDetail table
- "Change" button on a line opens the alternatives/search panel

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ProposalDetail.tsx \
        frontend/src/app/rfp/[id]/page.tsx
git commit -m "feat: ProposalDetail interactive line table with override flow and report page integration"
```

---

## Self-Review

**Spec coverage check:**
- ✅ Section 4 (DB tables) → Task 1
- ✅ Section 5a (enrich alternatives) → Task 3
- ✅ Section 5b (estimateAcceptance) → Task 2
- ✅ Section 5c (generateProposals, overrideLine) → Task 4
- ✅ Section 6 (4 API endpoints) → Task 5
- ✅ Section 7a (types) → Task 7
- ✅ Section 7b (report page flow) → Task 9
- ✅ Section 7c (ProposalPanel) → Task 8
- ✅ Section 7d (ProposalDetail + Change flow) → Task 9
- ✅ Section 7e (export with proposalId) → Task 6 + Task 9

**Placeholder scan:** No TBDs. All code blocks are complete.

**Type consistency:**
- `AcceptanceEstimate(probability: Int, reasoning: String)` — defined in Task 2, used in Tasks 4, 5
- `EnrichedAlt`, `SelectedProductDto`, `ProposalLineDto`, `ProposalDto` — defined in `ProposalService.kt` Task 4, consumed in Task 5 controller test
- `SearchResultDto` — defined in `ProposalController.kt` Task 5
- Frontend `Proposal`, `ProposalLine`, `SelectedProduct`, `ProposalAlternative`, `ProductSearchResult` — defined in Task 7, consumed in Tasks 8 and 9
- `api.proposals.*` — defined in Task 7, consumed in Task 9

**Potential issue: `evalCompliant` duplication** — defined in both `ProposalService` and `ProposalController`. This is intentional (YAGNI — shared helper would require a new file for 10 lines of code). Keep as-is.
