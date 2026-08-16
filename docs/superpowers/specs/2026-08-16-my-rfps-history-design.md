# My RFP Submissions History — Design Spec

## Goal

Give each authenticated user a dedicated page listing all their past RFP submissions, with status visibility, direct access to reports and proposals, the ability to re-run matching with a revised supplier list, and the ability to delete submissions.

## Architecture

A new frontend route `/my-rfps` backed by three backend changes: a list endpoint, a delete endpoint, and persisted supplier IDs on the tender so re-runs know which suppliers to default to.

```
/my-rfps  →  GET /rfp          (list user's tenders)
              DELETE /rfp/{id}  (delete tender + cascade)
/rfp/{id}  →  unchanged (report + proposal + product swap)
POST /rfp/{id}/match  →  now accepts optional { supplierIds } body
```

## Tech Stack

Spring Boot + Kotlin backend; Next.js + TypeScript + Tailwind CSS v4 frontend; Flyway migration for schema change; existing `SupplierCombobox` component reused for re-run supplier selection.

## Global Constraints

- User-scoped data only: `GET /rfp` and `DELETE /rfp/{id}` enforce `tender.userId == authenticatedUserId`; a mismatch returns 403.
- No pagination: load all tenders for the user in one request (reasonable volume per user).
- No soft delete: `DELETE /rfp/{id}` hard-deletes tender + cascade (lines, match_result, proposals, proposal_line).
- Supplier IDs stored as JSON text (`supplier_ids TEXT`), not a join table.
- Re-run matching clears old match results and proposals before starting a fresh pass.
- Status polling on the card stops when status reaches `done` or `failed`.

---

## Section 1 — Page Structure and Navigation

**Route:** `frontend/src/app/my-rfps/page.tsx`

**Nav link:** Home page nav bar gains "My RFPs →" alongside the existing "Admin →" link. The link is always visible to authenticated users.

**Page layout:**
- Heading "My RFP Submissions" + a "New RFP →" button (links back to `/`).
- Vertical list of `SubmissionCard` components, newest first.
- Empty state: "No submissions yet. Upload your first RFP →"
- Loading state: skeleton placeholder.
- Error state: inline error message.

---

## Section 2 — Submission Cards

**Component:** `frontend/src/components/SubmissionCard.tsx`

Each card displays:

| Field | Source |
|---|---|
| Filename | `tender.filename` |
| Status badge | `tender.status`: `uploading` (slate) → `extracting` (amber) → `matching` (blue) → `done` (green) / `failed` (red) |
| Created date | `tender.createdAt` formatted as locale date-time |
| Line count | `tender.lineCount` (e.g. "12 lines") |
| Proposal count | `tender.proposalCount` (e.g. "3 proposals") |

**Actions on each card:**

1. **View Report →** — navigates to `/rfp/{id}`. Always visible; disabled while status is `uploading` or `extracting` (no match results exist yet); enabled for `matching`, `done`, and `failed`.

2. **Re-run Matching** — visible on all non-deleted cards; disabled while status is `matching` or `extracting`. Clicking expands an inline panel below the card containing:
   - A `SupplierCombobox` pre-populated with the tender's current `supplierIds`.
   - A "Run" button: calls `POST /rfp/{id}/match` with the selected supplier IDs, then collapses the panel and starts polling the card's status.
   - A "Cancel" link that collapses the panel without submitting.
   - While the new match runs, the card status badge updates live (poll every 3 s, stop on `done`/`failed`).

3. **Delete** — shows a `window.confirm("Delete this submission and all its results?")` guard, then calls `DELETE /rfp/{id}`, removes the card from the list on success.

**Status polling:** The `SubmissionCard` polls `GET /rfp/{id}/report` every 3 s while status is `uploading`, `extracting`, or `matching`, and stops when it reaches `done` or `failed`.

---

## Section 3 — Backend Changes

### 3a. Flyway migration — persist supplier IDs on tender

**File:** `V<N>__tender_supplier_ids.sql`

```sql
ALTER TABLE tender ADD COLUMN supplier_ids TEXT;
```

Existing rows get `NULL` (handled gracefully by defaulting to an empty list on re-run).

### 3b. Update `POST /rfp/upload` — save supplier IDs

`RfpController.upload` already receives `supplierIds: List<Long>`. After saving the `Tender`, persist `supplierIds` as a JSON string on the entity:

```kotlin
tender.copy(supplierIds = objectMapper.writeValueAsString(supplierIds))
```

### 3c. New endpoint — `GET /rfp`

```
GET /rfp
Authorization: Bearer <token>
→ 200 List<TenderSummaryDto>
```

Returns all tenders where `userId == authenticatedUserId`, ordered by `createdAt DESC`.

```kotlin
data class TenderSummaryDto(
    val id: Long,
    val filename: String,
    val status: String,
    val createdAt: Instant,
    val lineCount: Int,
    val proposalCount: Int,
    val supplierIds: List<Long>,
)
```

`lineCount` = count of `tender_line` rows for this tender.  
`proposalCount` = count of `proposal` rows for this tender.  
`supplierIds` = deserialized from `tender.supplierIds`; empty list if null.

### 3d. New endpoint — `DELETE /rfp/{id}`

```
DELETE /rfp/{id}
Authorization: Bearer <token>
→ 204 No Content
→ 403 if tender.userId != authenticatedUserId
→ 404 if not found
```

Deletes in order: `proposal_line` → `proposal` → `match_result` → `tender_line` → `tender`.

### 3e. Update `POST /rfp/{id}/match` — accept optional supplierIds

```
POST /rfp/{id}/match
Content-Type: application/json   (optional body)
{ "supplierIds": [1, 2, 3] }    (omit to use stored list)
```

Behaviour:
- If body is present and non-empty, overwrite `tender.supplierIds` with the new list, then re-run matching.
- If body is absent or empty, use the stored `tender.supplierIds`.
- Before re-running, delete existing `match_result` rows and `proposal` rows for this tender (so the new pass starts clean).
- Reset `tender.status` to `"matching"`.

---

## Frontend API Methods

Add to `api.ts`:

```ts
// List the authenticated user's tenders
myRfps: {
  list: (token: string) => Promise<TenderSummary[]>
  delete: (rfpId: number, token: string) => Promise<void>
}
```

`TenderSummary` type added to `types.ts`:

```ts
export interface TenderSummary {
  id: number;
  filename: string;
  status: 'uploading' | 'extracting' | 'matching' | 'done' | 'failed';
  createdAt: string;
  lineCount: number;
  proposalCount: number;
  supplierIds: number[];
}
```

The existing `api.rfp.match` is updated to accept an optional `supplierIds` body parameter.

---

## Error Handling

| Scenario | Frontend behaviour |
|---|---|
| `GET /rfp` fails | Show "Could not load submissions" inline error |
| `DELETE /rfp/{id}` fails | Toast/inline error on the card; card remains |
| Re-run `POST /rfp/{id}/match` fails | Inline error in the supplier panel |
| Status stays `failed` after polling | Badge turns red; "View Report" shows the error from the report endpoint |

---

## Testing

**Backend:**
- `@WebMvcTest(RfpController::class)` — test `GET /rfp` returns only authenticated user's tenders (not other users'); test `DELETE` returns 403 for wrong user; test `POST /{id}/match` with supplierIds body overwrites stored list.
- Flyway migration applies cleanly in tests (H2 mode).

**Frontend:**
- `SubmissionCard.test.tsx` — renders filename, status badge, line/proposal counts; Re-run panel expands on click; Delete calls api and removes card; polling stops on terminal status.
- `MyRfpsPage.test.tsx` — empty state; list of cards; error state.
