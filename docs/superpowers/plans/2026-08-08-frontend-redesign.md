# Frontend Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add auth UI, free-text supplier creation with optional catalog upload, admin portal (users/suppliers/products/RFPs), and redesign all pages with a clean enterprise/SaaS aesthetic.

**Architecture:** JWT stored in `localStorage`; `useAuth` hook guards every protected page and redirects to `/auth?next=<path>`. Admin endpoints live in `AdminController.kt`. All new UI follows the indigo/slate design system with Inter font.

**Tech Stack:** Next.js 16 App Router + TypeScript + Tailwind CSS · Spring Boot 3.2 + Kotlin · PostgreSQL 17

## Global Constraints

- Tailwind classes only — no inline style objects or CSS modules unless Tailwind can't do it
- Next.js 16 App Router: `'use client'` directive required for any component that uses hooks or browser APIs
- All backend commands use: `$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot"` and Maven at `C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd`
- Design palette: accent `indigo-600`, success `emerald-600`, warning `amber-500`, danger `red-600`, sidebar `slate-900`
- No Mockito — MockK for all backend tests
- Kotlin functions with early `return` inside must use block body `{ return ... }` not expression body `= ...`

---

### Task 1: Backend Admin Endpoints

**Files:**
- Modify: `backend/src/main/kotlin/com/rfp/controller/AdminController.kt`
- Modify: `backend/src/main/kotlin/com/rfp/repository/ProductRepository.kt`

**Interfaces:**
- Produces:
  - `GET /admin/users` → `[{id, username, role}]`
  - `DELETE /admin/users/{id}` → 204
  - `GET /admin/products?page=0&size=50&q=` → `{content:[{id,name,mpn,supplierName,productClass,isStale}], totalElements}`
  - `GET /admin/tenders` → `[{id,filename,userId,status,createdAt}]`

- [ ] **Step 1: Add paginated search to ProductRepository**

Open `backend/src/main/kotlin/com/rfp/repository/ProductRepository.kt` and add:

```kotlin
package com.rfp.repository

import com.rfp.domain.Product
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ProductRepository : JpaRepository<Product, Long> {
    fun findBySupplierIdInAndIsStaleAndNameIgnoreCase(
        supplierIds: List<Long>, isStale: Boolean, name: String
    ): List<Product>
    fun findBySupplierIdInAndIsStaleAndMpnIgnoreCase(
        supplierIds: List<Long>, isStale: Boolean, mpn: String
    ): List<Product>
    fun findBySupplierIdInAndIsStaleAndProductClassId(
        supplierIds: List<Long>, isStale: Boolean, classId: Long
    ): List<Product>
    fun findBySupplierIdAndMpnIgnoreCase(supplierId: Long, mpn: String): Product?
    fun findBySupplierIdAndNameIgnoreCase(supplierId: Long, name: String): Product?
    fun findBySupplierId(supplierId: Long): List<Product>

    @Query("""
        SELECT p FROM Product p
        WHERE (:q IS NULL OR :q = ''
               OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(p.mpn,'')) LIKE LOWER(CONCAT('%', :q, '%')))
    """)
    fun searchByNameOrMpn(q: String?, pageable: Pageable): Page<Product>
}
```

- [ ] **Step 2: Rewrite AdminController with new endpoints**

Replace the entire contents of `backend/src/main/kotlin/com/rfp/controller/AdminController.kt`:

```kotlin
package com.rfp.controller

import com.rfp.domain.Tender
import com.rfp.job.CatalogRefreshJob
import com.rfp.repository.AppUserRepository
import com.rfp.repository.ProductRepository
import com.rfp.repository.TenderRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class UserDto(val id: Long, val username: String, val role: String)
data class ProductDto(
    val id: Long, val name: String, val mpn: String?,
    val supplierName: String, val productClass: String?, val isStale: Boolean
)
data class TenderDto(
    val id: Long, val filename: String, val userId: Long,
    val status: String, val createdAt: String
)
data class PageResult<T>(val content: List<T>, val totalElements: Long)

@RestController
@RequestMapping("/admin")
class AdminController(
    private val refreshJob: CatalogRefreshJob,
    private val userRepo: AppUserRepository,
    private val productRepo: ProductRepository,
    private val tenderRepo: TenderRepository
) {

    @PostMapping("/refresh")
    fun triggerRefresh(): ResponseEntity<Map<String, String>> {
        refreshJob.refreshStaleSuppliers()
        return ResponseEntity.ok(mapOf("status" to "refresh enqueued"))
    }

    @GetMapping("/users")
    fun listUsers(): List<UserDto> =
        userRepo.findAll().map { UserDto(it.id, it.username, it.role) }

    @DeleteMapping("/users/{id}")
    fun deleteUser(@PathVariable id: Long): ResponseEntity<Void> {
        userRepo.deleteById(id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/products")
    fun listProducts(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(required = false) q: String?
    ): PageResult<ProductDto> {
        val pageable = PageRequest.of(page, size, Sort.by("name"))
        val result = productRepo.searchByNameOrMpn(q, pageable)
        return PageResult(
            content = result.content.map { p ->
                ProductDto(
                    id = p.id,
                    name = p.name,
                    mpn = p.mpn,
                    supplierName = p.supplier.name,
                    productClass = p.productClass?.name,
                    isStale = p.isStale
                )
            },
            totalElements = result.totalElements
        )
    }

    @GetMapping("/tenders")
    fun listTenders(): List<TenderDto> =
        tenderRepo.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).map { t ->
            TenderDto(t.id, t.filename, t.userId, t.status, t.createdAt.toString())
        }
}
```

- [ ] **Step 3: Compile backend to verify**

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot"
$env:PATH = "C:\tools\maven\apache-maven-3.9.8\bin;$env:JAVA_HOME\bin;$env:PATH"
Set-Location "D:\dev\RFP\rfp\backend"
& "C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd" compile -q
```

Expected: `BUILD SUCCESS` (no output means success with `-q`)

- [ ] **Step 4: Commit**

```powershell
git add backend/src/main/kotlin/com/rfp/controller/AdminController.kt `
       backend/src/main/kotlin/com/rfp/repository/ProductRepository.kt
git commit -m "feat: add admin CRUD endpoints (users, products, tenders)"
```

---

### Task 2: Frontend Foundation (Types + API + Font)

**Files:**
- Modify: `frontend/src/lib/types.ts`
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/app/layout.tsx`
- Create: `frontend/src/app/globals.css`

**Interfaces:**
- Produces: `api.auth.login`, `api.auth.register`, `api.uploadCatalog`, `api.admin.listUsers`, `api.admin.deleteUser`, `api.admin.listProducts`, `api.admin.listTenders`

- [ ] **Step 1: Update types.ts**

Replace `frontend/src/lib/types.ts` entirely:

```typescript
export interface Supplier {
  id: number;
  name: string;
  officialWebsite?: string;
  contactEmail?: string;
  contactPhone?: string;
  country?: string;
  description?: string;
  categories: string[];
  scrapeStatus: string;
}

