# Adaptive Resumable Catalog Crawler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the shallow monolithic supplier scraper with a safe, resumable crawler that can completely ingest catalogs exceeding 5,000 products without falsely marking products stale.

**Architecture:** A durable `crawl_run` owns a persistent prioritized `crawl_url` frontier and immutable product observations. Deterministic code enforces URL safety, robots rules, limits, retries, completeness, and reconciliation; the LLM only classifies ambiguous pages and maps page-sized product data. HTTP is preferred, with a shared Playwright renderer used only for JavaScript-dependent pages.

**Tech Stack:** Kotlin, Spring Boot 3.2, Java 21, PostgreSQL/Flyway, Spring Data JPA, OkHttp, Jsoup, Playwright, PDFBox, Jackson, MockK, JUnit 5, Next.js 16, React 19, TypeScript, Jest, Testing Library.

**Spec:** `docs/superpowers/specs/2026-08-14-adaptive-resumable-crawler-design.md`

## Global Constraints

- Preserve `POST /suppliers/{id}/catalog/scrape`; it must enqueue a durable run.
- Existing file-upload ingestion remains independent and operational.
- Only HTTP and HTTPS URLs may be fetched.
- Permit the supplier registrable domain and subdomains automatically; separate hosts require a supplier allowlist.
- Respect `robots.txt` and fail closed when its policy cannot be established.
- Partial, failed, cancelled, or budget-exhausted runs must never mark products stale.
- A product becomes stale only after absence from two consecutive complete crawl snapshots.
- LLM decisions cannot override host, IP, robots, response-size, concurrency, time, batch, or total-run limits.
- Product-bearing pages are extracted independently; never truncate a combined catalog into one LLM request.
- Every observation records a source URL and extraction method.
- Read `frontend/node_modules/next/dist/docs/` before changing Next.js-specific APIs.

---

## File Structure

Backend units to create:

- `domain/CrawlRun.kt`, `domain/CrawlUrl.kt`, `domain/CrawlProductObservation.kt`: durable crawl state only.
- `repository/CrawlRunRepository.kt`, `repository/CrawlUrlRepository.kt`, `repository/CrawlProductObservationRepository.kt`: persistence queries and transactional frontier claims.
- `service/crawl/CrawlPolicy.kt`: host, scheme, DNS/IP, redirect, and robots decisions.
- `service/crawl/UrlCanonicalizer.kt`: URI resolution and stable normalization.
- `service/crawl/CrawlFetcher.kt`: bounded HTTP fetch and response metadata.
- `service/crawl/PlaywrightRenderer.kt`: shared-browser JavaScript fallback.
- `service/crawl/PageParser.kt`: Jsoup/JSON-LD/link/document parsing.
- `service/crawl/CrawlClassifier.kt`: deterministic classification with bounded LLM fallback.
- `service/crawl/ProductPageExtractor.kt`: page-sized extraction and schema validation.
- `service/crawl/CrawlCoordinator.kt`: run creation, batching, checkpoints, recovery, and cancellation.
- `service/crawl/CrawlReconciler.kt`: observations, identity, completeness, upsert, and two-snapshot staleness.
- `controller/CrawlController.kt`: run status and controls.

Existing `ScrapeService` becomes a compatibility facade and no longer owns crawling internals. `CatalogIngestService` retains upload ingestion and delegates website work to `CrawlCoordinator`.

Frontend units to create:

- `components/CrawlRunPanel.tsx`: progress, completeness, errors, and actions.
- `components/ProductProvenance.tsx`: canonical source and extraction provenance.

---

