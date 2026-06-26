# RFP Instrument Matching System — Full Plan

A web platform (Jordan-focused) where users upload instrument requirement documents (PDF/Word/Excel). The system extracts required instruments via an LLM, matches them against a company-scoped instrument database (populating it via web scraping when data is missing), and generates a matching report with scores, manual links, and prices. A scheduled job keeps the database fresh.

---

## 1. System Overview

Core capabilities:

- Upload PDF / Word / Excel describing required instruments.
- Extract structured requirements from the document using an LLM.
- Maintain an instrument database scoped per company.
- Populate the database via web scraping of company official websites when data does not already exist.
- Match required instruments to database instruments and produce a matching score out of 100.
- Generate a report highlighting matched and not-found instruments.
- Refresh the database on a schedule (cronjob) to keep prices, manuals, and catalogs current.

---

## 2. Architecture

```
┌─────────────┐     REST/JSON      ┌──────────────────┐
│  Frontend   │ ◄────────────────► │     Backend      │
│  (Node.js)  │                    │ (Spring + Kotlin)│
└─────────────┘                    └────────┬─────────┘
                                            │
                  ┌─────────────────────────┼──────────────────────┐
                  │                          │                      │
            ┌─────▼─────┐            ┌───────▼────────┐      ┌───────▼──────┐
            │ PostgreSQL│            │  LLM Service   │      │  Scraper     │
            │    DB     │            │ (Claude API)   │      │  Service     │
            └───────────┘            └────────────────┘      └──────────────┘
                  ▲
                  │
            ┌─────┴───────────────┐
            │  Scheduled Refresh  │
            │  (Cronjob / @Scheduled) │
            └─────────────────────┘
```

Web scraping and LLM calls are slow, so long-running tasks run as background jobs with status polling. The backend exposes a REST API; the frontend polls job status until completion.

---

## 3. Tech Stack


| Layer                    | Choice                                          | Notes                                                    |
| ------------------------ | ----------------------------------------------- | -------------------------------------------------------- |
| Frontend                 | Node.js + Next.js (React)                       | SSR, file upload UI, report viewer                       |
| Backend                  | Spring Boot + Kotlin                            | REST API, orchestration, scheduling                      |
| Database                 | PostgreSQL                                      | relational data + JSONB for raw scraped payloads         |
| Async jobs               | Spring `@Async` + job table (or RabbitMQ)       | track scrape / extract / match jobs                      |
| File parsing             | Apache POI (Excel/Word), PDFBox (PDF)           | server-side extraction                                   |
| LLM                      | Anthropic Claude API                            | requirement extraction, scraping cleanup, matching score |
| Scraping                 | Playwright (JVM) or a Node scraper microservice | headless browser for JS-heavy sites                      |
| Vector search (optional) | pgvector                                        | semantic instrument matching                             |
| Scheduling lock          | ShedLock                                        | ensures the cronjob runs once across instances           |
| Auth                     | JWT / Spring Security                           | per-user RFP sessions                                    |


---

## 4. Database Schema

```sql
company (
  id, name, official_website, country DEFAULT 'Jordan',
  scrape_status, last_scraped_at, created_at
)

instrument (
  id, company_id FK,
  description TEXT,
  normalized_name TEXT,        -- LLM-cleaned canonical name
  manual_link TEXT,
  price NUMERIC NULL,
  currency VARCHAR DEFAULT 'JOD',
  raw_data JSONB,              -- original scraped payload
  embedding VECTOR(1024) NULL, -- optional, for semantic match
  is_stale BOOLEAN DEFAULT FALSE,
  created_at, updated_at
)

instrument_price_history (
  id, instrument_id FK,
  price NUMERIC, currency VARCHAR,
  recorded_at
)

rfp_request (
  id, user_id, original_filename, file_type,
  status,                      -- UPLOADED, EXTRACTING, MATCHING, DONE, FAILED
  created_at
)

required_instrument (
  id, rfp_request_id FK,
  raw_text, extracted_spec JSONB,  -- LLM-parsed requirement
  matched_instrument_id FK NULL,
  matching_score INT,              -- 0–100
  match_status                     -- MATCHED, NOT_FOUND, PARTIAL
)

scrape_job (
  id, company_id FK, status, error_msg, started_at, finished_at
)
```

The required report fields map directly: required instrument (`required_instrument.raw_text` / `extracted_spec`), matched instrument (`matched_instrument_id`), manual link (`instrument.manual_link`), matching score (`required_instrument.matching_score`), and price (`instrument.price`).

---

## 5. Core Workflows

### A. Company / Database Population

1. User submits required companies (names + optional URLs).
2. Backend checks the DB by name/website for each company.
3. **If data exists** → reuse stored instruments.
4. **If missing** → create a `scrape_job`:
  - Resolve official website (search if no URL given).
  - Crawl product/catalog pages with a headless browser.
  - Pass raw HTML/text to the LLM to extract structured records: `{description, manual_link, price, company}`.
  - Normalize names and optionally compute embeddings.
  - Persist to `instrument`.