export interface AttributeVerdict {
  attr: string;
  required: unknown;
  offered: unknown;
  verdict: 'COMPLIANT' | 'DEVIATION' | 'UNVERIFIABLE';
}

export interface Alternative {
  productId: number;
  name: string;
  mpn: string | null;
  score: number;
  attributeVerdicts: AttributeVerdict[];
}

export interface MatchResultItem {
  lineId: number;
  description: string;
  qty: number | null;
  matchType: 'exact' | 'spec' | null;
  score: number;
  status: 'matched' | 'partial' | 'not_found';
  matchedProduct: string | null;
  mpn: string | null;
  attributeVerdicts: AttributeVerdict[];
  alternatives: Alternative[];
}

export interface RfpReport {
  rfpId: number;
  status: string;
  items: MatchResultItem[];
}

export interface JobStatus {
  id: number;
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';
  error?: string;
}

export interface AdminUser {
  id: number;
  username: string;
  role: string;
}

export interface AdminProduct {
  id: number;
  name: string;
  mpn: string | null;
  supplierName: string;
  productClass: string | null;
  isStale: boolean;
}

export interface AdminTender {
  id: number;
  filename: string;
  userId: number;
  status: string;
  createdAt: string;
}

export interface PageResult<T> {
  content: T[];
  totalElements: number;
}
```

- [ ] **Step 2: Update api.ts**

Replace `frontend/src/lib/api.ts` entirely:

```typescript
import type { Supplier, RfpReport, AdminUser, AdminProduct, AdminTender, PageResult } from './types';

const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`API error ${res.status}`);
  return res.json();
}

export const api = {
  auth: {
    async login(username: string, password: string): Promise<{ token: string }> {
      const res = await fetch(`${BASE}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      return json<{ token: string }>(res);
    },
    async register(username: string, password: string): Promise<{ token: string }> {
      const res = await fetch(`${BASE}/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      return json<{ token: string }>(res);
    },
  },

  async listSuppliers(token: string): Promise<Supplier[]> {
    const res = await fetch(`${BASE}/suppliers`, { headers: authHeaders(token) });
    return json<Supplier[]>(res);
  },

  async registerSupplier(
    data: { name: string; officialWebsite?: string },
    token: string
  ): Promise<Supplier> {
    const res = await fetch(`${BASE}/suppliers`, {
      method: 'POST',
      headers: authHeaders(token),
      body: JSON.stringify(data),
    });
    return json<Supplier>(res);
  },

  async uploadCatalog(supplierId: number, file: File, token: string): Promise<{ jobId: string }> {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch(`${BASE}/suppliers/${supplierId}/catalog/upload`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    });
    return json<{ jobId: string }>(res);
  },

  async uploadRfp(
    file: File,
    supplierIds: number[],
    token: string
  ): Promise<{ rfpId: number }> {
    const form = new FormData();
    form.append('file', file);
    supplierIds.forEach(id => form.append('supplierIds', String(id)));
    const res = await fetch(`${BASE}/rfp/upload`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    });
    return json<{ rfpId: number }>(res);
  },

  async triggerMatch(rfpId: number, token: string): Promise<{ jobId: string }> {
    const res = await fetch(`${BASE}/rfp/${rfpId}/match`, {
      method: 'POST',
      headers: authHeaders(token),
    });
    return json<{ jobId: string }>(res);
  },

  async getReport(rfpId: number, token: string): Promise<RfpReport> {
    const res = await fetch(`${BASE}/rfp/${rfpId}/report`, {
      headers: authHeaders(token),
    });
    return json<RfpReport>(res);
  },

  admin: {
    async listUsers(token: string): Promise<AdminUser[]> {
      const res = await fetch(`${BASE}/admin/users`, { headers: authHeaders(token) });
      return json<AdminUser[]>(res);
    },
    async deleteUser(id: number, token: string): Promise<void> {
      await fetch(`${BASE}/admin/users/${id}`, {
        method: 'DELETE',
        headers: authHeaders(token),
      });
    },
    async listProducts(
      token: string,
      opts?: { page?: number; size?: number; q?: string }
    ): Promise<PageResult<AdminProduct>> {
      const params = new URLSearchParams();
      if (opts?.page !== undefined) params.set('page', String(opts.page));
      if (opts?.size !== undefined) params.set('size', String(opts.size));
      if (opts?.q) params.set('q', opts.q);
      const res = await fetch(`${BASE}/admin/products?${params}`, {
        headers: authHeaders(token),
      });
      return json<PageResult<AdminProduct>>(res);
    },
    async listTenders(token: string): Promise<AdminTender[]> {
      const res = await fetch(`${BASE}/admin/tenders`, { headers: authHeaders(token) });
      return json<AdminTender[]>(res);
    },
  },
};
```

- [ ] **Step 3: Add Inter font + global CSS to layout.tsx**

Replace `frontend/src/app/layout.tsx`:

```typescript
import type { Metadata } from 'next';
import { Inter } from 'next/font/google';
import './globals.css';

const inter = Inter({ subsets: ['latin'] });

export const metadata: Metadata = {
  title: 'RFP Instrument Matching',
  description: 'Match RFP instrument requirements against company catalogs',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className={`${inter.className} bg-slate-50 text-slate-900`}>{children}</body>
    </html>
  );
}
```

- [ ] **Step 4: Create globals.css**

Create `frontend/src/app/globals.css`:

```css
@tailwind base;
@tailwind components;
@tailwind utilities;
```

- [ ] **Step 5: Type-check**

```powershell
Set-Location "D:\dev\RFP\rfp\frontend"
npx tsc --noEmit
```

Expected: no errors

- [ ] **Step 6: Commit**

```powershell
git add frontend/src/lib/types.ts frontend/src/lib/api.ts `
       frontend/src/app/layout.tsx frontend/src/app/globals.css
git commit -m "feat: add admin types, api client update, Inter font"
```

---

### Task 3: useAuth Hook + Auth Page

**Files:**
- Create: `frontend/src/hooks/useAuth.ts`
- Create: `frontend/src/app/auth/page.tsx`

**Interfaces:**
- Produces: `useAuth(): { token: string }` — redirects to `/auth?next=<path>` if no token
- Auth page: login/register tabs, saves `token` to `localStorage`, redirects to `next` or `/`

- [ ] **Step 1: Create useAuth hook**

Create `frontend/src/hooks/useAuth.ts`:

```typescript
'use client';
import { useEffect, useState } from 'react';
import { useRouter, usePathname } from 'next/navigation';

export function useAuth() {
  const router = useRouter();
  const pathname = usePathname();
  const [token, setToken] = useState('');

  useEffect(() => {
    const stored = localStorage.getItem('token') ?? '';
    if (!stored) {
      router.replace(`/auth?next=${encodeURIComponent(pathname)}`);
    } else {
      setToken(stored);
    }
  }, [pathname, router]);

  return { token };
}
```