### Task 1: Durable Crawl Schema and Domain Model

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__adaptive_crawl.sql`
- Create: `backend/src/main/kotlin/com/rfp/domain/CrawlRun.kt`
- Create: `backend/src/main/kotlin/com/rfp/domain/CrawlUrl.kt`
- Create: `backend/src/main/kotlin/com/rfp/domain/CrawlProductObservation.kt`
- Create: `backend/src/main/kotlin/com/rfp/repository/CrawlRunRepository.kt`
- Create: `backend/src/main/kotlin/com/rfp/repository/CrawlUrlRepository.kt`
- Create: `backend/src/main/kotlin/com/rfp/repository/CrawlProductObservationRepository.kt`
- Modify: `backend/src/main/kotlin/com/rfp/domain/Supplier.kt`
- Modify: `backend/src/main/kotlin/com/rfp/domain/Product.kt`
- Test: `backend/src/test/kotlin/com/rfp/repository/CrawlPersistenceTest.kt`

**Interfaces:**
- Produces: `CrawlRunStatus`, `CrawlUrlStatus`, `CrawlPageType`, `CrawlRun`, `CrawlUrl`, `CrawlProductObservation`.
- Produces: `CrawlUrlRepository.claimBatch(runId: Long, limit: Int, now: Instant): List<CrawlUrl>`.
- Produces: supplier fields `crawlAllowedHosts: Array<String>` and crawl-limit overrides.
- Produces: product fields `crawlMissCount: Int`, `canonicalSourceUrl: String?`, `lastObservedAt: Instant?`.

- [ ] **Step 1: Write the failing persistence test**

```kotlin
@DataJpaTest
class CrawlPersistenceTest(@Autowired val runs: CrawlRunRepository,
                           @Autowired val urls: CrawlUrlRepository,
                           @Autowired val suppliers: SupplierRepository) {
    @Test
    fun `frontier URL is unique per run and pending work can be claimed`() {
        val supplier = suppliers.save(Supplier(name = "Large Catalog", officialWebsite = "https://shop.example.com"))
        val run = runs.save(CrawlRun(supplier = supplier, status = CrawlRunStatus.QUEUED,
            configJson = """{"batchPages":100}"""))
        urls.save(CrawlUrl(run = run, originalUrl = "https://shop.example.com/p/1",
            normalizedUrl = "https://shop.example.com/p/1", host = "shop.example.com",
            status = CrawlUrlStatus.PENDING, pageType = CrawlPageType.UNKNOWN, depth = 1, priority = 50))

        val claimed = urls.claimBatch(run.id, 10, Instant.now())

        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().status).isEqualTo(CrawlUrlStatus.CLAIMED)
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `cd backend && mvn test -Dtest=CrawlPersistenceTest`

Expected: compilation fails because the crawl entities and repositories do not exist.

- [ ] **Step 3: Add the migration and domain types**

Create PostgreSQL enum-compatible `varchar` columns with Kotlin enums persisted as strings. The migration must create `crawl_run`, `crawl_url`, and `crawl_product_observation`, unique `(crawl_run_id, normalized_url)`, indexes on `(run_id, status, priority)`, `(run_id, next_attempt_at)`, and `(supplier_id, identity_key)`, plus supplier configuration and product crawl-state columns. Add foreign keys with cascade only from a crawl run to frontier/observations; never cascade from supplier to products.

Use these core declarations:

```kotlin
enum class CrawlRunStatus { QUEUED, DISCOVERING, CRAWLING, EXTRACTING, RECONCILING, COMPLETE, PARTIAL, FAILED, CANCELLED }
enum class CrawlUrlStatus { PENDING, CLAIMED, FETCHED, EXTRACTED, RETRY, FAILED, REJECTED, SKIPPED }
enum class CrawlPageType { UNKNOWN, SITEMAP, FEED, API, CATEGORY, LISTING, PRODUCT, DOCUMENT, OTHER }
```

Implement `claimBatch` as a native transactional `FOR UPDATE SKIP LOCKED` query followed by a claimed-state update, returning highest-priority eligible records first.

- [ ] **Step 4: Run persistence tests and migration validation**

Run: `cd backend && mvn test -Dtest=CrawlPersistenceTest`

Expected: PASS; duplicate normalized URLs in one run raise a constraint violation.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V7__adaptive_crawl.sql backend/src/main/kotlin/com/rfp/domain backend/src/main/kotlin/com/rfp/repository backend/src/test/kotlin/com/rfp/repository/CrawlPersistenceTest.kt
git commit -m "feat: add durable crawl persistence model"
```

### Task 2: URL Canonicalization and Network Safety Policy

**Files:**
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/UrlCanonicalizer.kt`
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/CrawlPolicy.kt`
- Create: `backend/src/test/kotlin/com/rfp/service/crawl/UrlCanonicalizerTest.kt`
- Create: `backend/src/test/kotlin/com/rfp/service/crawl/CrawlPolicyTest.kt`

**Interfaces:**
- Produces: `UrlCanonicalizer.resolveAndNormalize(pageUrl: URI, reference: String): URI?`.
- Produces: `CrawlPolicy.validate(uri: URI, supplierRoot: URI, explicitHosts: Set<String>): PolicyDecision`.
- Produces: `PolicyDecision.Allowed(resolved: List<InetAddress>)` or `PolicyDecision.Rejected(reason: PolicyRejection)`.

- [ ] **Step 1: Write failing URI-resolution tests**

```kotlin
@Test
fun `resolves nested relative path against current page`() {
    val result = canonicalizer.resolveAndNormalize(
        URI("https://example.com/catalog/meters/index.html"), "../manuals/m1.pdf#page=2")
    assertThat(result.toString()).isEqualTo("https://example.com/catalog/manuals/m1.pdf")
}

@Test
fun `removes tracking parameters but preserves functional query`() {
    val result = canonicalizer.resolveAndNormalize(
        URI("https://example.com/"), "/products?page=2&utm_source=email")
    assertThat(result.toString()).isEqualTo("https://example.com/products?page=2")
}
```

- [ ] **Step 2: Run and verify RED**

Run: `cd backend && mvn test -Dtest=UrlCanonicalizerTest`

Expected: compilation fails because `UrlCanonicalizer` does not exist.

- [ ] **Step 3: Implement canonicalization with `URI.resolve`**

Reject blank, malformed, non-HTTP(S), user-info, and fragment-only references. Lowercase scheme/host, remove default ports and fragments, normalize dot segments, preserve meaningful query order, and remove configured tracking keys (`utm_*`, `gclid`, `fbclid`).

- [ ] **Step 4: Write failing SSRF and host-policy tests**

```kotlin
@Test
fun `allows subdomain and explicit separate documentation host`() {
    resolver.answers["docs.vendor.net"] = listOf(InetAddress.getByName("203.0.113.20"))
    assertThat(policy.validate(URI("https://docs.vendor.net/m.pdf"),
        URI("https://shop.example.com"), setOf("docs.vendor.net"))).isInstanceOf(PolicyDecision.Allowed::class.java)
}

@Test
fun `rejects redirect destination resolving to metadata address`() {
    resolver.answers["redirect.example.com"] = listOf(InetAddress.getByName("169.254.169.254"))
    assertThat(policy.validate(URI("https://redirect.example.com/latest/meta-data"),
        URI("https://example.com"), emptySet())).isEqualTo(
            PolicyDecision.Rejected(PolicyRejection.NON_PUBLIC_ADDRESS))
}
```

- [ ] **Step 5: Implement deterministic policy checks**

Inject a `DnsResolver` interface so tests never use live DNS. Validate all A/AAAA results and reject if any selected destination is non-public. Use a public-suffix-aware registrable-domain helper rather than a naive last-two-label comparison. Require exact or subdomain match for automatic hosts; exact normalized match for explicit hosts.

- [ ] **Step 6: Run both suites and commit**

Run: `cd backend && mvn test -Dtest=UrlCanonicalizerTest,CrawlPolicyTest`

Expected: PASS.

```bash
git add backend/src/main/kotlin/com/rfp/service/crawl backend/src/test/kotlin/com/rfp/service/crawl
git commit -m "feat: enforce crawl URL and network safety"
```

### Task 3: Robots Rules and Bounded Fetching

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/RobotsPolicyService.kt`
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/CrawlFetcher.kt`
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/PlaywrightRenderer.kt`
- Test: `backend/src/test/kotlin/com/rfp/service/crawl/RobotsPolicyServiceTest.kt`
- Test: `backend/src/test/kotlin/com/rfp/service/crawl/CrawlFetcherTest.kt`

**Interfaces:**
- Produces: `RobotsPolicyService.canFetch(uri: URI, userAgent: String): RobotsDecision`.
- Produces: `CrawlFetcher.fetch(request: CrawlFetchRequest): FetchResult`.
- Produces: `FetchResult(url, status, contentType, body, etag, lastModified, method, contentHash)`.
- Consumes: `CrawlPolicy.validate` before every request and redirect.

- [ ] **Step 1: Add Jsoup and robots-parser dependencies and failing tests**

Use Jsoup `1.18.3` and a Java 21-compatible robots parser with explicit version in `pom.xml`. Tests use `MockWebServer` and assert disallowed paths are never requested, oversized bodies fail with `RESPONSE_TOO_LARGE`, redirects are revalidated, and `304` responses reuse stored content metadata.

```kotlin
@Test
fun `does not fetch a robots-disallowed product page`() {
    robots.stub("User-agent: *\nDisallow: /private/")
    val result = fetcher.fetch(request("/private/product-1"))
    assertThat(result).isEqualTo(FetchResult.Rejected(FetchError.ROBOTS_DISALLOWED))
    assertThat(server.requestCount).isEqualTo(1) // robots.txt only
}
```

- [ ] **Step 2: Run and verify RED**

Run: `cd backend && mvn test -Dtest=RobotsPolicyServiceTest,CrawlFetcherTest`

Expected: compilation fails for missing services.

- [ ] **Step 3: Implement robots caching and bounded HTTP fetching**

Cache robots rules by origin with expiry. Fail closed when rules cannot be obtained after bounded retries. Disable OkHttp automatic redirects; follow manually only after resolving and validating each `Location`. Stream response bodies and stop at the configured byte ceiling. Enforce accepted HTML, XML, JSON, text, and supported document MIME types.

- [ ] **Step 4: Implement shared Playwright renderer**

Create one browser per application worker and a fresh context per render. Add `@PreDestroy` cleanup. Render only when HTTP content is below the meaningful-content threshold or parser signals JavaScript dependency. Wait for DOM stabilization with a hard maximum; do not rely exclusively on `NETWORKIDLE`.

- [ ] **Step 5: Verify and commit**

Run: `cd backend && mvn test -Dtest=RobotsPolicyServiceTest,CrawlFetcherTest`

Expected: PASS with no real network access.

```bash
git add backend/pom.xml backend/src/main/kotlin/com/rfp/service/crawl backend/src/test/kotlin/com/rfp/service/crawl
git commit -m "feat: add robots-aware bounded crawl fetching"
```

### Task 4: Structured Page Parsing and Discovery

**Files:**
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/PageParser.kt`
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/SitemapParser.kt`
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/CatalogDocumentParser.kt`
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/DiscoveryModels.kt`
- Test: `backend/src/test/kotlin/com/rfp/service/crawl/PageParserTest.kt`
- Test: `backend/src/test/kotlin/com/rfp/service/crawl/SitemapParserTest.kt`
- Test: `backend/src/test/kotlin/com/rfp/service/crawl/CatalogDocumentParserTest.kt`
- Test fixtures: `backend/src/test/resources/crawl/`

**Interfaces:**
- Produces: `PageParser.parse(fetch: FetchResult.Success): ParsedPage`.
- Produces: `ParsedPage(title, canonicalUrl, visibleText, links, jsonLdProducts, embeddedJson, pagination, documents, signals)`.
- Produces: `SitemapParser.parse(xml: String): SitemapResult.Index | SitemapResult.Urls`.
- Produces: `CatalogDocumentParser.parse(bytes: ByteArray, contentType: String, sourceUrl: URI): ParsedDocument`.

- [ ] **Step 1: Create realistic fixtures and failing parser tests**

Fixtures must include nested anchor markup, Arabic product text, `rel=canonical`, JSON-LD Product/Offer, pagination, a PDF datasheet, an embedded JSON state object, and a sitemap index referencing multiple child sitemaps. The PDF fixture must contain two product records and one manual URL so document parsing has an observable contract.

```kotlin
@Test
fun `extracts structured product manual pagination and nested anchor label`() {
    val page = parser.parse(successFixture("product-page.html"))
    assertThat(page.jsonLdProducts.single().mpn).isEqualTo("DMM-1000")
    assertThat(page.documents.single().uri.path).endsWith("DMM-1000-manual.pdf")
    assertThat(page.pagination.map { it.uri.toString() }).contains("https://example.com/products?page=2")
    assertThat(page.links).anySatisfy { assertThat(it.label).isEqualTo("Digital Multimeters") }
}
```

- [ ] **Step 2: Run and verify RED**

Run: `cd backend && mvn test -Dtest=PageParserTest,SitemapParserTest,CatalogDocumentParserTest`

Expected: compilation fails because parsing types do not exist.

- [ ] **Step 3: Implement parsing without regex HTML traversal**

Use Jsoup selectors and Jackson for structured JSON. Resolve every discovered reference through `UrlCanonicalizer`. Parse XML with external entities and DTD processing disabled. Limit embedded JSON depth/size. Use PDFBox for bounded PDF text extraction and return page-number provenance with discovered links; reject encrypted, oversized, or page-limit-exceeding documents explicitly. Return data only; do not enqueue or persist from parsers.

- [ ] **Step 4: Verify and commit**

Run: `cd backend && mvn test -Dtest=PageParserTest,SitemapParserTest,CatalogDocumentParserTest`

Expected: PASS for English and Arabic fixtures.

```bash
git add backend/src/main/kotlin/com/rfp/service/crawl backend/src/test/kotlin/com/rfp/service/crawl backend/src/test/resources/crawl
git commit -m "feat: parse structured catalog pages and sitemaps"
```

### Task 5: Page Classification and Page-Sized Product Extraction

**Files:**
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/CrawlClassifier.kt`
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/ProductPageExtractor.kt`
- Create: `backend/src/main/kotlin/com/rfp/dto/CrawlLlmDtos.kt`
- Modify: `backend/src/main/kotlin/com/rfp/service/LlmService.kt`
- Test: `backend/src/test/kotlin/com/rfp/service/crawl/CrawlClassifierTest.kt`
- Test: `backend/src/test/kotlin/com/rfp/service/crawl/ProductPageExtractorTest.kt`

**Interfaces:**
- Produces: `CrawlClassifier.classify(page: ParsedPage): PageClassification`.
- Produces: `ProductPageExtractor.extract(page: ParsedPage, classes: List<ClassSchema>): List<ExtractedObservation>`.
- Produces: `PageClassification(type, priority, shouldCrawl, partitionKey, confidence)`.
- Produces: `ExtractedObservation(identityHint, name, mpn, className, attributes, price, currency, sourceUrl, method, confidence, fieldSources)`.

- [ ] **Step 1: Write failing deterministic and LLM-fallback tests**

```kotlin
@Test
fun `JSON-LD product is extracted without an LLM call`() {
    val observations = extractor.extract(parsedJsonLdProduct(), emptyList())
    assertThat(observations.single().mpn).isEqualTo("DMM-1000")
    verify(exactly = 0) { llmClient.call(any(), any()) }
}

@Test
fun `malformed LLM batch retries once with individual product blocks`() {
    every { llmClient.call(any(), any()) } returnsMany listOf("{truncated", validSingleProductJson)
    val observations = extractor.extract(ambiguousListingWithOneProduct(), emptyList())
    assertThat(observations).hasSize(1)
}
```

- [ ] **Step 2: Run and verify RED**

Run: `cd backend && mvn test -Dtest=CrawlClassifierTest,ProductPageExtractorTest`

Expected: compilation fails for missing classifier and extractor.

- [ ] **Step 3: Implement deterministic-first classification and extraction**

Use URL patterns, sitemap metadata, JSON-LD types, pagination signals, product identifiers, and product density before calling the LLM. Give the LLM bounded candidate data and require versioned JSON. Validate required fields, absolute safe source URLs, numeric prices, currency codes, attributes, description, and manual link. Retry malformed batches by reducing batch size; record a terminal extraction error after the configured attempts.

- [ ] **Step 4: Verify and commit**

Run: `cd backend && mvn test -Dtest=CrawlClassifierTest,ProductPageExtractorTest,LlmServiceTest`

Expected: PASS.

```bash
git add backend/src/main/kotlin/com/rfp/dto/CrawlLlmDtos.kt backend/src/main/kotlin/com/rfp/service/LlmService.kt backend/src/main/kotlin/com/rfp/service/crawl backend/src/test/kotlin/com/rfp/service
git commit -m "feat: classify and extract product pages safely"
```

### Task 6: Durable Crawl Coordinator and Resumable Frontier

**Files:**
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/CrawlCoordinator.kt`
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/CrawlRunConfig.kt`
- Create: `backend/src/main/kotlin/com/rfp/job/CrawlBatchJob.kt`
- Modify: `backend/src/main/kotlin/com/rfp/config/AsyncConfig.kt`
- Test: `backend/src/test/kotlin/com/rfp/service/crawl/CrawlCoordinatorTest.kt`

**Interfaces:**
- Produces: `CrawlCoordinator.enqueue(supplierId: Long, overrides: CrawlRunOverrides? = null): Long`.
- Produces: `CrawlCoordinator.processBatch(runId: Long): BatchOutcome`.
- Produces: `CrawlCoordinator.resume(runId: Long)`, `cancel(runId: Long)`, `retryFailed(runId: Long)`.
- Consumes all Tasks 1–5 interfaces.

- [ ] **Step 1: Write failing run-state and recovery tests**

```kotlin
@Test
fun `budget exhaustion checkpoints pending URLs and schedules another batch`() {
    val runId = coordinator.enqueue(supplier.id, CrawlRunOverrides(batchPages = 2))
    val outcome = coordinator.processBatch(runId)
    assertThat(outcome).isEqualTo(BatchOutcome.MORE_WORK)
    assertThat(urlRepo.countPending(runId)).isGreaterThan(0)
    assertThat(runRepo.getReferenceById(runId).status).isEqualTo(CrawlRunStatus.CRAWLING)
}

@Test
fun `expired claims return to pending after worker restart`() {
    seedExpiredClaim(run)
    coordinator.recoverAbandonedClaims(run.id, now)
    assertThat(urlRepo.getReferenceById(url.id).status).isEqualTo(CrawlUrlStatus.PENDING)
}
```

- [ ] **Step 2: Run and verify RED**

Run: `cd backend && mvn test -Dtest=CrawlCoordinatorTest`

Expected: compilation fails for missing coordinator.

- [ ] **Step 3: Implement transactional batch state machine**

Snapshot configuration when enqueuing. Seed `/robots.txt`, `/sitemap.xml`, and the homepage after policy validation. Claim bounded work, update heartbeat, fetch/parse/classify, enqueue newly discovered URLs idempotently, store observations, retry transient errors with exponential backoff and jitter, and release expired claims. Check cancellation before each claim and between expensive stages.

Default configuration is code-enforced and externally configurable: 100 pages and 20 documents per batch, two concurrent requests, two-second delay per host, 15-minute batch duration, 50,000 total URLs, 24-hour total wall-clock ceiling, and three fetch attempts. These are ceilings, not LLM inputs.

- [ ] **Step 4: Verify multi-batch completion and cancellation**

Run: `cd backend && mvn test -Dtest=CrawlCoordinatorTest`

Expected: PASS; cancellation claims no new URLs, pending state survives a new coordinator instance, and batches never exceed configured limits.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/rfp/service/crawl backend/src/main/kotlin/com/rfp/job/CrawlBatchJob.kt backend/src/main/kotlin/com/rfp/config/AsyncConfig.kt backend/src/test/kotlin/com/rfp/service/crawl/CrawlCoordinatorTest.kt
git commit -m "feat: orchestrate resumable crawl batches"
```

### Task 7: Observation Identity, Merge, Completeness, and Reconciliation

**Files:**
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/ProductIdentityService.kt`
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/ObservationMerger.kt`
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/CrawlCompletenessService.kt`
- Create: `backend/src/main/kotlin/com/rfp/service/crawl/CrawlReconciler.kt`
- Test: `backend/src/test/kotlin/com/rfp/service/crawl/ProductIdentityServiceTest.kt`
- Test: `backend/src/test/kotlin/com/rfp/service/crawl/CrawlReconcilerTest.kt`

**Interfaces:**
- Produces: `ProductIdentityService.identity(supplierId: Long, observation: ExtractedObservation): String`.
- Produces: `ObservationMerger.merge(observations: List<CrawlProductObservation>): MergedProduct`.
- Produces: `CrawlCompletenessService.evaluate(runId: Long): CompletenessResult`.
- Produces: `CrawlReconciler.reconcile(runId: Long): ReconciliationResult`.

- [ ] **Step 1: Write failing identity and merge tests**

Assert normalized MPN wins over name/source fingerprint, same MPN casing merges, missing MPN uses normalized name/class/canonical URL, collisions remain separate and flagged, product-page values beat listing values, and a newer low-confidence value does not overwrite an older high-confidence value without explicit precedence.

- [ ] **Step 2: Run and verify RED**

Run: `cd backend && mvn test -Dtest=ProductIdentityServiceTest,CrawlReconcilerTest`

Expected: compilation fails for missing services.

- [ ] **Step 3: Implement identity and deterministic merge**

Create identity keys as `mpn:<normalized>` or `fallback:<sha256(normalizedName|className|canonicalUrl)>`. Merge by extraction-method rank (`JSON_LD`, `API`, `PRODUCT_PAGE_LLM`, `LISTING_LLM`), then confidence, then observation time. Preserve per-field provenance from the winning observation.

- [ ] **Step 4: Add failing completeness and two-snapshot stale tests**

```kotlin
@Test
fun `partial run cannot increment product miss count`() {
    seedProduct(missCount = 1)
    seedRun(status = CrawlRunStatus.PARTIAL)
    reconciler.reconcile(run.id)
    assertThat(productRepo.getReferenceById(product.id).crawlMissCount).isEqualTo(1)
    assertThat(productRepo.getReferenceById(product.id).isStale).isFalse()
}

@Test
fun `second consecutive complete miss marks product stale`() {
    seedProduct(missCount = 1)
    seedCompleteRunWithoutObservation()
    reconciler.reconcile(run.id)
    assertThat(productRepo.getReferenceById(product.id).crawlMissCount).isEqualTo(2)
    assertThat(productRepo.getReferenceById(product.id).isStale).isTrue()
}
```

- [ ] **Step 5: Implement completeness and reconciliation transaction**

Complete only when required partitions have no pending/retry/failed records, no limit was exhausted, and all product pages reached extracted/skipped state. Upsert merged observations with source/provenance using the existing product-class resolution, unit normalization, product-price, and price-history repositories. Only complete runs change miss counters. Reappearance clears the count and crawler-controlled stale state.

- [ ] **Step 6: Verify and commit**

Run: `cd backend && mvn test -Dtest=ProductIdentityServiceTest,CrawlReconcilerTest`

Expected: PASS.

```bash
git add backend/src/main/kotlin/com/rfp/service/crawl backend/src/test/kotlin/com/rfp/service/crawl
git commit -m "feat: reconcile complete crawl snapshots safely"
```

### Task 8: Compatibility Facade, Scheduling, and Feature-Flagged Rollout

**Files:**
- Modify: `backend/src/main/kotlin/com/rfp/service/ScrapeService.kt`
- Modify: `backend/src/main/kotlin/com/rfp/service/CatalogIngestService.kt`
- Modify: `backend/src/main/kotlin/com/rfp/job/CatalogRefreshJob.kt`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/kotlin/com/rfp/service/ScrapeServiceTest.kt`
- Test: `backend/src/test/kotlin/com/rfp/job/CatalogRefreshJobTest.kt`

**Interfaces:**
- Preserves: `ScrapeService.runScrapeJobAsync(supplierId: Long)` as a facade calling `CrawlCoordinator.enqueue`.
- Removes website crawling from `CatalogIngestService.ingestScrape`; uploads still call `runIngest`.
- Adds configuration `rfp.scraper.adaptive-enabled`, initially `false` outside tests/local explicit configuration.

- [ ] **Step 1: Replace method-existence test with failing behavior tests**

```kotlin
@Test
fun `scrape facade enqueues exactly one durable crawl`() {
    every { coordinator.enqueue(7L, null) } returns 41L
    service.runScrapeJobAsync(7L)
    verify(exactly = 1) { coordinator.enqueue(7L, null) }
}
```

Test scheduled refresh ignores suppliers with active nonterminal runs and enqueues eligible stale suppliers exactly once.

- [ ] **Step 2: Run and verify RED**

Run: `cd backend && mvn test -Dtest=ScrapeServiceTest,CatalogRefreshJobTest`

Expected: existing `ScrapeService` performs the old crawl and tests fail.

- [ ] **Step 3: Implement facade and rollout flag**

Keep the old crawler in a clearly named `LegacyScrapeService` only while the flag exists. Adaptive mode enqueues a run and returns immediately. Scheduler uses repository queries for active runs rather than in-memory checks. Do not duplicate `@Async` layers around coordinator enqueueing.

- [ ] **Step 4: Verify and commit**

Run: `cd backend && mvn test -Dtest=ScrapeServiceTest,CatalogRefreshJobTest,CatalogIngestServiceTest`

Expected: PASS and upload ingestion behavior remains unchanged.

```bash
git add backend/src/main/kotlin/com/rfp/service backend/src/main/kotlin/com/rfp/job backend/src/main/resources/application.yml backend/src/test/kotlin/com/rfp
git commit -m "feat: route supplier scraping through adaptive crawler"
```

### Task 9: Crawl API, Progress, Controls, and Metrics

**Files:**
- Create: `backend/src/main/kotlin/com/rfp/controller/CrawlController.kt`
- Create: `backend/src/main/kotlin/com/rfp/dto/CrawlDtos.kt`
- Modify: `backend/src/main/kotlin/com/rfp/controller/SupplierController.kt`
- Modify: `backend/src/main/kotlin/com/rfp/controller/AdminController.kt`
- Modify: `backend/src/main/kotlin/com/rfp/config/SecurityConfig.kt`
- Test: `backend/src/test/kotlin/com/rfp/controller/CrawlControllerTest.kt`

**Interfaces:**
- `POST /suppliers/{supplierId}/catalog/scrape` returns `{runId,status}`.
- `GET /suppliers/{supplierId}/crawl-runs` returns summaries.
- `GET /crawl-runs/{runId}` returns counters, completeness, configuration, and errors.
- `POST /crawl-runs/{runId}/resume`, `/cancel`, and `/retry-failed` perform idempotent controls.
- Product admin DTO adds `canonicalSourceUrl`, `lastObservedAt`, and provenance.
- Supplier request/response DTOs add `crawlAllowedHosts` and nullable crawl-limit overrides; validation rejects IP literals, URLs, ports, and hosts outside normalized DNS-host syntax.

- [ ] **Step 1: Write failing controller contract tests**

Test authenticated authorization, supplier/run ownership, status serialization, partial completeness reason, structured rejected URLs, idempotent cancel, invalid transition `409`, and backwards-compatible scrape enqueue response.

```kotlin
mvc.perform(get("/crawl-runs/41").with(jwtAdmin()))
    .andExpect(status().isOk)
    .andExpect(jsonPath("$.status").value("CRAWLING"))
    .andExpect(jsonPath("$.counts.discovered").value(5300))
    .andExpect(jsonPath("$.completeness.canReconcile").value(false))
```

- [ ] **Step 2: Run and verify RED**

Run: `cd backend && mvn test -Dtest=CrawlControllerTest`

Expected: route-not-found failures.

- [ ] **Step 3: Implement DTO mapping and state-safe controls**

Controllers expose stored state only and delegate transitions to `CrawlCoordinator`. Return `404` for unknown run, `409` for invalid terminal transitions, and `202` for accepted asynchronous actions. Add Micrometer counters/timers for fetch outcome, retries, Playwright fallback, extraction yield, duplicate observations, and run completion status; do not put supplier names or URLs in metric tags.

- [ ] **Step 4: Verify and commit**

Run: `cd backend && mvn test -Dtest=CrawlControllerTest,SupplierControllerTest,AdminControllerTest`

Expected: PASS.

```bash
git add backend/src/main/kotlin/com/rfp/controller backend/src/main/kotlin/com/rfp/dto backend/src/main/kotlin/com/rfp/config/SecurityConfig.kt backend/src/test/kotlin/com/rfp/controller
git commit -m "feat: expose crawl progress and controls"
```

### Task 10: Supplier Crawl Operations and Product Provenance UI

**Files:**
- Modify: `frontend/src/lib/types.ts`
- Modify: `frontend/src/lib/api.ts`
- Create: `frontend/src/components/CrawlRunPanel.tsx`
- Create: `frontend/src/components/ProductProvenance.tsx`
- Modify: `frontend/src/app/admin/suppliers/[id]/page.tsx`
- Modify: `frontend/src/components/ProductAttributePanel.tsx`
- Test: `frontend/src/__tests__/CrawlRunPanel.test.tsx`
- Test: `frontend/src/__tests__/ProductAttributePanel.test.tsx`

**Interfaces:**
- Produces TypeScript `CrawlRunSummary`, `CrawlRunDetail`, `CrawlCounts`, `Completeness`, and `CrawlError` matching backend DTOs.
- API methods: `listCrawlRuns`, `getCrawlRun`, `resumeCrawl`, `cancelCrawl`, `retryFailedCrawlPages`.
- Supplier edit API sends `crawlAllowedHosts` and optional per-supplier batch/throttle/total-run overrides.

- [ ] **Step 1: Read relevant Next.js 16 documentation**

Read the installed documentation for App Router client components and route parameter handling under `frontend/node_modules/next/dist/docs/`. Record no code changes in this step.

- [ ] **Step 2: Write failing progress-panel tests**

```tsx
test('warns that a partial crawl cannot stale products', () => {
  render(<CrawlRunPanel run={partialRun} onAction={jest.fn()} />);
  expect(screen.getByText(/partial crawl cannot mark products stale/i)).toBeInTheDocument();
  expect(screen.getByText('5,300 discovered')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /resume/i })).toBeEnabled();
});

