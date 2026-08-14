# Proposal Intelligence Design

**Date:** 2026-08-14  
**Status:** Approved  
**Goal:** Increase proposal acceptance rate by surfacing LLM-estimated acceptance probabilities, generating three proposal variants per tender, and letting customers override product selections interactively.

---

## 1. Problem Statement

The current system produces one match result per tender line (best technical score). There is no notion of a "proposal" as a composed document, no acceptance probability, and no way for a customer to interactively swap products before exporting. The goal of this project is to give customers the tools to maximise the chance a submitted proposal is accepted.

---

## 2. Core Concepts

### Acceptance Probability (per line)
An LLM-estimated probability (0–100) that a procurement reviewer would accept the offered product as fulfilling the requirement. The LLM considers:
- Technical compliance (COMPLIANT / DEVIATION / UNVERIFIABLE per attribute)
- Price competitiveness (if price data is available)
- Brand reputation and market acceptance (LLM general knowledge)
- Whether the product is a reasonable substitution for the requirement as written

Geography-agnostic — the LLM uses global procurement context.

### Proposal Acceptance Rate (proposal level)
`mean(all line acceptance probabilities)`. If any line has probability = 0 (product not found or rejected), the proposal is flagged `isComplete = false` — a zero on any line is a dealbreaker.

### Match Score (per line)
The existing technical compliance score (0–100): percentage of required attributes that are COMPLIANT. Distinct from acceptance probability — a technically perfect match may have a lower acceptance rate than a well-known brand with a minor deviation.

Both metrics are shown together throughout the UI.

---

## 3. Three Proposal Variants

| Variant | Selection rule per line |
|---|---|
| **PERFECT** | Product with highest technical match score (current best match) |
| **BEST_ACCEPTANCE** | Product with highest LLM-estimated acceptance probability among the best match + stored alternatives (up to 6 candidates total) |
| **CHEAPEST** | Among products with score > 0, pick lowest price; fall back to highest score if price data is unavailable |

All three variants compute and display both match score and acceptance rate. The difference is only in which product is selected per line.

---

## 4. Data Model

### New table: `proposal`

