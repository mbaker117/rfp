# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

---

## Project Summary

Jordan-focused web platform where users upload instrument requirement documents (PDF/Word/Excel). The system extracts required instruments via an LLM, matches them against a per-company instrument database (auto-populated via web scraping), and generates a scored matching report with manual links and prices.

---

## Commands

### Backend (Spring Boot + Kotlin, Java 21)

```bash
# Run locally (requires PostgreSQL at localhost:5432)
cd backend && ./mvnw spring-boot:run

# Build JAR
cd backend && ./mvnw clean package -DskipTests

# Run all tests
cd backend && ./mvnw test

# Run a single test class
cd backend && ./mvnw test -Dtest=LlmServiceTest

# Run a single test method
cd backend && ./mvnw test -Dtest=LlmServiceTest#extractRequirementsParses
```

### Frontend (Next.js + TypeScript + Tailwind)

```bash
# Dev server (http://localhost:3000)
cd frontend && npm run dev

# Type-check + build
cd frontend && npm run build

# Lint
cd frontend && npm run lint

# Run all tests
cd frontend && npm test

# Run a single test file
cd frontend && npm test -- src/__tests__/ReportTable.test.tsx
```

### Full stack (Docker Compose)

```bash
# Start everything (postgres + backend + frontend)
docker compose up --build

# Required env vars (add to .env or export):
# RFP_LLM_API_KEY=<anthropic or openai key>
# JWT_SECRET=<at least 32 chars>
# RFP_LLM_PROVIDER=anthropic   # or openai
# RFP_LLM_MODEL=Codex-sonnet-4-6
```

---

## Architecture

### Request Flow

```
Browser → Next.js (3000) → Spring Boot (8080) → PostgreSQL (5432)
                                               → LLM API (Anthropic/OpenAI)
                                               → Playwright (headless Chromium)
```

Long-running operations (scraping, matching) are `@Async` background jobs. The frontend polls job status via `GET /jobs/{id}`.

### Backend Package Layout

```
com.rfp
├── controller/       REST endpoints (Auth, Company, RFP, Job, Admin)
├── service/
│   ├── LlmClient.kt              interface
│   ├── AnthropicLlmClient.kt     active when rfp.llm.provider=anthropic
│   ├── OpenAiLlmClient.kt        active when rfp.llm.provider=openai
│   ├── LlmService.kt             orchestrates 3 LLM tasks (extract, structure, score)
│   ├── ScrapeService.kt          Playwright crawler + persist pipeline
│   ├── MatchingService.kt        @Async job — keyword retrieval + LLM rerank
│   ├── ReportService.kt          PDF (PDFBox) and XLSX (Apache POI) export
│   ├── DocumentParsingService.kt PDF/Word/Excel → raw text
│   └── CompanyResolutionService.kt resolve company by name, enqueue scrape if missing
├── domain/           JPA entities (Company, Instrument, InstrumentPriceHistory,
│                     RfpRequest, RequiredInstrument, ScrapeJob)
├── repository/       Spring Data JPA repos; findCandidates() does keyword search
├── security/         JwtFilter + JwtUtil (stateless JWT, no sessions)
├── job/              InstrumentRefreshJob (@Scheduled daily, ShedLock guarded)
├── config/           AsyncConfig (taskExecutor), SecurityConfig (CORS, JWT chain)
└── dto/              LlmDtos.kt (ExtractedRequirement, ScrapedInstrument, MatchResult)
```

### Frontend Layout

```
src/
├── app/
│   ├── page.tsx           home — company selector + file upload flow
│   └── rfp/[id]/page.tsx  report viewer for a completed RFP
├── components/
│   ├── CompanySelector.tsx
│   ├── FileUpload.tsx
│   ├── JobStatusPoller.tsx  polls GET /jobs/{id} until done/failed
│   └── ReportTable.tsx
└── lib/
    ├── api.ts     typed fetch wrappers (resolveCompanies, uploadRfp, triggerMatch, getReport)
    └── types.ts   shared TypeScript types
```

### LLM Integration

`LlmService` has three tasks, each forcing JSON-only output:
1. **extractRequirements** — document text → `[{rawText, name, quantity, specs}]`
2. **structureScrapeData** — raw HTML (truncated to 12 000 chars) → `[{description, normalizedName, manualLink, price, currency}]`
3. **scoreMatch** — requirement vs up to 10 candidates → `{matchedInstrumentId, score, reason, status}`

Results are cached in a `ConcurrentHashMap` keyed by `sha256(systemPrompt|userMessage)`. Swap the LLM provider via `rfp.llm.provider` in `application.yml` or the `RFP_LLM_PROVIDER` env var.

### Scraping Pipeline

`ScrapeService` uses Playwright (headless Chromium) to crawl a company's `officialWebsite`, passes raw HTML to the LLM for structuring, then diffs against existing DB records: price changes → `instrument_price_history`, removed products → `is_stale = true`. Self-injection via `@Lazy` is required to preserve the Spring proxy so `@Async` works correctly.

### Auth

Stateless JWT. `POST /auth/register` + `POST /auth/login` return a bearer token. The in-memory user store in `AuthController` is a stub — replace with a `users` table and `UserRepository` for production. `/admin/**` requires `ROLE_ADMIN` in the JWT claims.

### Database Migrations

Flyway (`db/migration/`). `ddl-auto: validate` — Flyway owns all schema changes. Add new migrations as `V{N}__description.sql`.

---

## Key Constraints

- **Next.js version** — the frontend uses a version with breaking API changes vs standard docs. Always read `node_modules/next/dist/docs/` before writing Next.js-specific code (see `frontend/AGENTS.md`).
- **Arabic content** — documents and scraped pages may be Arabic. The PDF export loads `NotoSansArabic-Regular.ttf` from `/fonts/` with Helvetica as fallback. LLM prompts explicitly ask for bilingual handling.
- **`@Async` self-call trap** — calling an `@Async` method from within the same bean bypasses the Spring proxy. `ScrapeService` works around this by self-injecting `lateinit var self: ScrapeService` via `@Lazy` and routing the async call through `self.runScrapeJobAsync(...)`.
- **Matching score thresholds** — MATCHED ≥ 80, PARTIAL 40–79, NOT\_FOUND < 40 (defined in `LlmService.scoreMatch` prompt, mirrored in `MatchStatus` enum).
- **CORS** — backend allows `http://localhost:3000` only. Update `SecurityConfig.corsConfigurationSource()` for production origins.