test('shows source and extraction method in product details', () => {
  render(<ProductAttributePanel attributesJson="{}" provenance={provenance} />);
  expect(screen.getByRole('link', { name: /source/i })).toHaveAttribute('href', provenance.canonicalSourceUrl);
  expect(screen.getByText('JSON-LD')).toBeInTheDocument();
});
```

- [ ] **Step 3: Run and verify RED**

Run: `cd frontend && npm test -- --runInBand src/__tests__/CrawlRunPanel.test.tsx src/__tests__/ProductAttributePanel.test.tsx`

Expected: compilation fails for missing types/components/props.

- [ ] **Step 4: Implement typed API and accessible UI**

Poll active runs through the existing polling pattern, stopping on terminal states. Render phase, counts, completeness reason, batch/heartbeat, limits, structured errors, and rejected URLs. Add a supplier crawl-settings section for separate allowed hosts and optional limit overrides, with explanatory text that root-domain subdomains are automatic. Confirm cancel and start-fresh actions in the UI. Use actual buttons for expandable rows and controls, keyboard-visible focus, `aria-expanded`, and descriptive status text. Permit only validated HTTP(S) source/manual links.

- [ ] **Step 5: Verify frontend**

Run: `cd frontend && npm test -- --runInBand`

Run: `cd frontend && npx tsc --noEmit`

Run: `cd frontend && npx eslint src/components/CrawlRunPanel.tsx src/components/ProductProvenance.tsx src/components/ProductAttributePanel.tsx src/app/admin/suppliers/[id]/page.tsx src/lib/api.ts src/lib/types.ts`

Expected: all commands exit 0.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib frontend/src/components frontend/src/app/admin/suppliers frontend/src/__tests__
git commit -m "feat: add crawl operations and provenance UI"
```