```sql
CREATE TABLE proposal (
    id               BIGSERIAL PRIMARY KEY,
    tender_id        BIGINT NOT NULL REFERENCES tender(id),
    variant          VARCHAR(32) NOT NULL,  -- PERFECT | BEST_ACCEPTANCE | CHEAPEST
    acceptance_rate  NUMERIC(5,2),          -- null while generating
    match_score      NUMERIC(5,2),          -- avg of per-line match scores, null while generating
    is_complete      BOOLEAN NOT NULL DEFAULT FALSE,
    status           VARCHAR(32) NOT NULL DEFAULT 'GENERATING', -- GENERATING | READY | FAILED
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### New table: `proposal_line`

```sql
CREATE TABLE proposal_line (
    id                      BIGSERIAL PRIMARY KEY,
    proposal_id             BIGINT NOT NULL REFERENCES proposal(id) ON DELETE CASCADE,
    line_id                 BIGINT NOT NULL REFERENCES tender_line(id),
    selected_product_id     BIGINT REFERENCES product(id),  -- null = not_found
    match_score             NUMERIC(5,2),     -- score of selected product vs requirement
    acceptance_probability  NUMERIC(5,2),     -- null while computing
    llm_reasoning           TEXT,
    is_overridden           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (proposal_id, line_id)
);
```

### Enriched `match_result.alternatives` JSON

Extend the stored alternatives to include full product detail (no schema change — extend the JSONB payload):

```json
{
  "productId": 42,
  "name": "...",
  "mpn": "...",
  "score": 73,
  "attributeVerdicts": [...],
  "price": 149.50,
  "currency": "JOD",
  "description": "...",
  "manualLink": "https://...",
  "supplierName": "..."
}
```

This enrichment happens at match time in `MatchingEngineService` by joining `ProductPrice` and reading `attributes` JSON. No additional DB fetches at read time.

---

## 5. Backend Services

### 5a. `MatchingEngineService` — enrich alternatives

When building the `alternatives` JSON list for a `MatchResult`, additionally look up:
- `ProductPrice` for each alternative product (left join — price may be null)
- `attributes` JSON for `description` and `manualLink` keys
- `supplier.name`

Store the enriched JSON. Existing stored results are not backfilled — enrichment applies on next re-match.

### 5b. `LlmService.estimateAcceptance()`

New method. Single LLM call, result cached by `sha256(system|user)`.

**System prompt:**
```
You are a procurement analyst. Estimate the probability (0–100) that a 
procurement reviewer would accept the offered product as fulfilling the 
stated requirement. Consider: technical compliance (attribute verdicts), 
price competitiveness, brand reputation and market acceptance, and whether 
this is a reasonable substitution. Return ONLY valid JSON:
{"probability": <integer 0-100>, "reasoning": "<one sentence>"}
```

**User message:**
```
Requirement: {line.description}
Required attributes: {lineAttrs as key:value pairs}
Offered product: {product.name} ({product.mpn})
Offered attributes: {productAttrs as key:value pairs}
Attribute verdicts: {list of "attr: COMPLIANT/DEVIATION/UNVERIFIABLE"}
Price: {price + currency, or "not available"}
```

Returns: `AcceptanceEstimate(probability: Int, reasoning: String)`

### 5c. `ProposalService` (new `@Service`, `@Async`)

#### `generateProposals(tenderId: Long)`

Triggered by `POST /rfp/{id}/proposals`. Idempotent — deletes and recreates all 3 proposals if called again.

Steps:
1. Load all `TenderLine`s and their `MatchResult`s (with enriched alternatives)
2. For each variant, select one product per line:
   - **PERFECT**: use `matchResult.product` (already the best-score product)
   - **BEST_ACCEPTANCE**: call `estimateAcceptance` for the best match + all stored alternatives (up to 6 candidates per line), pick highest probability
   - **CHEAPEST**: among candidates with `score > 0`, sort by price ascending; null-price products go last
3. Save `Proposal` rows (status = GENERATING) and `ProposalLine` rows
4. For each line in PERFECT and CHEAPEST: call `estimateAcceptance` (parallel via `taskExecutor`)
5. Update `proposal_line.acceptance_probability` and `llm_reasoning`
6. Compute `proposal.acceptance_rate = mean(all line probabilities)` and `proposal.match_score = mean(all line match scores)`
7. Set `proposal.is_complete = !lines.any { it.acceptance_probability == 0.0 || it.selected_product_id == null }`
8. Set `proposal.status = READY`

#### `overrideLine(proposalId: Long, lineId: Long, newProductId: Long)`

1. Update `proposal_line`: set `selected_product_id = newProductId`, `is_overridden = true`, clear `acceptance_probability` and `llm_reasoning`
2. Async: call `estimateAcceptance` for the new product vs the line requirement
3. Update `proposal_line` with new probability and reasoning
4. Recompute `proposal.acceptance_rate` and `proposal.match_score` from all stored line values
5. Update `proposal.updated_at`

---

## 6. API Endpoints

New `ProposalController` at `/rfp/{rfpId}/proposals`:

| Method | Path | Description |
|---|---|---|
| `POST` | `/rfp/{id}/proposals` | Trigger generation of all 3 variants. Returns `{status: "generating"}` |
| `GET` | `/rfp/{id}/proposals` | Returns all 3 proposals with lines, selected products, scores, acceptance rates |
| `PATCH` | `/rfp/{id}/proposals/{proposalId}/lines/{lineId}` | Override line product. Body: `{"productId": Long}` |
| `GET` | `/rfp/{id}/proposals/{proposalId}/lines/{lineId}/search` | Live product search within tender suppliers scoped to the line's product class. Params: `q` (name/MPN). Runs `scoreCandidate` against line requirements. Returns up to 10 results with match score + price + supplier. |

### GET response shape

```json
[{
  "id": 1,
  "variant": "PERFECT",
  "status": "READY",
  "acceptanceRate": 89.2,
  "matchScore": 94.0,
  "isComplete": true,
  "lines": [{
    "lineId": 5,
    "description": "...",
    "qty": 2,
    "matchScore": 100.0,
    "selectedProduct": {
      "id": 42, "name": "...", "mpn": "...",
      "price": 149.50, "currency": "JOD", "supplierName": "...",
      "description": "...", "manualLink": "..."
    },
    "acceptanceProbability": 91.0,
    "llmReasoning": "Strong technical compliance and competitive price.",
    "isOverridden": false,
    "alternatives": [{
      "productId": 44, "name": "...", "mpn": "...",
      "score": 68, "price": 120.00, "currency": "JOD",
      "description": "...", "supplierName": "..."
    }]
  }]
}]
```

---

## 7. Frontend

### 7a. New TypeScript types (`types.ts`)

```ts
interface SelectedProduct {
  id: number; name: string; mpn: string | null;
  price: number | null; currency: string; supplierName: string;
  description: string | null; manualLink: string | null;
}

