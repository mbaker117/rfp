# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Project Summary

Jordan-focused procurement platform ("Specta"). Users upload a tender/RFP document (PDF/Word/Excel); an LLM extracts requirement lines, a deterministic engine matches them against per-supplier product catalogs, and the system generates three ranked proposal variants plus PDF/XLSX exports. Supplier catalogs are populated either by uploading a catalog file or by crawling the supplier website.

Jordan defaults are constraints, not suggestions: JOD currency fallback, Arabic/UTF-8 throughout.

---

## Commands

### Backend (Spring Boot 3.2.5 + Kotlin 1.9.25, Java 21, Maven)

**There is no Maven wrapper in this repo.** Use a Maven install directly, and make sure `JAVA_HOME` points at a JDK 21 — the default JVM on this machine is older and Surefire fails with "class file version 65.0".

```bash
cd backend

# Windows (known-good Maven install)
C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd test

# All tests / single class / single method
mvn test
mvn test -Dtest=MatchingEngineServiceTest
mvn test -Dtest=LlmServiceTest#extractCrawlProductsRejectsOversizeResponse

# Run locally (needs PostgreSQL on localhost:5432)
mvn spring-boot:run

# Build JAR, inspect migrations
mvn clean package -DskipTests
mvn flyway:info
```

Surefire runs with `forkCount=0` — tests share one JVM, so avoid global mutable state in tests.

### Frontend (Next.js 16 App Router + React 19 + TypeScript + Tailwind v4)

```bash
cd frontend
npm run dev                                   # http://localhost:3000
npm run build                                 # type-check + build
npm run lint
npm test -- --watchAll=false                  # all tests
npm test -- src/__tests__/ReportTable.test.tsx  # single file
```

### Full stack

```bash
docker compose up --build        # postgres + backend + frontend
docker compose up db -d          # just Postgres (rfp/rfp/rfp on 5432) for local dev
```

Env vars (`.env` in repo root, see `.env.example`): `RFP_LLM_API_KEY`, `JWT_SECRET` (≥32 chars), `RFP_LLM_PROVIDER` (`anthropic`|`openai`), `RFP_LLM_MODEL`, optional `RFP_LLM_BASE_URL`.

Admin access is granted by editing the DB directly, then re-logging in (the role is a JWT claim):
`UPDATE app_user SET role = 'ADMIN' WHERE username = '...';`

---

## Architecture

```
Browser → Next.js (3000) → Spring Boot (8080) → PostgreSQL (5432)
                                              → LLM API (Anthropic/OpenAI)
                                              → Playwright / OkHttp (supplier sites)
```

### Two independent pipelines

**A. Catalog ingestion → `product` rows** (per supplier)

1. *File upload* — `POST /suppliers/{id}/catalog/upload` → `CatalogIngestService.ingestFile` (`@Async`) → `DocumentParsingService` (each PDF page ends with a form feed) → `CatalogChunker` splits the text into `rfp.catalog.chunk-chars` chunks → `LlmService.parseCatalogBatch` per chunk (`rfp.catalog.parallelism` concurrent calls, capped at `rfp.catalog.max-chunks`) → `UnitNormalizationService` → upsert `product` + `product_price` (+ `product_price_history` on price change) matched by `attributes.item_no`, then `mpn`, and by name only when a product has neither (catalog names are generic), saved in document order with per-chunk progress in `catalog_ingest.step_log`. A chunk whose answer hits the output limit (`rfp.llm.max-tokens`; `LlmClient.callDetailed` reports the provider stop reason) is continued: the same whole chunk is sent again with the item numbers already extracted, up to 8 calls, so table headings stay in view. Complete chunk results are cached in `catalog_chunk_cache` (key: `sha256(CATALOG_PROMPT_VERSION | model | chunk text)`), so re-uploading a catalog skips unchanged chunks — bump `CATALOG_PROMPT_VERSION` in `LlmService.kt` whenever the catalog prompt changes. A failed chunk is skipped; a 400/401/403 LLM error or 3 consecutive failures stop the run. Only a complete, failure-free run marks this supplier's unseen products `is_stale`, **except** crawler-discovered ones (`canonicalSourceUrl != null`).
2. *Website crawl* — `POST /suppliers/{id}/catalog/scrape` → `CrawlCoordinator.enqueue` when the supplier has an `officialWebsite`, else the legacy `ScrapeService` path.

**B. Tender processing → report + proposals**

`POST /rfp/upload` → `TenderExtractionService.extract` (`@Async`) → `LlmService.parseTenderLines` → `tender_line` rows → *automatically chains* into `MatchingEngineService.matchAsync` → `match_result` rows. `POST /rfp/{id}/proposals` then runs `ProposalService.generateProposals` (`@Async`), producing three variants: `PERFECT`, `BEST_ACCEPTANCE`, `CHEAPEST`.