- [ ] **Step 2: Create auth page**

Create `frontend/src/app/auth/page.tsx`:

```typescript
'use client';
import { useState, FormEvent } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { api } from '@/src/lib/api';

type Tab = 'login' | 'register';

export default function AuthPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const next = searchParams.get('next') ?? '/';

  const [tab, setTab] = useState<Tab>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    if (tab === 'register' && password !== confirm) {
      setError('Passwords do not match');
      return;
    }
    setLoading(true);
    try {
      const { token } =
        tab === 'login'
          ? await api.auth.login(username, password)
          : await api.auth.register(username, password);
      localStorage.setItem('token', token);
      router.replace(next);
    } catch {
      setError(tab === 'login' ? 'Invalid credentials' : 'Username already taken');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50">
      <div className="w-full max-w-sm bg-white border border-slate-200 rounded-xl shadow-sm p-8 space-y-6">
        <h1 className="text-xl font-semibold text-slate-900">RFP Instrument Matching</h1>

        {/* Tabs */}
        <div className="flex border-b border-slate-200">
          {(['login', 'register'] as Tab[]).map(t => (
            <button
              key={t}
              onClick={() => { setTab(t); setError(''); }}
              className={`flex-1 pb-2 text-sm font-medium capitalize transition-colors ${
                tab === t
                  ? 'border-b-2 border-indigo-600 text-indigo-600'
                  : 'text-slate-500 hover:text-slate-700'
              }`}
            >
              {t}
            </button>
          ))}
        </div>

        <form onSubmit={submit} className="space-y-4">
          <div>
            <label className="block text-xs font-medium uppercase tracking-wide text-slate-500 mb-1">
              Username
            </label>
            <input
              type="text"
              value={username}
              onChange={e => setUsername(e.target.value)}
              required
              className="w-full border border-slate-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium uppercase tracking-wide text-slate-500 mb-1">
              Password
            </label>
            <input
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
              className="w-full border border-slate-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>
          {tab === 'register' && (
            <div>
              <label className="block text-xs font-medium uppercase tracking-wide text-slate-500 mb-1">
                Confirm Password
              </label>
              <input
                type="password"
                value={confirm}
                onChange={e => setConfirm(e.target.value)}
                required
                className="w-full border border-slate-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
          )}
          {error && (
            <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded px-3 py-2">
              {error}
            </p>
          )}
          <button
            type="submit"
            disabled={loading}
            className="w-full bg-indigo-600 text-white rounded-md py-2 text-sm font-semibold hover:bg-indigo-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading ? '...' : tab === 'login' ? 'Sign In' : 'Create Account'}
          </button>
        </form>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Type-check**

```powershell
Set-Location "D:\dev\RFP\rfp\frontend"
npx tsc --noEmit
```

Expected: no errors

- [ ] **Step 4: Commit**

```powershell
git add frontend/src/hooks/useAuth.ts frontend/src/app/auth/page.tsx
git commit -m "feat: auth page with login/register tabs and useAuth hook"
```

---

### Task 4: SupplierCombobox Component

**Files:**
- Create: `frontend/src/components/SupplierCombobox.tsx`

**Interfaces:**
- Consumes: `api.listSuppliers(token)`, `api.registerSupplier({name}, token)`, `api.uploadCatalog(id, file, token)`
- Produces: `SupplierCombobox` — accepts `token`, `onChange(selected: SelectedSupplier[])` prop
- `SelectedSupplier = { id: number; name: string; catalogFile: File | null }`

- [ ] **Step 1: Create SupplierCombobox**

Create `frontend/src/components/SupplierCombobox.tsx`:

```typescript
'use client';
import { useEffect, useState, useRef } from 'react';
import { api } from '@/src/lib/api';
import type { Supplier } from '@/src/lib/types';

export interface SelectedSupplier {
  id: number;
  name: string;
  catalogFile: File | null;
}

interface Props {
  token: string;
  onChange: (selected: SelectedSupplier[]) => void;
}

