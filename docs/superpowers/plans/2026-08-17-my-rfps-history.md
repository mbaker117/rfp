# My RFPs History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give authenticated users a `/my-rfps` page listing all their past RFP submissions, with status visibility, report access, re-run matching with optional new suppliers, and delete.

**Architecture:** Four backend repository extensions + two new endpoints + one updated endpoint on `RfpController`; two new frontend types; two new API methods; a new `SubmissionCard` component; and a new `/my-rfps` page with a nav link from home. Supplier IDs are stored in the existing `tender_supplier` join table — no schema migration needed.

**Tech Stack:** Spring Boot 3.2.5 + Kotlin 1.9.25 backend; Spring Data JPA repositories; Next.js + TypeScript + Tailwind CSS v4 frontend; Jest + React Testing Library; MockK + `@WebMvcTest` for backend tests.

## Global Constraints

- User-scoped: `GET /rfp` and `DELETE /rfp/{id}` enforce `tender.userId == authenticatedUserId`; mismatch → 403.
- No pagination: load all user tenders in one call.
- Hard-delete only: `DELETE /rfp/{id}` permanently removes tender + all child rows.
- Supplier IDs live in `tender_supplier` join table — never add a `supplier_ids TEXT` column to tender.
- `auth.principal as? Long` extracts `userId` from JWT (established pattern in `RfpController.upload`).
- Maven wrapper lives at `backend\mvnw`; run as `.\mvnw` from `backend\` on Windows.
- Frontend uses Next.js App Router with `'use client'` on interactive components.
- Frontend import alias is `@/src/...` (not `@/...`).
- `window.confirm` is the established pattern for destructive-action guards (per `AdminController` usage).
- All new backend test classes use `@WebMvcTest(RfpController::class)` extending `RfpControllerTest` patterns (MockK + Spring Security test).
- Delete cascade order: `proposal_line` → `proposal` → `match_result` → `tender_supplier` → `tender_line` → `tender`.

---

### Task 1: Backend repository extensions and new/updated endpoints

**Files:**
- Modify: `backend/src/main/kotlin/com/rfp/repository/TenderRepository.kt`
- Modify: `backend/src/main/kotlin/com/rfp/repository/MatchResultRepository.kt`
- Modify: `backend/src/main/kotlin/com/rfp/repository/TenderLineRepository.kt`
- Modify: `backend/src/main/kotlin/com/rfp/repository/TenderSupplierRepository.kt`
- Modify: `backend/src/main/kotlin/com/rfp/repository/ProposalLineRepository.kt`
- Modify: `backend/src/main/kotlin/com/rfp/controller/RfpController.kt`
- Modify: `backend/src/test/kotlin/com/rfp/controller/RfpControllerTest.kt`

**Interfaces:**
- Produces: `GET /rfp` → `List<TenderSummaryDto>` (JSON array); `DELETE /rfp/{id}` → 204/403/404; `POST /rfp/{id}/match` now accepts optional JSON body `{"supplierIds":[1,2]}`.
- Produces: `TenderSummaryDto` data class (used by frontend in Task 2).

- [ ] **Step 1: Add derived/query methods to repositories**

Edit `TenderRepository.kt`:
```kotlin
package com.rfp.repository

import com.rfp.domain.Tender
import org.springframework.data.jpa.repository.JpaRepository

interface TenderRepository : JpaRepository<Tender, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<Tender>
}
```

Edit `MatchResultRepository.kt` — add delete:
```kotlin
package com.rfp.repository

import com.rfp.domain.MatchResult
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.transaction.annotation.Transactional

interface MatchResultRepository : JpaRepository<MatchResult, Long> {
    fun findByLineId(lineId: Long): MatchResult?
    fun findByLineTenderId(tenderId: Long): List<MatchResult>

    @Transactional
    @Modifying
    fun deleteByLineTenderId(tenderId: Long)
}
```

Edit `TenderLineRepository.kt` — add delete:
```kotlin
package com.rfp.repository

import com.rfp.domain.TenderLine
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.transaction.annotation.Transactional

interface TenderLineRepository : JpaRepository<TenderLine, Long> {
    fun findByTenderId(tenderId: Long): List<TenderLine>

    @Transactional
    @Modifying
    fun deleteByTenderId(tenderId: Long)
}
```

Edit `TenderSupplierRepository.kt` — add delete:
```kotlin
package com.rfp.repository

import com.rfp.domain.TenderSupplier
import com.rfp.domain.TenderSupplierId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