interface ProposalAlternative {
  productId: number; name: string; mpn: string | null;
  score: number; price: number | null; currency: string;
  description: string | null; supplierName: string;
}

interface ProposalLine {
  lineId: number; description: string; qty: number | null;
  matchScore: number; selectedProduct: SelectedProduct | null;
  acceptanceProbability: number | null; llmReasoning: string | null;
  isOverridden: boolean; alternatives: ProposalAlternative[];
}

interface Proposal {
  id: number; variant: 'PERFECT' | 'BEST_ACCEPTANCE' | 'CHEAPEST';
  status: 'GENERATING' | 'READY' | 'FAILED';
  acceptanceRate: number | null; matchScore: number | null;
  isComplete: boolean; lines: ProposalLine[];
}
```

### 7b. Report page flow

1. When `report.status === 'done'` and no proposals exist: show **"Generate Proposals"** button
2. After clicking: polls `GET /rfp/{id}/proposals` every 3s until all 3 are `READY`
3. Once ready: show `ProposalPanel` above the existing `ReportTable`

### 7c. `ProposalPanel` — 3 variant cards

```
┌──────────────────────┐ ┌──────────────────────┐ ┌──────────────────────┐
│    Perfect Match     │ │   Best Acceptance    │ │      Cheapest        │
│  Match score:  94%  │ │  Match score:  76%  │ │  Match score:  61%  │
│  Acceptance:   89%  │ │  Acceptance:   94%  │ │  Acceptance:   71%  │
│  ✓ Complete          │ │  ✓ Complete          │ │  ⚠ 2 gaps            │
│  [View & Edit]       │ │  [View & Edit]       │ │  [View & Edit]       │
└──────────────────────┘ └──────────────────────┘ └──────────────────────┘
```

While `status === 'GENERATING'`: show spinner cards with "Computing…"

### 7d. `ProposalDetail` — interactive line table

Opened from "View & Edit". Columns per line:
- Requirement description + qty
- Selected product (name, MPN, supplier, price)
- Match score bar (existing `ScoreBar` component)
- Acceptance probability bar (color: green ≥ 70, amber 40–69, red < 40)
- LLM reasoning (shown on row expand)
- **"Change"** button

**Change flow:**
1. Inline panel expands below the row
2. Shows pre-computed alternatives as selectable cards (score, acceptance probability if available, price)
3. Search box for live search (`GET /rfp/{id}/proposals/{proposalId}/search?q=...`)
4. Clicking a product calls `PATCH` override; that line shows a spinner until `acceptanceProbability` is non-null (polls proposal)
5. `ProposalPanel` acceptance rate and match score update live as lines are overridden

### 7e. Export

Existing Export Excel / Export PDF buttons gain a variant selector:
- "Export match report" (existing behaviour)
- "Export Perfect Match proposal"
- "Export Best Acceptance proposal"  
- "Export Cheapest proposal"

The proposal export passes `?proposalId={id}` as a query param to the existing `/rfp/{id}/report/export?format=xlsx&proposalId={id}` endpoint. `ReportService` checks for `proposalId`: if present, uses `proposal_line.selected_product_id` per line instead of `match_result.product_id`.

---

## 8. Out of Scope

- Historical win-rate tracking (no past proposal outcomes stored yet)
- Multi-supplier proposal mixing (all lines stay within the supplier set chosen at upload)
- Proposal sharing / collaboration (single-user per session)
- Automatic re-generation when match results change

---

## 9. Migration

New Flyway migration `V7__proposals.sql` (or `V8` if adaptive crawler V7 lands first) containing the `proposal` and `proposal_line` table DDL from Section 4.
