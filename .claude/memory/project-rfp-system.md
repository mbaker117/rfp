---
name: project-rfp-system
description: "RFP Instrument Matching System — status, tech stack, key decisions, known gaps"
metadata: 
  node_type: memory
  type: project
  originSessionId: 9cee62d9-e564-41c1-8a84-de2804b490e9
---

Jordan-focused web platform: users upload instrument requirement documents (PDF/Word/Excel), an LLM extracts required instruments, matches them against a company-scoped DB populated via web scraping, and generates a scored report with PDF/XLSX export.

**Why:** Internal procurement tool for Jordan market (currency JOD, timezone Asia/Amman, Arabic/UTF-8 required throughout).

**How to apply:** Ground any new work in this context — Jordan defaults are non-negotiable constraints, not suggestions.

## Status (as of 2026-06-27)

All 12 planned tasks complete and merged to `master`. Progress ledger: `.superpowers/sdd/progress.md`. Plan file: `docs/superpowers/plans/2026-06-26-rfp-instrument-matching.md`.

## Tech Stack

| Layer | Choice |
|---|---|
| Backend | Spring Boot + Kotlin, Maven, Java 21 |
| Frontend | Next.js (React/TypeScript), `src/app/` App Router |
| Database | PostgreSQL + Flyway migrations |
| LLM | Anthropic Claude API (provider-switchable — see [[project-llm-provider]]) |
| Scraping | Playwright (headless browser) |
| Scheduling | Spring `@Scheduled` + ShedLock |
| Auth | JWT (in-memory stub — not production-ready) |

## Known Remaining Gaps

- **Price-diff history**: `persistScrapedInstruments` in `ScrapeService` doesn't yet write `instrument_price_history` rows when price changes (schema exists, logic missing)
- **Stale marking**: instruments removed from catalog not marked `is_stale = true` on refresh
- **Real auth**: in-memory user store loses users on restart; no token revocation
- **`matchStatus` null**: blank-keyword instruments get `matchStatus = null` instead of `NOT_FOUND`
- **robots.txt**: scraper doesn't check `robots.txt` before crawling

## Key File Locations

- Backend: `backend/src/main/kotlin/com/rfp/`
- Frontend: `frontend/src/app/` and `frontend/src/components/`
- DB migrations: `backend/src/main/resources/db/migration/`
- Config: `backend/src/main/resources/application.yml`