interface TenderSupplierRepository : JpaRepository<TenderSupplier, TenderSupplierId> {
    @Query("SELECT ts.supplier.id FROM TenderSupplier ts WHERE ts.tender.id = :tenderId")
    fun findSupplierIdsByTenderId(tenderId: Long): List<Long>

    @Transactional
    @Modifying
    @Query("DELETE FROM TenderSupplier ts WHERE ts.tender.id = :tenderId")
    fun deleteByTenderId(tenderId: Long)
}
```

Edit `ProposalLineRepository.kt` — add delete:
```kotlin
package com.rfp.repository

import com.rfp.domain.ProposalLine
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.transaction.annotation.Transactional

interface ProposalLineRepository : JpaRepository<ProposalLine, Long> {
    fun findByProposalId(proposalId: Long): List<ProposalLine>
    fun findByProposalIdAndLineId(proposalId: Long, lineId: Long): ProposalLine?

    @Transactional
    @Modifying
    fun deleteByProposalId(proposalId: Long)
}
```

- [ ] **Step 2: Write failing tests for GET /rfp, DELETE /rfp/{id}, and updated POST /rfp/{id}/match**

Add to `RfpControllerTest.kt` (append before the closing `}`):

```kotlin
    // --- New tests for Task 1 ---

    @MockkBean lateinit var tenderSupplierRepo: TenderSupplierRepository
    @MockkBean lateinit var supplierRepo: SupplierRepository

    @Test
    @WithMockUser(username = "1")
    fun `GET rfp returns list of user tenders`() {
        every { tenderRepo.findByUserIdOrderByCreatedAtDesc(1L) } returns listOf(tender)
        every { tenderLineRepo.findByTenderId(1L) } returns emptyList()
        every { proposalRepo.findByTenderId(1L) } returns emptyList()
        every { tenderSupplierRepo.findSupplierIdsByTenderId(1L) } returns listOf(42L)

        mvc.perform(get("/rfp"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].filename").value("test.pdf"))
            .andExpect(jsonPath("$[0].supplierIds[0]").value(42))
            .andExpect(jsonPath("$[0].lineCount").value(0))
            .andExpect(jsonPath("$[0].proposalCount").value(0))
    }

    @Test
    @WithMockUser(username = "2")
    fun `GET rfp returns empty list for user with no tenders`() {
        every { tenderRepo.findByUserIdOrderByCreatedAtDesc(2L) } returns emptyList()

        mvc.perform(get("/rfp"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$").isEmpty)
    }

    @Test
    @WithMockUser(username = "1")
    fun `DELETE rfp returns 204 for owner`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { proposalRepo.findByTenderId(1L) } returns emptyList()
        every { proposalLineRepo.deleteByProposalId(any()) } just Runs
        every { proposalRepo.deleteByTenderId(1L) } just Runs
        every { matchResultRepo.deleteByLineTenderId(1L) } just Runs
        every { tenderSupplierRepo.deleteByTenderId(1L) } just Runs
        every { tenderLineRepo.deleteByTenderId(1L) } just Runs
        every { tenderRepo.deleteById(1L) } just Runs

        mvc.perform(delete("/rfp/1").with(csrf()))
            .andExpect(status().isNoContent)
    }

    @Test
    @WithMockUser(username = "99")
    fun `DELETE rfp returns 403 for non-owner`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)

        mvc.perform(delete("/rfp/1").with(csrf()))
            .andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(username = "1")
    fun `POST match with supplierIds body updates suppliers and returns jobId`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { tenderSupplierRepo.deleteByTenderId(1L) } just Runs
        every { tenderSupplierRepo.save(any()) } answers { firstArg() }
        every { supplierRepo.getReferenceById(any()) } returns mockk(relaxed = true)
        every { proposalRepo.findByTenderId(1L) } returns emptyList()
        every { proposalLineRepo.deleteByProposalId(any()) } just Runs
        every { proposalRepo.deleteByTenderId(1L) } just Runs
        every { matchResultRepo.deleteByLineTenderId(1L) } just Runs
        every { tenderRepo.save(any()) } answers { firstArg() }
        every { matchingService.matchAsync(1L) } just Runs

        mvc.perform(
            post("/rfp/1/match").with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""{"supplierIds":[5,6]}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value("rfp-1-match"))
    }