### Task 11: End-to-End, Restart, and 5,000-Product Validation

**Files:**
- Create: `backend/src/test/kotlin/com/rfp/service/crawl/AdaptiveCrawlerIntegrationTest.kt`
- Create: `backend/src/test/kotlin/com/rfp/service/crawl/LargeCatalogIntegrationTest.kt`
- Create: `backend/src/test/kotlin/com/rfp/service/crawl/CrawlSecurityIntegrationTest.kt`
- Create: `backend/src/test/resources/crawl/generate-large-catalog.js`
- Modify: `README.md`
- Modify: `backend/src/main/resources/application.yml`
- Remove after validation: legacy crawling internals from `backend/src/main/kotlin/com/rfp/service/LegacyScrapeService.kt`

**Interfaces:**
- Exercises public enqueue/status/control interfaces and real repositories.
- Uses local `MockWebServer` fixtures only; no public internet dependency.

- [ ] **Step 1: Write failing end-to-end tests**

Create a generated sitemap index with six partitions and 5,200 products, paginated listings, product JSON-LD, duplicate canonical URLs, 50 manuals, one transient `503`, and one JavaScript marker fixture. Assert:

```kotlin
assertThat(run.status).isEqualTo(CrawlRunStatus.COMPLETE)
assertThat(observationRepo.countByRunId(run.id)).isEqualTo(5200)
assertThat(productRepo.findBySupplierId(supplier.id)).hasSize(5200)
assertThat(fetchAudit.maxConcurrentForHost).isLessThanOrEqualTo(2)
assertThat(fetchAudit.requestedPrivateAddresses).isEmpty()
```