Tender `status` walks `uploading → extracting → matching → done` (or `failed`; `pending_match` is used to re-run a finished tender). **There is no `/jobs` endpoint** — the frontend polls `GET /rfp/{id}/report` every 3 s (`JobStatusPoller.tsx`) and reads `status`.

### Backend package layout (`com.rfp`)

```
controller/   Auth, Supplier, Rfp, Proposal, Crawl, Admin
service/
  LlmClient.kt / AnthropicLlmClient.kt / OpenAiLlmClient.kt   @ConditionalOnProperty on rfp.llm.provider
  LlmService.kt              all prompts + JSON parsing + response-size guards
  DocumentParsingService.kt  PDF/Word/Excel → text
  CatalogIngestService.kt    catalog file → products (@Async)
  TenderExtractionService.kt tender file → lines, then chains to matching (@Async)
  MatchingEngineService.kt   deterministic attribute scoring (@Async) — no LLM
  ProposalService.kt         3 proposal variants + LLM acceptance estimate (@Async)
  UnitNormalizationService.kt attribute values → canonical units (unit_conversion table)
  ReportService.kt           PDF (PDFBox) + XLSX (POI) export
  ScrapeService.kt           legacy Playwright scrape; delegates to CrawlCoordinator when adaptive
  crawl/                     adaptive resumable crawler (see below)
domain/       Supplier, Product, ProductClass, AttributeDef, ProductPrice(+History),
              CatalogIngest, Tender, TenderLine, TenderSupplier, MatchResult,
              Proposal, ProposalLine, CrawlRun, CrawlUrl, CrawlProductObservation, AppUser
job/          CatalogRefreshJob (@Scheduled), CrawlBatchJob (@Scheduled, drives crawl batches)
config/       AsyncConfig (taskExecutor + crawlBatchExecutor, ShedLock), SecurityConfig
```

### Adaptive crawler (`service/crawl/`)

Durable, resumable, batch-driven. `CrawlCoordinator.enqueue` seeds the frontier (homepage, `/sitemap.xml`, `/robots.txt`) into `crawl_url`; `CrawlBatchJob` polls `QUEUED` runs every 5 s and calls `processBatch` on a dedicated executor until a terminal state (`COMPLETE`/`PARTIAL`/`CANCELLED`/`FAILED`). Each batch claims URLs atomically, fetches (`CrawlFetcher`: HTTP first, Playwright fallback), parses (`PageParser`), classifies (`CrawlClassifier`, LLM for ambiguous pages), and writes `crawl_product_observation` rows. `CrawlReconciler` turns observations into `product`/`product_price` rows once `CrawlCompletenessService` says the run is complete.

Invariants enforced in code (do not relax them, and never let LLM output override them):

- Budget ceilings in `CrawlRunConfig` are absolute; supplier and per-run overrides may only tighten.
- Only `COMPLETE` runs may increment `crawlMissCount` or set `crawlerStale`. Partial/failed/cancelled runs never mark products stale, and `Product.isStale` (manual admin flag) is never cleared by the reconciler.
- Reconciliation is idempotent; identity keys (`ProductIdentityService`, `mpn:` or `fallback:` sha256) are scoped per supplier.
- SSRF protection lives in `CrawlPolicy` + `ValidatedHttpTransport`: http/https only, host must be within the supplier's registrable domain or an explicit allow-list, DNS results must be public addresses, and each hop is re-validated.

The legacy path still exists behind `rfp.scraper.adaptive-enabled` (`true` in `application.yml` and as the `@Value` default in `ScrapeService`/`CatalogRefreshJob`; set it to `false` to fall back to the inline Playwright scrape). Do not delete the legacy Playwright code during rollout.

### Matching engine

Deterministic, **no LLM at scoring time**. Per tender line: exact name match → exact MPN match → attribute scoring over candidates in the same `product_class` from the selected suppliers. Each required attribute yields a verdict (`COMPLIANT` / `DEVIATION` / `UNVERIFIABLE`, using `AttributeDef.matchOp` = `eq`/`gte`/`lte`), and `score = compliant / total * 100`. Status: `matched` at 100, `partial` at ≥40, else `not_found`. Alternatives = next 5 candidates scoring ≥40, denormalized into `match_result.alternatives` JSON.