### B. RFP Upload & Extraction

1. User uploads a PDF/Word/Excel file and selects target companies.
2. Backend extracts raw text (POI / PDFBox).
3. The LLM extracts a structured list of required instruments (name, quantity, specs).
4. Results are stored as `required_instrument` rows.

### C. Matching

For each required instrument:

1. Candidate retrieval from the DB, scoped to selected companies (keyword + optional vector similarity).
2. The LLM (or a hybrid scorer) produces a matching score 0–100, selects the best match, and returns JSON `{matchId, score, reason}`.
3. The matched instrument, score, and status are persisted.

### D. Report Generation

Produce a report containing, per required instrument: required instrument, matched instrument, manual link, matching score out of 100, and price. Not-found items are clearly highlighted. The report is shown as an HTML view and exportable to PDF/Excel.

---

## 6. Scheduled Database Refresh (Cronjob)

Prices, manuals, and catalogs change, so a scheduled job keeps the database fresh.

### What it does

1. Select companies/instruments due for refresh (e.g. `last_scraped_at` older than N days).
2. Re-run the scrape → LLM structuring pipeline.
3. Diff against existing records:
  - **Price changed** → update and log to `instrument_price_history`.
  - **New instrument** → insert.
  - **Manual link dead/changed** → update or flag.
  - **Instrument gone from catalog** → mark `is_stale = true` (do not hard-delete).
4. Update `last_scraped_at` and log job results.

### Spring/Kotlin scheduling

```kotlin
@Component
class InstrumentRefreshJob(
    private val companyRepo: CompanyRepository,
    private val scrapeService: ScrapeService
) {
    // Runs daily at 2 AM Amman time
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Amman")
    @SchedulerLock(name = "instrumentRefreshJob")
    fun refreshStaleCompanies() {
        val cutoff = Instant.now().minus(7, ChronoUnit.DAYS)
        companyRepo.findByLastScrapedAtBefore(cutoff)
            .forEach { scrapeService.enqueueScrapeJob(it.id) }
    }
}
```

Enable with `@EnableScheduling` on a config class.

### Design notes

- **Don't scrape everything at once** — enqueue one job per company onto the async queue and throttle, to avoid rate-limit bans and LLM cost spikes.
- **Stagger refresh windows** so all companies aren't due the same night.
- **Keep price history** so reports can show trends and old prices aren't lost.
- **Use ShedLock** (or a DB lock) for multi-instance deployments, since plain `@Scheduled` fires on every instance.
- **Provide a manual trigger** (`POST /admin/refresh`) to force a refresh on demand.

---

## 7. LLM Integration Points


| Task                   | Purpose                                                                     |
| ---------------------- | --------------------------------------------------------------------------- |
| Requirement extraction | Turn messy document text into a clean instrument list with specs            |
| Scraping cleanup       | Convert raw HTML into structured `{id, description, manual_link, price}`    |
| Matching score         | Compare a requirement vs candidates, output JSON `{matchId, score, reason}` |


Always force JSON-only structured output, validate against a schema, keep candidate lists small to control cost/latency, and cache LLM results keyed by an input hash.

---

## 8. Key API Endpoints

```
POST /companies/resolve            # submit companies, trigger scrape jobs
GET  /companies/{id}/status
POST /rfp/upload                   # upload doc, returns rfpId
POST /rfp/{id}/extract
POST /rfp/{id}/match
GET  /rfp/{id}/report
GET  /rfp/{id}/report/export?format=pdf|xlsx
GET  /jobs/{id}                    # async status polling
POST /admin/refresh                # manual DB refresh trigger
```

---

## 9. Implementation Phases

1. **Foundations** — DB schema, Spring/Kotlin skeleton, Next.js skeleton, auth, file upload + parsing.
2. **LLM extraction** — document → structured requirements.
3. **Scraping pipeline** — company resolution, crawler, LLM structuring, persistence.
4. **Matching engine** — candidate retrieval + scoring (start keyword-only, add pgvector later).
5. **Reporting** — report view + PDF/Excel export with highlighting.
6. **Scheduled refresh** — cronjob, price-diff logic, ShedLock, price history.
7. **Hardening** — async jobs, caching, rate limiting, error handling, monitoring.

---

## 10. Key Considerations

- **Scraping legality / ToS** — confirm each site permits scraping, respect `robots.txt`, and throttle requests. Some Jordan vendor sites may lack a structured catalog; handle these gracefully as NOT_FOUND.
- **Arabic content** — documents and sites may be in Arabic. Ensure UTF-8 throughout and that LLM prompts handle bilingual text.
- **Stale prices** — store `last_scraped_at`, show data age in reports, and allow re-scrape.
- **Matching accuracy** — a hybrid approach (embedding shortlist + LLM rerank) outperforms pure LLM on both cost and quality.
- **Cost control** — cache LLM and scrape outputs and batch requests where possible.