Add a restart test that processes two batches, constructs a new coordinator, recovers expired claims, and completes without duplicate observations. Add a partial-partition test proving zero miss-count changes.

- [ ] **Step 2: Run and verify RED**

Run: `cd backend && mvn test -Dtest=AdaptiveCrawlerIntegrationTest,LargeCatalogIntegrationTest,CrawlSecurityIntegrationTest`

Expected: at least one acceptance assertion fails until all production integration is connected.

- [ ] **Step 3: Connect missing integration seams only**

Fix wiring, transactions, batch scheduling, configuration binding, and deterministic test clocks revealed by the end-to-end tests. Do not weaken acceptance assertions or increase limits to hide loops.

- [ ] **Step 4: Run complete verification**

Run: `cd backend && mvn test`

Run: `cd backend && mvn clean package -DskipTests`

Run: `cd frontend && npm test -- --runInBand`

Run: `cd frontend && npx tsc --noEmit`

Run: `cd frontend && npm run lint`

Expected: all tests and builds pass. If the Next.js build requires network font access, run it in the approved network-enabled environment and record that result separately from offline TypeScript verification.

- [ ] **Step 5: Enable adaptive mode and remove legacy crawler**

After shadow-mode comparison confirms equivalent or better product yield for selected suppliers, set `rfp.scraper.adaptive-enabled: true`. Delete the old crawl implementation while retaining the `ScrapeService` compatibility facade. Update README configuration, operational controls, stale policy, allowlist behavior, and recovery instructions.

- [ ] **Step 6: Commit**

```bash
git add backend frontend README.md
git commit -m "feat: complete adaptive resumable catalog crawler"
```

---

## Review Checkpoints

Review after Tasks 1–3 for schema and network-security correctness before enabling any fetch. Review after Tasks 4–7 for extraction, completion, and reconciliation correctness before connecting production endpoints. Review after Tasks 8–10 for backward compatibility and operator usability. Task 11 is the release gate; adaptive mode must remain disabled if any large-catalog, restart, SSRF, or stale-product assertion fails.