```

Also add imports at the top of the test file:
```kotlin
import com.rfp.repository.TenderSupplierRepository
import com.rfp.repository.SupplierRepository
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import io.mockk.mockk
```

- [ ] **Step 3: Run tests to verify they fail**

```
cd backend && .\mvnw test -Dtest=RfpControllerTest
```
Expected: failures on the 3 new test methods (endpoint not found / not mapped).

- [ ] **Step 4: Implement the updated RfpController**

Replace `RfpController.kt` with:

```kotlin
package com.rfp.controller

import com.rfp.domain.Tender
import com.rfp.domain.TenderSupplier
import com.rfp.domain.TenderSupplierId
import com.rfp.repository.*
import com.rfp.service.MatchingEngineService
import com.rfp.service.ReportService
import com.rfp.service.TenderExtractionService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.time.Instant

data class TenderSummaryDto(
    val id: Long,
    val filename: String,
    val status: String,
    val createdAt: Instant,
    val lineCount: Int,
    val proposalCount: Int,
    val supplierIds: List<Long>
)

data class MatchRequestBody(val supplierIds: List<Long>? = null)

@RestController
@RequestMapping("/rfp")
class RfpController(
    private val tenderRepo: TenderRepository,
    private val tenderLineRepo: TenderLineRepository,
    private val matchResultRepo: MatchResultRepository,
    private val extractionService: TenderExtractionService,
    private val matchingService: MatchingEngineService,
    private val reportService: ReportService,
    private val proposalRepo: ProposalRepository,
    private val proposalLineRepo: ProposalLineRepository,
    private val tenderSupplierRepo: TenderSupplierRepository,
    private val supplierRepo: SupplierRepository
) {
    @PostMapping("/upload", consumes = ["multipart/form-data"])
    fun upload(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("supplierIds", required = false, defaultValue = "") supplierIds: List<Long>,
        auth: Authentication
    ): ResponseEntity<Map<String, Any>> {
        val userId = auth.principal as? Long ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val ext = file.originalFilename?.substringAfterLast('.', "")?.lowercase() ?: ""
        if (ext !in setOf("pdf", "docx", "doc", "xlsx", "xls"))
            return ResponseEntity.badRequest().body(mapOf("error" to "Unsupported file type"))

        val tender = tenderRepo.save(Tender(userId = userId, filename = file.originalFilename ?: "upload", fileType = ext))
        extractionService.extract(tender.id, file.bytes, ext, supplierIds)
        return ResponseEntity.ok(mapOf("rfpId" to tender.id))
    }

    @GetMapping
    fun list(auth: Authentication): ResponseEntity<List<TenderSummaryDto>> {
        val userId = auth.principal as? Long ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val tenders = tenderRepo.findByUserIdOrderByCreatedAtDesc(userId)
        val summaries = tenders.map { t ->
            TenderSummaryDto(
                id = t.id,
                filename = t.filename,
                status = t.status,
                createdAt = t.createdAt,
                lineCount = tenderLineRepo.findByTenderId(t.id).size,
                proposalCount = proposalRepo.findByTenderId(t.id).size,
                supplierIds = tenderSupplierRepo.findSupplierIdsByTenderId(t.id)
            )
        }
        return ResponseEntity.ok(summaries)
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long, auth: Authentication): ResponseEntity<Void> {
        val userId = auth.principal as? Long ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val tender = tenderRepo.findById(id).orElseThrow { NoSuchElementException("Tender $id not found") }
        if (tender.userId != userId) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()

        proposalRepo.findByTenderId(id).forEach { proposalLineRepo.deleteByProposalId(it.id) }
        proposalRepo.deleteByTenderId(id)
        matchResultRepo.deleteByLineTenderId(id)
        tenderSupplierRepo.deleteByTenderId(id)
        tenderLineRepo.deleteByTenderId(id)
        tenderRepo.deleteById(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/match")
    fun match(
        @PathVariable id: Long,
        @RequestBody(required = false) body: MatchRequestBody?,
        auth: Authentication
    ): ResponseEntity<Map<String, Any>> {
        val userId = auth.principal as? Long ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val tender = tenderRepo.findById(id).orElseThrow { NoSuchElementException("Tender $id not found") }
        if (tender.userId != userId) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()

        val newSupplierIds = body?.supplierIds
        if (!newSupplierIds.isNullOrEmpty()) {
            tenderSupplierRepo.deleteByTenderId(id)
            newSupplierIds.forEach { sid ->
                tenderSupplierRepo.save(
                    TenderSupplier(
                        id = TenderSupplierId(id, sid),
                        tender = tender,
                        supplier = supplierRepo.getReferenceById(sid)
                    )
                )
            }
        }
        proposalRepo.findByTenderId(id).forEach { proposalLineRepo.deleteByProposalId(it.id) }
        proposalRepo.deleteByTenderId(id)
        matchResultRepo.deleteByLineTenderId(id)
        tenderRepo.save(tender.copy(status = "pending_match"))
        matchingService.matchAsync(id)
        return ResponseEntity.ok(mapOf("jobId" to "rfp-$id-match"))
    }

    @GetMapping("/{id}/report")
    fun report(@PathVariable id: Long): ResponseEntity<Map<String, Any?>> {
        val tender = tenderRepo.findById(id).orElseThrow { NoSuchElementException("Tender $id not found") }
        val lineCount = tenderLineRepo.findByTenderId(id).size
        val results = matchResultRepo.findByLineTenderId(id).map { r ->
            mapOf(
                "lineId" to r.line.id,
                "description" to r.line.description,
                "qty" to r.line.qty,
                "matchType" to r.matchType,
                "score" to r.score,
                "status" to r.status,
                "matchedProduct" to r.product?.name,
                "mpn" to r.product?.mpn,
                "attributeVerdicts" to r.attributeVerdicts,
                "alternatives" to r.alternatives
            )
        }
        return ResponseEntity.ok(mapOf("rfpId" to tender.id, "status" to tender.status, "lineCount" to lineCount, "items" to results))
    }

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
}
```

- [ ] **Step 5: Run all tests to verify they pass**

```
cd backend && .\mvnw test -Dtest=RfpControllerTest
```
Expected: all `RfpControllerTest` tests pass (including the 3 pre-existing ones and the new ones).

Also run the full suite to confirm no regressions:
```
cd backend && .\mvnw test
```
Expected: same pass count as before (266 pass, 39 pre-existing @DataJpaTest failures).

- [ ] **Step 6: Commit**

```
git add backend/src/main/kotlin/com/rfp/repository/TenderRepository.kt
git add backend/src/main/kotlin/com/rfp/repository/MatchResultRepository.kt
git add backend/src/main/kotlin/com/rfp/repository/TenderLineRepository.kt
git add backend/src/main/kotlin/com/rfp/repository/TenderSupplierRepository.kt
git add backend/src/main/kotlin/com/rfp/repository/ProposalLineRepository.kt
git add backend/src/main/kotlin/com/rfp/controller/RfpController.kt
git add backend/src/test/kotlin/com/rfp/controller/RfpControllerTest.kt
git commit -m "feat: add GET /rfp list, DELETE /rfp/{id}, and re-run match with optional supplierIds"
```

---

### Task 2: Frontend types and API methods

**Files:**
- Modify: `frontend/src/lib/types.ts`
- Modify: `frontend/src/lib/api.ts`

**Interfaces:**
- Consumes: `GET /rfp` returns `List<TenderSummaryDto>` from Task 1; `DELETE /rfp/{id}` returns 204; `POST /rfp/{id}/match` accepts optional `{"supplierIds":[...]}` body.
- Produces: `TenderSummary` interface exported from `types.ts`; `api.myRfps.list`, `api.myRfps.delete`, updated `api.triggerMatch` — all used in Tasks 3 and 4.

- [ ] **Step 1: Add `TenderSummary` to `types.ts`**

Append to `frontend/src/lib/types.ts`:
```typescript
export interface TenderSummary {
  id: number;
  filename: string;
  status: 'uploading' | 'extracting' | 'matching' | 'pending_match' | 'done' | 'failed';
  createdAt: string;
  lineCount: number;
  proposalCount: number;
  supplierIds: number[];
}
```

- [ ] **Step 2: Add `myRfps` namespace and update `triggerMatch` in `api.ts`**

In `api.ts`, update the import line to add `TenderSummary`:
```typescript
import type { Supplier, RfpReport, AdminUser, AdminProduct, AdminTender, PageResult, IngestRecord, SelectedProduct, ProposalAlternative, ProposalLine, Proposal, ProductSearchResult, CrawlRunSummary, CrawlRunDetail, TenderSummary } from './types';
```

Update `triggerMatch` to accept an optional `supplierIds` parameter:
```typescript
async triggerMatch(rfpId: number, token: string, supplierIds?: number[]): Promise<{ jobId: string }> {
  const res = await fetch(`${BASE}/rfp/${rfpId}/match`, {
    method: 'POST',
    headers: authHeaders(token),
    body: supplierIds !== undefined ? JSON.stringify({ supplierIds }) : undefined,
  });
  return json<{ jobId: string }>(res);
},
```

Add `myRfps` namespace before `admin`:
```typescript
myRfps: {
  async list(token: string): Promise<TenderSummary[]> {
    const res = await fetch(`${BASE}/rfp`, { headers: authHeaders(token) });
    return json<TenderSummary[]>(res);
  },
  async delete(rfpId: number, token: string): Promise<void> {
    const res = await fetch(`${BASE}/rfp/${rfpId}`, {
      method: 'DELETE',
      headers: authHeaders(token),
    });
    if (!res.ok) throw new Error(`API error ${res.status}`);
  },
},
```

- [ ] **Step 3: TypeScript type-check**

```
cd frontend && npm run build
```
Expected: clean build, no type errors.

- [ ] **Step 4: Commit**

```
git add frontend/src/lib/types.ts frontend/src/lib/api.ts
git commit -m "feat: add TenderSummary type and myRfps API methods"
```

---

### Task 3: SubmissionCard component

**Files:**
- Create: `frontend/src/components/SubmissionCard.tsx`
- Create: `frontend/src/__tests__/SubmissionCard.test.tsx`
- Modify: `frontend/src/components/SupplierCombobox.tsx` — add `defaultSelectedIds?: number[]` prop

**Interfaces:**
- Consumes: `TenderSummary` from Task 2; `api.myRfps.delete`, `api.triggerMatch` from Task 2; `SupplierCombobox` component (extended with `defaultSelectedIds`).
- Produces: `SubmissionCard` component used in Task 4; `SupplierCombobox` `defaultSelectedIds` prop used here.

- [ ] **Step 1: Add `defaultSelectedIds` to SupplierCombobox**

In `SupplierCombobox.tsx`, update the `Props` interface:
```typescript
interface Props {
  token: string;
  onChange: (selected: SelectedSupplier[]) => void;
  defaultSelectedIds?: number[];
}
```

And in the component body, update the `useEffect` that loads suppliers to pre-select defaults:
```typescript
useEffect(() => {
  if (token) {
    api.listSuppliers(token).then(all => {
      setSuppliers(all);
      if (defaultSelectedIds && defaultSelectedIds.length > 0) {
        const preSelected = all
          .filter(s => defaultSelectedIds.includes(s.id))
          .map(s => ({ id: s.id, name: s.name, catalogFile: null }));
        setSelected(preSelected);
        onChange(preSelected);
      }
    }).catch(() => {});
  }
}, [token]); // eslint-disable-line react-hooks/exhaustive-deps
```

- [ ] **Step 2: Write failing tests for SubmissionCard**

Create `frontend/src/__tests__/SubmissionCard.test.tsx`:

```typescript
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { SubmissionCard } from '@/src/components/SubmissionCard';
import { api } from '@/src/lib/api';

jest.mock('@/src/lib/api');
const mockApi = api as jest.Mocked<typeof api>;

const baseTender = {
  id: 1,
  filename: 'test.pdf',
  status: 'done' as const,
  createdAt: '2026-08-17T10:00:00Z',
  lineCount: 5,
  proposalCount: 2,
  supplierIds: [10, 20],
};

describe('SubmissionCard', () => {
  afterEach(() => jest.clearAllMocks());

  it('renders filename and status badge', () => {
    render(
      <SubmissionCard tender={baseTender} token="tok" onDeleted={() => {}} />
    );
    expect(screen.getByText('test.pdf')).toBeInTheDocument();
    expect(screen.getByText('done')).toBeInTheDocument();
  });

  it('renders line and proposal counts', () => {
    render(
      <SubmissionCard tender={baseTender} token="tok" onDeleted={() => {}} />
    );
    expect(screen.getByText('5 lines')).toBeInTheDocument();
    expect(screen.getByText('2 proposals')).toBeInTheDocument();
  });

  it('View Report link is enabled for done status', () => {
    render(
      <SubmissionCard tender={baseTender} token="tok" onDeleted={() => {}} />
    );
    const link = screen.getByRole('link', { name: /view report/i });
    expect(link).toHaveAttribute('href', '/rfp/1');
  });

  it('View Report link is disabled while extracting', () => {
    render(
      <SubmissionCard
        tender={{ ...baseTender, status: 'extracting' }}
        token="tok"
        onDeleted={() => {}}
      />
    );
    expect(screen.queryByRole('link', { name: /view report/i })).toBeNull();
    expect(screen.getByText(/view report/i)).toBeInTheDocument();
  });

  it('Re-run panel expands on click', async () => {
    render(
      <SubmissionCard tender={baseTender} token="tok" onDeleted={() => {}} />
    );
    fireEvent.click(screen.getByRole('button', { name: /re-run matching/i }));
    expect(screen.getByRole('button', { name: /run/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /cancel/i })).toBeInTheDocument();
  });

  it('Delete calls api.myRfps.delete and invokes onDeleted', async () => {
    const onDeleted = jest.fn();
    mockApi.myRfps = {
      list: jest.fn(),
      delete: jest.fn().mockResolvedValue(undefined),
    };
    window.confirm = jest.fn().mockReturnValue(true);

    render(
      <SubmissionCard tender={baseTender} token="tok" onDeleted={onDeleted} />
    );
    fireEvent.click(screen.getByRole('button', { name: /delete/i }));
    await waitFor(() => expect(mockApi.myRfps.delete).toHaveBeenCalledWith(1, 'tok'));
    expect(onDeleted).toHaveBeenCalledWith(1);
  });

  it('Delete does not call api when user cancels confirm', async () => {
    mockApi.myRfps = {
      list: jest.fn(),
      delete: jest.fn(),
    };
    window.confirm = jest.fn().mockReturnValue(false);

    render(
      <SubmissionCard tender={baseTender} token="tok" onDeleted={() => {}} />
    );
    fireEvent.click(screen.getByRole('button', { name: /delete/i }));
    expect(mockApi.myRfps.delete).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 3: Run tests to confirm they fail**

```
cd frontend && npm test -- src/__tests__/SubmissionCard.test.tsx
```
Expected: FAIL — `SubmissionCard` module not found.

- [ ] **Step 4: Implement SubmissionCard**

Create `frontend/src/components/SubmissionCard.tsx`:

```typescript
'use client';
import { useState } from 'react';
import Link from 'next/link';
import { SupplierCombobox, type SelectedSupplier } from './SupplierCombobox';
import { api } from '@/src/lib/api';
import type { TenderSummary } from '@/src/lib/types';

const STATUS_COLORS: Record<string, string> = {
  uploading: 'bg-slate-200 text-slate-700',
  extracting: 'bg-amber-100 text-amber-700',
  matching: 'bg-blue-100 text-blue-700',
  pending_match: 'bg-blue-100 text-blue-700',
  done: 'bg-green-100 text-green-700',
  failed: 'bg-red-100 text-red-700',
};

const REPORT_ENABLED_STATUSES = new Set(['matching', 'pending_match', 'done', 'failed']);

interface Props {
  tender: TenderSummary;
  token: string;
  onDeleted: (id: number) => void;
}

export function SubmissionCard({ tender, token, onDeleted }: Props) {
  const [rerunOpen, setRerunOpen] = useState(false);
  const [selectedSuppliers, setSelectedSuppliers] = useState<SelectedSupplier[]>([]);
  const [rerunError, setRerunError] = useState('');
  const [rerunLoading, setRerunLoading] = useState(false);
  const [deleteError, setDeleteError] = useState('');

  const reportEnabled = REPORT_ENABLED_STATUSES.has(tender.status);
  const rerunDisabled = tender.status === 'matching' || tender.status === 'extracting';

  async function handleDelete() {
    if (!window.confirm('Delete this submission and all its results?')) return;
    try {
      await api.myRfps.delete(tender.id, token);
      onDeleted(tender.id);
    } catch {
      setDeleteError('Could not delete submission.');
    }
  }

  async function handleRerun() {
    setRerunLoading(true);
    setRerunError('');
    try {
      const ids = selectedSuppliers.map(s => s.id);
      await api.triggerMatch(tender.id, token, ids.length > 0 ? ids : undefined);
      setRerunOpen(false);
    } catch {
      setRerunError('Could not start matching.');
    } finally {
      setRerunLoading(false);
    }
  }

  const statusColor = STATUS_COLORS[tender.status] ?? 'bg-gray-100 text-gray-700';
  const created = new Date(tender.createdAt).toLocaleString();

  return (
    <div className="border rounded-lg p-4 space-y-2">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <span className="font-medium">{tender.filename}</span>
          <span className={`ml-2 px-2 py-0.5 rounded text-xs font-semibold ${statusColor}`}>
            {tender.status}
          </span>
        </div>
        <span className="text-sm text-gray-500">{created}</span>
      </div>

      <div className="text-sm text-gray-600 flex gap-4">
        <span>{tender.lineCount} lines</span>
        <span>{tender.proposalCount} proposals</span>
      </div>

      <div className="flex gap-2 flex-wrap items-center">
        {reportEnabled ? (
          <Link
            href={`/rfp/${tender.id}`}
            className="text-sm px-3 py-1 rounded bg-blue-600 text-white hover:bg-blue-700"
          >
            View Report →
          </Link>
        ) : (
          <span className="text-sm px-3 py-1 rounded bg-gray-200 text-gray-400 cursor-not-allowed">
            View Report
          </span>
        )}

        <button
          disabled={rerunDisabled}
          onClick={() => setRerunOpen(o => !o)}
          className="text-sm px-3 py-1 rounded border border-gray-300 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          Re-run Matching
        </button>

        <button
          onClick={handleDelete}
          className="text-sm px-3 py-1 rounded border border-red-300 text-red-600 hover:bg-red-50"
        >
          Delete
        </button>
      </div>

      {deleteError && <p className="text-sm text-red-600">{deleteError}</p>}

      {rerunOpen && (
        <div className="border-t pt-3 space-y-2">
          <p className="text-sm font-medium">Select suppliers for re-run:</p>
          <SupplierCombobox
            token={token}
            defaultSelectedIds={tender.supplierIds}
            onChange={setSelectedSuppliers}
          />
          <div className="flex gap-2 items-center">
            <button
              onClick={handleRerun}
              disabled={rerunLoading}
              className="text-sm px-3 py-1 rounded bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {rerunLoading ? 'Starting…' : 'Run'}
            </button>
            <button
              onClick={() => setRerunOpen(false)}
              className="text-sm text-gray-500 hover:underline"
            >
              Cancel
            </button>
          </div>
          {rerunError && <p className="text-sm text-red-600">{rerunError}</p>}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 5: Run tests to verify they pass**

```
cd frontend && npm test -- src/__tests__/SubmissionCard.test.tsx
```
Expected: all tests pass.

- [ ] **Step 6: TypeScript build check**

```
cd frontend && npm run build
```
Expected: clean build.

- [ ] **Step 7: Commit**

```
git add frontend/src/components/SubmissionCard.tsx
git add frontend/src/components/SupplierCombobox.tsx
git add frontend/src/__tests__/SubmissionCard.test.tsx
git commit -m "feat: add SubmissionCard component with re-run and delete actions"
```

---

### Task 4: /my-rfps page, MyRfpsPage tests, and nav link

**Files:**
- Create: `frontend/src/app/my-rfps/page.tsx`
- Create: `frontend/src/__tests__/MyRfpsPage.test.tsx`
- Modify: `frontend/src/app/page.tsx` — add "My RFPs →" nav link

**Interfaces:**
- Consumes: `SubmissionCard` from Task 3; `api.myRfps.list` from Task 2; `useAuth` hook (existing, returns `{ token, logout }`).
- Produces: `/my-rfps` route accessible from the home nav bar.

- [ ] **Step 1: Write failing tests for MyRfpsPage**

Create `frontend/src/__tests__/MyRfpsPage.test.tsx`:

```typescript
import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import MyRfpsPage from '@/src/app/my-rfps/page';
import { api } from '@/src/lib/api';
import { useAuth } from '@/src/hooks/useAuth';

jest.mock('@/src/lib/api');
jest.mock('@/src/hooks/useAuth');
jest.mock('@/src/components/SubmissionCard', () => ({
  SubmissionCard: ({ tender }: { tender: { filename: string } }) => (
    <div data-testid="submission-card">{tender.filename}</div>
  ),
}));

const mockApi = api as jest.Mocked<typeof api>;
const mockUseAuth = useAuth as jest.Mock;

const sampleTender = {
  id: 1,
  filename: 'instruments.pdf',
  status: 'done' as const,
  createdAt: '2026-08-17T09:00:00Z',
  lineCount: 3,
  proposalCount: 1,
  supplierIds: [],
};

describe('MyRfpsPage', () => {
  beforeEach(() => {
    mockUseAuth.mockReturnValue({ token: 'test-token', logout: jest.fn() });
  });

  afterEach(() => jest.clearAllMocks());

  it('shows loading skeleton initially', () => {
    mockApi.myRfps = { list: jest.fn().mockResolvedValue([]), delete: jest.fn() };
    render(<MyRfpsPage />);
    expect(screen.getByText(/loading/i)).toBeInTheDocument();
  });

  it('renders submission cards after load', async () => {
    mockApi.myRfps = {
      list: jest.fn().mockResolvedValue([sampleTender]),
      delete: jest.fn(),
    };
    render(<MyRfpsPage />);
    await waitFor(() =>
      expect(screen.getByTestId('submission-card')).toBeInTheDocument()
    );
    expect(screen.getByText('instruments.pdf')).toBeInTheDocument();
  });

  it('shows empty state when no tenders', async () => {
    mockApi.myRfps = { list: jest.fn().mockResolvedValue([]), delete: jest.fn() };
    render(<MyRfpsPage />);
    await waitFor(() =>
      expect(screen.getByText(/no submissions yet/i)).toBeInTheDocument()
    );
  });

  it('shows error state when list call fails', async () => {
    mockApi.myRfps = {
      list: jest.fn().mockRejectedValue(new Error('network error')),
      delete: jest.fn(),
    };
    render(<MyRfpsPage />);
    await waitFor(() =>
      expect(screen.getByText(/could not load/i)).toBeInTheDocument()
    );
  });
});
```

- [ ] **Step 2: Run tests to confirm they fail**

```
cd frontend && npm test -- src/__tests__/MyRfpsPage.test.tsx
```
Expected: FAIL — `my-rfps/page` module not found.

- [ ] **Step 3: Create the /my-rfps page**

Create `frontend/src/app/my-rfps/page.tsx`:

```typescript
'use client';
import { useEffect, useState } from 'react';
import Link from 'next/link';
import { SubmissionCard } from '@/src/components/SubmissionCard';
import { api } from '@/src/lib/api';
import { useAuth } from '@/src/hooks/useAuth';
import type { TenderSummary } from '@/src/lib/types';

export default function MyRfpsPage() {
  const { token, logout } = useAuth();
  const [tenders, setTenders] = useState<TenderSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!token) return;
    api.myRfps.list(token)
      .then(setTenders)
      .catch(() => setError('Could not load submissions.'))
      .finally(() => setLoading(false));
  }, [token]);

  function handleDeleted(id: number) {
    setTenders(ts => ts.filter(t => t.id !== id));
  }

  return (
    <div className="min-h-screen p-8 max-w-3xl mx-auto space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">My RFP Submissions</h1>
        <div className="flex gap-4 text-sm">
          <Link href="/" className="text-blue-600 hover:underline">New RFP →</Link>
          <button onClick={logout} className="text-gray-500 hover:underline">Logout</button>
        </div>
      </div>

      {loading && (
        <div className="space-y-3">
          {[1, 2, 3].map(i => (
            <div key={i} className="border rounded-lg p-4 animate-pulse bg-gray-100 h-20" />
          ))}
          <span className="sr-only">Loading…</span>
        </div>
      )}

      {!loading && error && (
        <p className="text-red-600">{error}</p>
      )}

      {!loading && !error && tenders.length === 0 && (
        <p className="text-gray-500">
          No submissions yet.{' '}
          <Link href="/" className="text-blue-600 hover:underline">Upload your first RFP →</Link>
        </p>
      )}

      {!loading && !error && tenders.length > 0 && (
        <div className="space-y-4">
          {tenders.map(t => (
            <SubmissionCard
              key={t.id}
              tender={t}
              token={token}
              onDeleted={handleDeleted}
            />
          ))}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Add "My RFPs →" nav link to home page**

In `frontend/src/app/page.tsx`, find the existing nav area that has "Admin →" (search for `Admin →` or `admin`). Add a "My RFPs →" link next to it. The link should be visible to all authenticated users.

Look for the nav section (typically rendered once the user is logged in). Add:
```typescript
<Link href="/my-rfps" className="text-sm text-blue-600 hover:underline">My RFPs →</Link>
```
Place it before or after the existing "Admin →" link, maintaining consistent styling.

Note: Read `frontend/src/app/page.tsx` fully before editing to place the link in the right spot.

- [ ] **Step 5: Run MyRfpsPage tests**

```
cd frontend && npm test -- src/__tests__/MyRfpsPage.test.tsx
```
Expected: all 4 tests pass.

- [ ] **Step 6: Run full frontend test suite**

```
cd frontend && npm test
```
Expected: all existing tests pass, new tests pass.

- [ ] **Step 7: TypeScript build check**

```
cd frontend && npm run build
```
Expected: clean build.

- [ ] **Step 8: Commit**

```
git add frontend/src/app/my-rfps/page.tsx
git add frontend/src/__tests__/MyRfpsPage.test.tsx
git add frontend/src/app/page.tsx
git commit -m "feat: add /my-rfps page with submission history and nav link"
```