export function SupplierCombobox({ token, onChange }: Props) {
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [selected, setSelected] = useState<SelectedSupplier[]>([]);
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (token) {
      api.listSuppliers(token).then(setSuppliers).catch(() => {});
    }
  }, [token]);

  const filtered = suppliers.filter(
    s =>
      s.name.toLowerCase().includes(query.toLowerCase()) &&
      !selected.some(sel => sel.id === s.id)
  );

  const isExact = suppliers.some(
    s => s.name.toLowerCase() === query.trim().toLowerCase()
  );
  const showCreate = query.trim().length > 0 && !isExact;

  const addSupplier = (sup: SelectedSupplier) => {
    const next = [...selected, sup];
    setSelected(next);
    onChange(next);
    setQuery('');
    setOpen(false);
    inputRef.current?.focus();
  };

  const selectExisting = (s: Supplier) => {
    addSupplier({ id: s.id, name: s.name, catalogFile: null });
  };

  const createNew = async () => {
    const name = query.trim();
    if (!name) return;
    setCreating(true);
    try {
      const created = await api.registerSupplier({ name }, token);
      setSuppliers(prev => [...prev, created]);
      addSupplier({ id: created.id, name: created.name, catalogFile: null });
    } catch {
      // ignore
    } finally {
      setCreating(false);
    }
  };

  const remove = (id: number) => {
    const next = selected.filter(s => s.id !== id);
    setSelected(next);
    onChange(next);
  };

  const setCatalog = (id: number, file: File | null) => {
    const next = selected.map(s => (s.id === id ? { ...s, catalogFile: file } : s));
    setSelected(next);
    onChange(next);
  };

  return (
    <div className="space-y-3">
      <label className="block text-xs font-medium uppercase tracking-wide text-slate-500">
        Suppliers
      </label>

      {/* Selected chips */}
      {selected.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {selected.map(s => (
            <div key={s.id} className="flex items-center gap-1 bg-indigo-50 border border-indigo-200 rounded-full px-3 py-1 text-sm">
              <span className="font-medium text-indigo-800">{s.name}</span>
              <label className="cursor-pointer text-indigo-500 hover:text-indigo-700 text-xs ml-1">
                {s.catalogFile ? (
                  <span title={s.catalogFile.name}>📎</span>
                ) : (
                  <span>+ Catalog</span>
                )}
                <input
                  type="file"
                  accept=".pdf,.doc,.docx,.xls,.xlsx"
                  className="hidden"
                  onChange={e => setCatalog(s.id, e.target.files?.[0] ?? null)}
                />
              </label>
              {s.catalogFile && (
                <button
                  onClick={() => setCatalog(s.id, null)}
                  className="text-indigo-400 hover:text-indigo-600 text-xs"
                  title="Remove catalog"
                >✕</button>
              )}
              <button
                onClick={() => remove(s.id)}
                className="text-indigo-400 hover:text-red-500 ml-1 text-xs"
              >✕</button>
            </div>
          ))}
        </div>
      )}

      {/* Combobox input */}
      <div className="relative">
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={e => { setQuery(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 150)}
          placeholder="Search or add supplier..."
          className="w-full border border-slate-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
        {open && (filtered.length > 0 || showCreate) && (
          <ul className="absolute z-10 mt-1 w-full bg-white border border-slate-200 rounded-md shadow-lg max-h-48 overflow-auto">
            {filtered.map(s => (
              <li
                key={s.id}
                onMouseDown={() => selectExisting(s)}
                className="px-3 py-2 text-sm cursor-pointer hover:bg-indigo-50 text-slate-800"
              >
                {s.name}
              </li>
            ))}
            {showCreate && (
              <li
                onMouseDown={createNew}
                className="px-3 py-2 text-sm cursor-pointer hover:bg-indigo-50 text-indigo-700 font-medium"
              >
                {creating ? 'Creating...' : `Create "${query.trim()}"`}
              </li>
            )}
          </ul>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Type-check**

```powershell
Set-Location "D:\dev\RFP\rfp\frontend"
npx tsc --noEmit
```

Expected: no errors

- [ ] **Step 3: Commit**

```powershell
git add frontend/src/components/SupplierCombobox.tsx
git commit -m "feat: SupplierCombobox with auto-create and per-supplier catalog upload"
```

---

### Task 5: Home Page Redesign

**Files:**
- Modify: `frontend/src/app/page.tsx`

**Interfaces:**
- Consumes: `useAuth()`, `SupplierCombobox`, `FileUpload`, `JobStatusPoller`, `api.uploadCatalog`, `api.uploadRfp`
- `SelectedSupplier = { id: number; name: string; catalogFile: File | null }`

- [ ] **Step 1: Rewrite home page**

Replace `frontend/src/app/page.tsx` entirely:

```typescript
'use client';
import { useState, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import { FileUpload } from '@/src/components/FileUpload';
import { SupplierCombobox, type SelectedSupplier } from '@/src/components/SupplierCombobox';
import { JobStatusPoller } from '@/src/components/JobStatusPoller';
import { api } from '@/src/lib/api';
import { useAuth } from '@/src/hooks/useAuth';

export default function Home() {
  const { token } = useAuth();
  const router = useRouter();
  const [file, setFile] = useState<File | null>(null);
  const [suppliers, setSuppliers] = useState<SelectedSupplier[]>([]);
  const [rfpId, setRfpId] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const submit = async () => {
    if (!file) { setError('Please select an RFP file'); return; }
    if (suppliers.length === 0) { setError('Please select at least one supplier'); return; }
    setError('');
    setLoading(true);
    try {
      // Upload any pending supplier catalogs in parallel
      const catalogUploads = suppliers
        .filter(s => s.catalogFile)
        .map(s => api.uploadCatalog(s.id, s.catalogFile!, token));
      await Promise.all(catalogUploads);

      const { rfpId: id } = await api.uploadRfp(file, suppliers.map(s => s.id), token);
      setRfpId(id);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  };

  const onComplete = useCallback(() => {
    if (rfpId !== null) router.push(`/rfp/${rfpId}`);
  }, [rfpId, router]);

  if (!token) return null;

  return (
    <main className="min-h-screen bg-slate-50">
      {/* Top nav */}
      <nav className="bg-white border-b border-slate-200 px-6 py-3 flex items-center justify-between">
        <span className="font-semibold text-slate-900">RFP Matching</span>
        <a href="/admin" className="text-sm text-slate-500 hover:text-indigo-600 transition-colors">
          Admin →
        </a>
      </nav>

      <div className="max-w-3xl mx-auto py-12 px-4 space-y-6">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Instrument Matching</h1>
          <p className="text-sm text-slate-500 mt-1">
            Upload an RFP document and select suppliers to match against their catalogs.
          </p>
        </div>

        {error && (
          <div className="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm">
            {error}
          </div>
        )}

        {rfpId !== null ? (
          <div className="bg-white border border-slate-200 rounded-xl p-6 text-center space-y-3">
            <div className="text-3xl">⚙️</div>
            <p className="font-medium text-slate-700">Processing your RFP…</p>
            <JobStatusPoller
              rfpId={rfpId}
              token={token}
              onComplete={onComplete}
              onError={setError}
            />
          </div>
        ) : (
          <div className="space-y-4">
            {/* Step 1 */}
            <div className="bg-white border border-slate-200 rounded-xl p-6 space-y-3">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                Step 1 — Upload RFP
              </p>
              <FileUpload onFile={setFile} />
              {file && (
                <p className="text-sm text-slate-600">
                  <span className="text-emerald-600 font-medium">✓</span> {file.name}
                </p>
              )}
            </div>

            {/* Step 2 */}
            <div className="bg-white border border-slate-200 rounded-xl p-6">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500 mb-3">
                Step 2 — Select Suppliers
              </p>
              <SupplierCombobox token={token} onChange={setSuppliers} />
            </div>

            {/* Step 3 */}
            <div className="bg-white border border-slate-200 rounded-xl p-6">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500 mb-3">
                Step 3 — Analyze
              </p>
              <button
                onClick={submit}
                disabled={loading || !file || suppliers.length === 0}
                className="w-full bg-indigo-600 text-white py-2.5 rounded-lg text-sm font-semibold hover:bg-indigo-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              >
                {loading ? 'Processing…' : 'Analyze RFP'}
              </button>
            </div>
          </div>
        )}
      </div>
    </main>
  );
}
```

- [ ] **Step 2: Type-check**

```powershell
Set-Location "D:\dev\RFP\rfp\frontend"
npx tsc --noEmit
```

Expected: no errors

- [ ] **Step 3: Commit**

```powershell
git add frontend/src/app/page.tsx
git commit -m "feat: redesign home page with 3-step layout and SupplierCombobox"
```

---

### Task 6: Report Page + ReportTable Redesign

**Files:**
- Modify: `frontend/src/app/rfp/[id]/page.tsx`
- Modify: `frontend/src/components/ReportTable.tsx`

- [ ] **Step 1: Rewrite ReportTable**

Replace `frontend/src/components/ReportTable.tsx`:

```typescript
'use client';
import { useState } from 'react';
import type { MatchResultItem, AttributeVerdict, Alternative } from '@/src/lib/types';

function asArray<T>(value: T[] | string | null | undefined): T[] {
  if (Array.isArray(value)) return value;
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      return Array.isArray(parsed) ? (parsed as T[]) : [];
    } catch { return []; }
  }
  return [];
}

const show = (v: unknown) =>
  v === null || v === undefined ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v);

function StatusBadge({ status }: { status: MatchResultItem['status'] }) {
  const cls = {
    matched: 'bg-emerald-100 text-emerald-700',
    partial: 'bg-amber-100 text-amber-700',
    not_found: 'bg-slate-100 text-slate-600',
  }[status];
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${cls}`}>
      {status.replace('_', ' ')}
    </span>
  );
}