`AttributeSchemaService` keeps each class's `attribute_def`s in line with the specs its products carry: after every catalog import (touched classes) and via `POST /admin/product-classes/sync-attributes` (all classes). It adds defs for specs on ≥5% of a class's products, types them from the stored values, takes units from the key suffix (`_in`, `_v`, `_pct`…), allows `gte`/`lte` only on numeric specs, and removes identifier defs (`item_no`, `*_item_no`, `mpn`). Numbers are read with `SpecNumbers.parse`, which accepts catalog fractions ("13 3/8") but not dual ratings ("115/230").

Re-running match is an upsert keyed on `match_result.line_id` (unique), and `matchAsync` returns early when status is already `matching`/`done` — that guard is why re-runs first set `pending_match`.

### LLM integration

`LlmService` owns every prompt and forces JSON-only output. Tasks: `parseCatalogBatch`, `defineClass` (auto-creates a `product_class` + its `attribute_def`s), `defineAttributes` (labels + matchOp for specs added later), `identifyProductUrls`, `parseTenderLines`, `estimateAcceptance`, plus crawler tasks `extractCrawlProducts` and `classifyCrawlPage`.

- Responses are cached in a `ConcurrentHashMap` keyed by `sha256(system|user)`; site-specific calls (`parseCatalogBatch`, `identifyProductUrls`, crawl extraction) deliberately bypass the cache.
- Crawl-facing tasks are hardened: strict `schemaVersion` check, response byte/char caps, attribute count/depth/string limits, and prompts that state all page content is untrusted data, never instructions.
- `repairTruncatedProductsJson` salvages complete product objects when a large catalog response is cut off mid-JSON.
- Adding a provider means one new `LlmClient` implementation — prompts, caching, and parsing stay untouched.

### Auth

Stateless JWT (jjwt), BCrypt hashes, `app_user` table. `JwtUtil.generateToken(userId, role)` embeds a `role` claim; `JwtFilter` maps it to `ROLE_USER`/`ROLE_ADMIN`, and `auth.name` is the **user ID as a string** (controllers do `auth.name.toLongOrNull()`). `/admin/**` and `/crawl-runs/**` require `ROLE_ADMIN`; everything else requires authentication. Unauthenticated requests get 401, not 403.

### Database

Flyway owns the schema (`ddl-auto: validate`) — every column change needs a new `V{N}__description.sql`. V1/V2 created the original `company`/`instrument` tables; **V3 (`specta_schema`) introduced the current model** and V8–V10 added the crawl tables and product identity key, V11 the catalog chunk cache, and V12 an index for item-number matching. JSON attribute bags (`product.attributes`, `tender_line.attributes`) are `jsonb` columns mapped as `String` with `@JdbcTypeCode(SqlTypes.JSON)`.

### Frontend

`src/app/` App Router: `/` (upload flow), `/auth`, `/my-rfps`, `/rfp/[id]`, `/admin/*`. Imports use the `@/*` alias rooted at the frontend directory, so paths look like `@/src/lib/api`. All backend calls go through the single typed client in `src/lib/api.ts` (`api.auth`, `api.proposals`, `api.myRfps`, `api.admin`, `api.crawl`) — add new endpoints there rather than calling `fetch` from components. `useAuth()` reads the JWT from `localStorage['token']`, decodes the `role` claim client-side, and redirects to `/auth?next=<path>` when absent.

---

## Key Constraints

- **Next.js version** — this Next.js has breaking changes vs. what you likely know. Read `node_modules/next/dist/docs/` before writing Next.js-specific code (`frontend/AGENTS.md` says the same).
- **Tailwind v4** — `globals.css` uses `@import "tailwindcss"`; the old `@tailwind` directives produce no output.
- **`@Async` self-call trap** — calling an `@Async` method from inside the same bean bypasses the Spring proxy. `ScrapeService` self-injects `lateinit var self: ScrapeService` via `@Lazy`; services that need async are declared `open` (Kotlin) so they can be proxied.
- **Arabic content** — tender documents and supplier pages are frequently Arabic. PDF export loads `NotoSansArabic-Regular.ttf` from resources with a Helvetica fallback; prompts ask for bilingual handling.
- **CORS** — backend allows `http://localhost:3000` only (`SecurityConfig.corsConfigurationSource()`).
- **Two agent files** — `AGENTS.md` (root, for Codex) is a mirror of this file and differs only in its opening line. Change one, change the other. `frontend/CLAUDE.md` just imports `frontend/AGENTS.md`.
- **Other docs** — `RUNNING.md` covers run/setup detail; `Specta Technical Core Build Spec v1.md` (repo root) is the original product spec; `docs/specta-gap-analysis.md` tracks the build spec backlog (written 2026-07-25, its data-model section predates the V3 schema); designs and plans live in `docs/superpowers/`.