function ScoreBar({ score }: { score: number }) {
  const color = score >= 80 ? 'bg-emerald-500' : score >= 40 ? 'bg-amber-400' : 'bg-slate-300';
  return (
    <div className="flex items-center gap-2">
      <div className="w-16 h-1.5 bg-slate-100 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${score}%` }} />
      </div>
      <span className="text-xs text-slate-600">{score}</span>
    </div>
  );
}

function ExpandedRow({ item }: { item: MatchResultItem }) {
  const verdicts = asArray<AttributeVerdict>(item.attributeVerdicts);
  const alts = asArray<Alternative>(item.alternatives);
  return (
    <tr>
      <td colSpan={7} className="bg-slate-50 px-6 py-4 border-b border-slate-200">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {verdicts.length > 0 && (
            <div>
              <p className="text-xs font-medium uppercase tracking-wide text-slate-400 mb-2">Attribute Verdicts</p>
              <table className="w-full text-xs">
                <tbody>
                  {verdicts.map((v, i) => (
                    <tr key={`${v.attr}-${i}`} className="border-b border-slate-100 last:border-0">
                      <td className="py-1 pr-2 font-medium text-slate-700">{v.attr}</td>
                      <td className="py-1 pr-2 text-slate-500">req {show(v.required)}</td>
                      <td className="py-1 pr-2 text-slate-500">off {show(v.offered)}</td>
                      <td className={`py-1 font-medium ${
                        v.verdict === 'COMPLIANT' ? 'text-emerald-600'
                        : v.verdict === 'DEVIATION' ? 'text-red-600'
                        : 'text-slate-400'
                      }`}>{v.verdict}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {alts.length > 0 && (
            <div>
              <p className="text-xs font-medium uppercase tracking-wide text-slate-400 mb-2">Alternatives</p>
              <ul className="space-y-1">
                {alts.map(alt => (
                  <li key={alt.productId} className="text-xs text-slate-700">
                    {alt.name}
                    {alt.mpn && <span className="text-slate-400 ml-1">({alt.mpn})</span>}
                    <span className="ml-2 text-slate-500">score: {alt.score}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </td>
    </tr>
  );
}

export function ReportTable({ items }: { items: MatchResultItem[] }) {
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  const toggle = (id: number) =>
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });

  return (
    <div className="overflow-x-auto rounded-xl border border-slate-200">
      <table className="w-full text-sm">
        <thead className="bg-slate-50 border-b border-slate-200">
          <tr>
            <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Requirement</th>
            <th className="text-right px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Qty</th>
            <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Type</th>
            <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Score</th>
            <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Status</th>
            <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Matched Product</th>
            <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">MPN</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {items.map(item => (
            <>
              <tr
                key={item.lineId}
                onClick={() => toggle(item.lineId)}
                className="hover:bg-slate-50 cursor-pointer transition-colors"
              >
                <td className="px-4 py-3 text-slate-900 max-w-xs truncate">{item.description}</td>
                <td className="px-4 py-3 text-right text-slate-600">{item.qty ?? '—'}</td>
                <td className="px-4 py-3 text-center text-slate-500 capitalize">{item.matchType ?? '—'}</td>
                <td className="px-4 py-3"><ScoreBar score={item.score} /></td>
                <td className="px-4 py-3"><StatusBadge status={item.status} /></td>
                <td className="px-4 py-3 text-slate-700">{item.matchedProduct ?? '—'}</td>
                <td className="px-4 py-3 text-slate-500 font-mono text-xs">{item.mpn ?? '—'}</td>
              </tr>
              {expanded.has(item.lineId) && <ExpandedRow key={`${item.lineId}-exp`} item={item} />}
            </>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 2: Rewrite report page**

Replace `frontend/src/app/rfp/[id]/page.tsx`:

```typescript
'use client';
import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { ReportTable } from '@/src/components/ReportTable';
import { api } from '@/src/lib/api';
import { useAuth } from '@/src/hooks/useAuth';
import type { RfpReport } from '@/src/lib/types';

function StatusBadge({ status }: { status: string }) {
  const cls = status === 'done' ? 'bg-emerald-100 text-emerald-700'
    : status === 'failed' ? 'bg-red-100 text-red-700'
    : 'bg-amber-100 text-amber-700';
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${cls}`}>
      {status}
    </span>
  );
}

export default function ReportPage() {
  const { token } = useAuth();
  const { id } = useParams<{ id: string }>();
  const [report, setReport] = useState<RfpReport | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    if (token) {
      api.getReport(Number(id), token).then(setReport).catch(e => setError(String(e)));
    }
  }, [id, token]);

  const handleExport = async (format: 'pdf' | 'xlsx') => {
    const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';
    const res = await fetch(`${apiUrl}/rfp/${id}/report/export?format=${format}`, {
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

  if (!token) return null;

  if (error) return (
    <div className="min-h-screen bg-slate-50 flex items-center justify-center">
      <p className="text-red-600 bg-red-50 border border-red-200 rounded-lg px-6 py-4">{error}</p>
    </div>
  );

  if (!report) return (
    <div className="min-h-screen bg-slate-50 flex items-center justify-center">
      <p className="text-slate-500 animate-pulse">Loading report…</p>
    </div>
  );

  const matched = report.items.filter(i => i.status === 'matched').length;
  const partial = report.items.filter(i => i.status === 'partial').length;
  const notFound = report.items.filter(i => i.status === 'not_found').length;

  return (
    <main className="min-h-screen bg-slate-50">
      <nav className="bg-white border-b border-slate-200 px-6 py-3 flex items-center gap-4">
        <a href="/" className="text-sm text-slate-500 hover:text-indigo-600 transition-colors">← Home</a>
        <span className="text-slate-300">|</span>
        <span className="text-sm font-medium text-slate-700">Report #{report.rfpId}</span>
        <StatusBadge status={report.status} />
        <div className="ml-auto flex gap-2">
          <button
            onClick={() => handleExport('xlsx')}
            className="text-xs bg-emerald-600 text-white px-3 py-1.5 rounded-md hover:bg-emerald-700 transition-colors"
          >
            Export Excel
          </button>
          <button
            onClick={() => handleExport('pdf')}
            className="text-xs bg-slate-600 text-white px-3 py-1.5 rounded-md hover:bg-slate-700 transition-colors"
          >
            Export PDF
          </button>
        </div>
      </nav>

      <div className="max-w-7xl mx-auto py-8 px-4 space-y-6">
        {/* Summary */}
        <div className="flex gap-3">
          <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm bg-emerald-100 text-emerald-700 font-medium">
            {matched} matched
          </span>
          <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm bg-amber-100 text-amber-700 font-medium">
            {partial} partial
          </span>
          <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm bg-slate-100 text-slate-600 font-medium">
            {notFound} not found
          </span>
        </div>

        <ReportTable items={report.items} />
      </div>
    </main>
  );
}
```

- [ ] **Step 3: Type-check**

```powershell
Set-Location "D:\dev\RFP\rfp\frontend"
npx tsc --noEmit
```

Expected: no errors

- [ ] **Step 4: Commit**

```powershell
git add frontend/src/app/rfp/[id]/page.tsx frontend/src/components/ReportTable.tsx
git commit -m "feat: redesign report page and ReportTable with expandable rows"
```

---

### Task 7: Admin Layout + Root Redirect

**Files:**
- Create: `frontend/src/app/admin/layout.tsx`
- Create: `frontend/src/app/admin/page.tsx`

- [ ] **Step 1: Create admin layout with sidebar**

Create `frontend/src/app/admin/layout.tsx`:

```typescript
'use client';
import { usePathname } from 'next/navigation';
import Link from 'next/link';
import { useAuth } from '@/src/hooks/useAuth';

const navItems = [
  { href: '/admin/users', label: 'Users' },
  { href: '/admin/suppliers', label: 'Suppliers' },
  { href: '/admin/products', label: 'Products' },
  { href: '/admin/rfps', label: 'RFPs' },
];

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const { token } = useAuth();
  const pathname = usePathname();

  if (!token) return null;

  return (
    <div className="flex min-h-screen">
      {/* Sidebar */}
      <aside className="w-60 shrink-0 bg-slate-900 flex flex-col">
        <div className="px-5 py-5 border-b border-slate-700">
          <span className="text-sm font-semibold text-white">RFP Admin</span>
        </div>
        <nav className="flex-1 py-4 space-y-0.5">
          {navItems.map(item => {
            const active = pathname.startsWith(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                className={`flex items-center px-5 py-2.5 text-sm transition-colors ${
                  active
                    ? 'border-l-2 border-indigo-500 bg-slate-800 text-white'
                    : 'text-slate-400 hover:text-white hover:bg-slate-800'
                }`}
              >
                {item.label}
              </Link>
            );
          })}
        </nav>
        <div className="px-5 py-4 border-t border-slate-700">
          <Link href="/" className="text-xs text-slate-500 hover:text-slate-300 transition-colors">
            ← Back to App
          </Link>
        </div>
      </aside>

      {/* Content */}
      <main className="flex-1 bg-slate-50 overflow-auto">
        {children}
      </main>
    </div>
  );
}
```

- [ ] **Step 2: Create admin root redirect**

Create `frontend/src/app/admin/page.tsx`:

```typescript
import { redirect } from 'next/navigation';

export default function AdminRoot() {
  redirect('/admin/suppliers');
}
```

- [ ] **Step 3: Type-check**

```powershell
Set-Location "D:\dev\RFP\rfp\frontend"
npx tsc --noEmit
```

Expected: no errors

- [ ] **Step 4: Commit**

```powershell
git add frontend/src/app/admin/layout.tsx frontend/src/app/admin/page.tsx
git commit -m "feat: admin layout with dark sidebar and root redirect"
```

---

### Task 8: Admin Users + Admin Suppliers Pages

**Files:**
- Create: `frontend/src/app/admin/users/page.tsx`
- Create: `frontend/src/app/admin/suppliers/page.tsx`

- [ ] **Step 1: Create admin users page**

Create `frontend/src/app/admin/users/page.tsx`:

```typescript
'use client';
import { useEffect, useState } from 'react';
import { api } from '@/src/lib/api';
import type { AdminUser } from '@/src/lib/types';
import { useAuth } from '@/src/hooks/useAuth';

export default function AdminUsersPage() {
  const { token } = useAuth();
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    if (token) {
      api.admin.listUsers(token).then(setUsers).catch(e => setError(String(e)));
    }
  }, [token]);

  const remove = async (id: number) => {
    if (!confirm('Delete this user?')) return;
    try {
      await api.admin.deleteUser(id, token);
      setUsers(prev => prev.filter(u => u.id !== id));
    } catch (e) {
      setError(String(e));
    }
  };

  return (
    <div className="p-8 space-y-6">
      <h2 className="text-xl font-semibold text-slate-900">Users</h2>
      {error && (
        <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-4 py-3">{error}</p>
      )}
      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 border-b border-slate-200">
            <tr>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">ID</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Username</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Role</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {users.map(u => (
              <tr key={u.id} className="hover:bg-slate-50">
                <td className="px-4 py-3 text-slate-500 tabular-nums">{u.id}</td>
                <td className="px-4 py-3 font-medium text-slate-900">{u.username}</td>
                <td className="px-4 py-3">
                  <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
                    u.role === 'ADMIN' ? 'bg-indigo-100 text-indigo-700' : 'bg-slate-100 text-slate-600'
                  }`}>{u.role}</span>
                </td>
                <td className="px-4 py-3 text-right">
                  <button
                    onClick={() => remove(u.id)}
                    className="text-xs text-red-600 hover:text-red-800 font-medium"
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Create admin suppliers page**

Create `frontend/src/app/admin/suppliers/page.tsx`:

```typescript
'use client';
import { useEffect, useState } from 'react';
import Link from 'next/link';
import { api } from '@/src/lib/api';
import type { Supplier } from '@/src/lib/types';
import { useAuth } from '@/src/hooks/useAuth';

const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

function ScrapeBadge({ status }: { status: string }) {
  const cls = status === 'done' ? 'bg-emerald-100 text-emerald-700'
    : status === 'failed' ? 'bg-red-100 text-red-700'
    : status === 'running' ? 'bg-amber-100 text-amber-700'
    : 'bg-slate-100 text-slate-500';
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${cls}`}>
      {status}
    </span>
  );
}

export default function AdminSuppliersPage() {
  const { token } = useAuth();
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [name, setName] = useState('');
  const [website, setWebsite] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (token) {
      api.listSuppliers(token).then(setSuppliers).catch(e => setError(String(e)));
    }
  }, [token]);

  const create = async () => {
    if (!name.trim()) return;
    try {
      const s = await api.registerSupplier({ name: name.trim(), officialWebsite: website || undefined }, token);
      setSuppliers(prev => [...prev, s]);
      setName(''); setWebsite(''); setShowCreate(false);
    } catch (e) { setError(String(e)); }
  };

  const triggerScrape = async (id: number) => {
    await fetch(`${BASE}/suppliers/${id}/catalog/scrape`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    });
  };

  const uploadCatalog = async (id: number, file: File) => {
    try { await api.uploadCatalog(id, file, token); }
    catch (e) { setError(String(e)); }
  };

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-semibold text-slate-900">Suppliers</h2>
        <button
          onClick={() => setShowCreate(v => !v)}
          className="text-sm bg-indigo-600 text-white px-4 py-2 rounded-lg hover:bg-indigo-700 transition-colors"
        >
          + New Supplier
        </button>
      </div>

      {showCreate && (
        <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-3">
          <p className="text-sm font-medium text-slate-700">New Supplier</p>
          <input
            value={name} onChange={e => setName(e.target.value)}
            placeholder="Name *"
            className="w-full border border-slate-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
          <input
            value={website} onChange={e => setWebsite(e.target.value)}
            placeholder="Website (optional)"
            className="w-full border border-slate-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
          <div className="flex gap-2">
            <button onClick={create} className="text-sm bg-indigo-600 text-white px-4 py-2 rounded-md hover:bg-indigo-700 transition-colors">
              Create
            </button>
            <button onClick={() => setShowCreate(false)} className="text-sm bg-slate-100 text-slate-700 px-4 py-2 rounded-md hover:bg-slate-200 transition-colors">
              Cancel
            </button>
          </div>
        </div>
      )}

      {error && (
        <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-4 py-3">{error}</p>
      )}

      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 border-b border-slate-200">
            <tr>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Name</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Website</th>
              <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Scrape Status</th>
              <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {suppliers.map(s => (
              <tr key={s.id} className="hover:bg-slate-50">
                <td className="px-4 py-3">
                  <Link href={`/admin/suppliers/${s.id}`} className="font-medium text-slate-900 hover:text-indigo-600 transition-colors">
                    {s.name}
                  </Link>
                </td>
                <td className="px-4 py-3 text-slate-500 text-xs">{s.officialWebsite ?? '—'}</td>
                <td className="px-4 py-3"><ScrapeBadge status={s.scrapeStatus} /></td>
                <td className="px-4 py-3">
                  <div className="flex gap-3 items-center">
                    <button onClick={() => triggerScrape(s.id)} className="text-xs text-indigo-600 hover:text-indigo-800 font-medium">
                      Scrape
                    </button>
                    <label className="text-xs text-slate-600 hover:text-slate-900 font-medium cursor-pointer">
                      Upload Catalog
                      <input type="file" accept=".pdf,.doc,.docx,.xls,.xlsx" className="hidden"
                        onChange={e => { const f = e.target.files?.[0]; if (f) uploadCatalog(s.id, f); }} />
                    </label>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Type-check**

```powershell
Set-Location "D:\dev\RFP\rfp\frontend"
npx tsc --noEmit
```

Expected: no errors

- [ ] **Step 4: Commit**

```powershell
git add frontend/src/app/admin/users/page.tsx frontend/src/app/admin/suppliers/page.tsx
git commit -m "feat: admin users and suppliers pages"
```

---

### Task 9: Admin Suppliers Detail + Products + RFPs Pages

**Files:**
- Create: `frontend/src/app/admin/suppliers/[id]/page.tsx`
- Create: `frontend/src/app/admin/products/page.tsx`
- Create: `frontend/src/app/admin/rfps/page.tsx`

- [ ] **Step 1: Create supplier detail page**

Create `frontend/src/app/admin/suppliers/[id]/page.tsx`:

```typescript
'use client';
import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { api } from '@/src/lib/api';
import type { AdminProduct, Supplier } from '@/src/lib/types';
import { useAuth } from '@/src/hooks/useAuth';

const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

export default function SupplierDetailPage() {
  const { token } = useAuth();
  const { id } = useParams<{ id: string }>();
  const [supplier, setSupplier] = useState<Supplier | null>(null);
  const [products, setProducts] = useState<AdminProduct[]>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!token) return;
    // Fetch supplier info
    fetch(`${BASE}/suppliers/${id}`, { headers: { Authorization: `Bearer ${token}` } })
      .then(r => r.json())
      .then(setSupplier)
      .catch(e => setError(String(e)));
    // Fetch products filtered by supplier via admin products endpoint
    api.admin.listProducts(token, { size: 200, q: '' })
      .then(result => setProducts(result.content))
      .catch(e => setError(String(e)));
  }, [id, token]);

  if (!token) return null;

  return (
    <div className="p-8 space-y-6">
      {supplier && (
        <div className="bg-white border border-slate-200 rounded-xl p-6">
          <h2 className="text-xl font-semibold text-slate-900">{supplier.name}</h2>
          {supplier.officialWebsite && (
            <a href={supplier.officialWebsite} target="_blank" rel="noreferrer"
              className="text-sm text-indigo-600 hover:underline">{supplier.officialWebsite}</a>
          )}
          <div className="mt-2 flex gap-3 text-sm text-slate-500">
            <span>Status: {supplier.scrapeStatus}</span>
            {supplier.categories.length > 0 && <span>Categories: {supplier.categories.join(', ')}</span>}
          </div>
        </div>
      )}

      {error && (
        <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-4 py-3">{error}</p>
      )}

      <div>
        <h3 className="text-base font-medium text-slate-700 mb-3">Products</h3>
        <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 border-b border-slate-200">
              <tr>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Name</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">MPN</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Class</th>
                <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Stale</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {products.filter(p => p.supplierName === supplier?.name).map(p => (
                <tr key={p.id} className="hover:bg-slate-50">
                  <td className="px-4 py-3 text-slate-900">{p.name}</td>
                  <td className="px-4 py-3 text-slate-500 font-mono text-xs">{p.mpn ?? '—'}</td>
                  <td className="px-4 py-3 text-slate-500">{p.productClass ?? '—'}</td>
                  <td className="px-4 py-3">
                    {p.isStale && (
                      <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-amber-100 text-amber-700">
                        stale
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Create admin products page**

Create `frontend/src/app/admin/products/page.tsx`:

```typescript
'use client';
import { useEffect, useState } from 'react';
import { api } from '@/src/lib/api';
import type { AdminProduct } from '@/src/lib/types';
import { useAuth } from '@/src/hooks/useAuth';

export default function AdminProductsPage() {
  const { token } = useAuth();
  const [products, setProducts] = useState<AdminProduct[]>([]);
  const [total, setTotal] = useState(0);
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [error, setError] = useState('');
  const SIZE = 50;

  useEffect(() => {
    if (!token) return;
    api.admin.listProducts(token, { page, size: SIZE, q: q || undefined })
      .then(r => { setProducts(r.content); setTotal(r.totalElements); })
      .catch(e => setError(String(e)));
  }, [token, page, q]);

  const pages = Math.ceil(total / SIZE);

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between gap-4">
        <h2 className="text-xl font-semibold text-slate-900">Products <span className="text-slate-400 font-normal text-base">({total})</span></h2>
        <input
          value={q}
          onChange={e => { setQ(e.target.value); setPage(0); }}
          placeholder="Search by name or MPN…"
          className="border border-slate-300 rounded-md px-3 py-2 text-sm w-64 focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
      </div>

      {error && (
        <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-4 py-3">{error}</p>
      )}

      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 border-b border-slate-200">
            <tr>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Name</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">MPN</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Supplier</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Class</th>
              <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Stale</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {products.map(p => (
              <tr key={p.id} className="hover:bg-slate-50">
                <td className="px-4 py-3 text-slate-900">{p.name}</td>
                <td className="px-4 py-3 text-slate-500 font-mono text-xs">{p.mpn ?? '—'}</td>
                <td className="px-4 py-3 text-slate-600">{p.supplierName}</td>
                <td className="px-4 py-3 text-slate-500">{p.productClass ?? '—'}</td>
                <td className="px-4 py-3">
                  {p.isStale && (
                    <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-amber-100 text-amber-700">stale</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {pages > 1 && (
        <div className="flex items-center justify-center gap-2">
          <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}
            className="text-sm px-3 py-1.5 border border-slate-300 rounded-md text-slate-600 hover:bg-slate-50 disabled:opacity-40">
            Prev
          </button>
          <span className="text-sm text-slate-500">{page + 1} / {pages}</span>
          <button onClick={() => setPage(p => Math.min(pages - 1, p + 1))} disabled={page >= pages - 1}
            className="text-sm px-3 py-1.5 border border-slate-300 rounded-md text-slate-600 hover:bg-slate-50 disabled:opacity-40">
            Next
          </button>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 3: Create admin RFPs page**

Create `frontend/src/app/admin/rfps/page.tsx`:

```typescript
'use client';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '@/src/lib/api';
import type { AdminTender } from '@/src/lib/types';
import { useAuth } from '@/src/hooks/useAuth';

function StatusBadge({ status }: { status: string }) {
  const cls = status === 'done' ? 'bg-emerald-100 text-emerald-700'
    : status === 'failed' ? 'bg-red-100 text-red-700'
    : 'bg-amber-100 text-amber-700';
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${cls}`}>
      {status}
    </span>
  );
}

export default function AdminRfpsPage() {
  const { token } = useAuth();
  const router = useRouter();
  const [tenders, setTenders] = useState<AdminTender[]>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    if (token) {
      api.admin.listTenders(token).then(setTenders).catch(e => setError(String(e)));
    }
  }, [token]);

  return (
    <div className="p-8 space-y-6">
      <h2 className="text-xl font-semibold text-slate-900">RFPs</h2>

      {error && (
        <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-4 py-3">{error}</p>
      )}

      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 border-b border-slate-200">
            <tr>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">ID</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Filename</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">User</th>
              <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Status</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Created</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {tenders.map(t => (
              <tr
                key={t.id}
                onClick={() => router.push(`/rfp/${t.id}`)}
                className="hover:bg-slate-50 cursor-pointer"
              >
                <td className="px-4 py-3 text-slate-500 tabular-nums">{t.id}</td>
                <td className="px-4 py-3 font-medium text-slate-900">{t.filename}</td>
                <td className="px-4 py-3 text-slate-500">{t.userId}</td>
                <td className="px-4 py-3"><StatusBadge status={t.status} /></td>
                <td className="px-4 py-3 text-slate-500 text-xs">
                  {new Date(t.createdAt).toLocaleString()}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Type-check**

```powershell
Set-Location "D:\dev\RFP\rfp\frontend"
npx tsc --noEmit
```

Expected: no errors

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/app/admin/suppliers/[id]/page.tsx `
       frontend/src/app/admin/products/page.tsx `
       frontend/src/app/admin/rfps/page.tsx
git commit -m "feat: admin supplier detail, products, and rfps pages"
```

---

### Task 10: Backend Restart + Smoke Test

**Files:** none (runtime verification only)

- [ ] **Step 1: Kill any running backend and restart**

```powershell
# Stop previous process if any
Stop-Process -Name java -Force -ErrorAction SilentlyContinue

$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot"
$env:PATH = "C:\tools\maven\apache-maven-3.9.8\bin;$env:JAVA_HOME\bin;$env:PATH"
$env:JWT_SECRET = "J+es0i/POQ/cL2CaE5lfkO3q01fUt2C4N4iyHID7rXQglzZZHHQw/NZAu22ORe/v"
$env:RFP_LLM_API_KEY = (Get-Content D:\dev\RFP\rfp\.env | Select-String "RFP_LLM_API_KEY").ToString().Split("=",2)[1]
$env:RFP_LLM_PROVIDER = "anthropic"
$env:RFP_LLM_MODEL = "claude-sonnet-4-6"
Set-Location "D:\dev\RFP\rfp\backend"
Start-Process powershell -ArgumentList "-NoExit", "-Command", @"
  `$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot'
  `$env:PATH = 'C:\tools\maven\apache-maven-3.9.8\bin;' + `$env:JAVA_HOME + '\bin;' + `$env:PATH
  `$env:JWT_SECRET = 'J+es0i/POQ/cL2CaE5lfkO3q01fUt2C4N4iyHID7rXQglzZZHHQw/NZAu22ORe/v'
  Set-Location 'D:\dev\RFP\rfp\backend'
  & 'C:\tools\maven\apache-maven-3.9.8\bin\mvn.cmd' spring-boot:run
"@
```

Wait ~30s for startup, then:

- [ ] **Step 2: Smoke test — login**

```powershell
$resp = Invoke-RestMethod -Uri "http://localhost:8080/auth/login" `
  -Method POST -ContentType "application/json" `
  -Body '{"username":"admin","password":"Admin@1234"}'
$token = $resp.token
Write-Output "Token: $($token.Substring(0,20))..."
```

Expected: token string printed

- [ ] **Step 3: Smoke test — admin endpoints**

```powershell
$headers = @{ Authorization = "Bearer $token" }
$users = Invoke-RestMethod "http://localhost:8080/admin/users" -Headers $headers
Write-Output "Users: $($users.Count)"

$products = Invoke-RestMethod "http://localhost:8080/admin/products?size=5" -Headers $headers
Write-Output "Total products: $($products.totalElements)"

$tenders = Invoke-RestMethod "http://localhost:8080/admin/tenders" -Headers $headers
Write-Output "Tenders: $($tenders.Count)"
```

Expected: counts printed without errors

- [ ] **Step 4: Browser smoke test**

Open Chrome and test:
1. `http://localhost:3000` — should redirect to `/auth` (no token)
2. Login with `admin / Admin@1234` — should redirect to `/`
3. Navigate to `http://localhost:3000/admin` — should redirect to `/admin/suppliers`
4. Check `/admin/users`, `/admin/products`, `/admin/rfps` pages load without errors
5. On home page: type a new supplier name, click "Create", verify chip appears
6. Upload an RFP file, select a supplier, click Analyze RFP

- [ ] **Step 5: Final commit (if any fixes needed)**

```powershell
git add -A
git status  # review what changed
git commit -m "fix: smoke test corrections"
```
