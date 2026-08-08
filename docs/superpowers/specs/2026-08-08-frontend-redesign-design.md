# Frontend Redesign — Design Spec
**Date:** 2026-08-08  
**Status:** Approved

---

## 1. Goals

1. Add login/register UI so users can authenticate (fixes 403 on every API call)
2. Allow free-text supplier creation inline in the upload flow
3. Allow optional per-supplier catalog upload alongside the RFP
4. Redesign all pages with a clean enterprise/SaaS aesthetic (Linear/Vercel style)
5. Build a full admin portal covering users, suppliers, products, and RFP history

---

## 2. Design System

### Palette
| Role | Value |
|---|---|
| Background | `slate-50` / white |
| Surface | white + `border-slate-200` |
| Text primary | `slate-900` |
| Text secondary | `slate-500` |
| Accent | `indigo-600` |
| Success | `emerald-600` |
| Warning | `amber-500` |
| Danger | `red-600` |
| Sidebar bg | `slate-900` |

### Typography
- Font: `Inter` via `next/font/google`
- Base: `text-sm`
- Headings: `font-semibold`
- Labels: `text-xs uppercase tracking-wide text-slate-500`

### Component Patterns
- **Tables:** full-width, `text-sm`, hover highlight, sticky header
- **Buttons:** `indigo-600` primary · `slate-100` secondary · `red-50 text-red-600` danger
- **Status badges:** pill-shaped — `matched` → emerald · `partial` → amber · `not_found` → slate · `done` → emerald · `failed` → red
- **Inputs:** `border-slate-300 rounded-md`, focus ring `indigo-500`
- **Sidebar:** `slate-900` bg, active item has `indigo-500` left border + `white` text

### Layouts
- **Main app:** centered column, `max-w-3xl mx-auto`
- **Admin:** fixed left sidebar 240px + scrollable content area

---

## 3. Routing

```
/auth                    public — login / register
/                        protected — RFP upload flow
/rfp/[id]               protected — report viewer
/admin                   protected — redirect → /admin/suppliers
/admin/suppliers         protected — supplier list + create
/admin/suppliers/[id]    protected — supplier detail (products + ingest history)
/admin/products          protected — product browser (read-only)
/admin/users             protected — user management
/admin/rfps              protected — RFP / tender history
```

### Auth Guard
A `useAuth()` hook reads the JWT from `localStorage`. If no token exists, it redirects to `/auth?next=<current-path>`. After successful login/register, the token is saved and the user is sent to `next` (defaulting to `/`).

---

## 4. Auth Page (`/auth`)

Single centered card with two tabs: **Login** and **Register**.

- Fields: username, password (register repeats password for confirmation)
- On success: `localStorage.setItem('token', token)` → redirect to `next` or `/`
- On error: inline message below the form
- No email, no OAuth — matches the backend's simple username/password model
- Credentials for seeded users: `admin / Admin@1234`, `testuser / Test@1234`

---

## 5. Home Page — Upload Flow (`/`)

Three-step card layout inside `max-w-3xl`:

### Step 1 — Upload RFP
Drag-and-drop zone accepting PDF / Word / Excel. Shows filename after selection.

### Step 2 — Select Suppliers
**Combobox** replaces the current checkbox list:
- Fetches `GET /suppliers` on mount, populates dropdown
- User can type to filter existing suppliers
- If typed value matches no existing supplier, shows **"Create '[name]'"** option
- Selecting "Create" calls `POST /suppliers { name }` → adds the returned ID to selection
- Selected suppliers rendered as chips: `[Supplier Name ✕]`
- Each chip has an optional **[+ Upload Catalog]** button
  - Clicking opens a file picker (PDF/Word/Excel)
  - Selected catalog file shown as `[📎 filename ✕]` next to the chip
  - Catalog is uploaded via `POST /suppliers/{id}/catalog/upload` before the RFP submit

### Step 3 — Analyze
**[Analyze RFP]** button:
1. Uploads any pending supplier catalogs (parallel `POST /suppliers/{id}/catalog/upload`)
2. Calls `POST /rfp/upload` with file + supplierIds
3. Shows `JobStatusPoller` while processing
4. On completion → redirects to `/rfp/{id}`

Validation: file required + at least one supplier selected.

---

## 6. Report Page (`/rfp/[id]`)

**Header bar:** RFP filename · status pill · export buttons (XLSX, PDF → `GET /rfp/{id}/report/export?format=`)

**Summary row:** three chips — `N matched (emerald)` · `N partial (amber)` · `N not found (slate)`

**ReportTable** (existing component, restyled):
- Columns: Item description · Qty · Match type · Score bar · Status badge · Matched product · MPN
- Expandable row → shows attribute verdicts table + alternatives list

---

## 7. Admin Portal

### Shared Layout (`AdminLayout`)
- Fixed dark sidebar (`slate-900`, 240px) with logo at top
- Nav items: Users · Suppliers · Products · RFPs · separator · ← Back to App
- Active item: `indigo-500` left border
- Content area: white, scrollable, `p-8`

### `/admin/users`
Table: ID · Username · Created · Actions  
Row action: **Disable** (calls `DELETE /admin/users/{id}`)  
No password reset, no role assignment (out of scope).

### `/admin/suppliers`
Table: Name · Website · Scrape Status badge · Last Scraped · Categories  
Row actions: **Edit** (slide-over drawer) · **Trigger Scrape** (`POST /suppliers/{id}/catalog/scrape`) · **Upload Catalog** (file picker → `POST /suppliers/{id}/catalog/upload`)  
**[+ New Supplier]** button top-right opens create drawer.

### `/admin/suppliers/[id]`
Supplier info card at top, then two tabs:
- **Products** — paginated table: Name · MPN · Class · Stale badge
- **Ingest History** — table of past catalog ingests (kind · status · started · finished)

### `/admin/products`
Read-only paginated table: Name · MPN · Supplier · Product Class · Stale badge  
Search by name or MPN (`?q=`). No edit — products come from ingestion only.

### `/admin/rfps`
Table: ID · Filename · User · Status badge · Created  
Click row → navigates to `/rfp/{id}`.

---

## 8. New Backend Endpoints

All under `AdminController`, all require valid JWT (same guard as existing endpoints):

| Method | Path | Response |
|---|---|---|
| `GET` | `/admin/users` | `[{id, username, createdAt}]` |
| `DELETE` | `/admin/users/{id}` | `204 No Content` |
| `GET` | `/admin/products?page=0&size=50&q=` | `{content:[...], totalElements}` |
| `GET` | `/admin/tenders` | `[{id, filename, userId, status, createdAt}]` |

---

## 9. New Frontend Files

```
src/
├── app/
│   ├── auth/page.tsx              NEW — login/register
│   ├── admin/
│   │   ├── layout.tsx             NEW — AdminLayout with sidebar
│   │   ├── page.tsx               NEW — redirect to /admin/suppliers
│   │   ├── suppliers/
│   │   │   ├── page.tsx           NEW
│   │   │   └── [id]/page.tsx      NEW
│   │   ├── products/page.tsx      NEW
│   │   ├── users/page.tsx         NEW
│   │   └── rfps/page.tsx          NEW
├── components/
│   ├── SupplierCombobox.tsx       NEW — replaces SupplierSelector
│   ├── AdminLayout.tsx            NEW — sidebar shell (or colocated in app/admin/layout.tsx)
│   └── DataTable.tsx              NEW — reusable sortable table
├── hooks/
│   └── useAuth.ts                 NEW — token read + redirect guard
└── lib/
    └── api.ts                     UPDATED — add admin endpoints
```

---

## 10. Out of Scope

- Role-based access (all logged-in users can reach `/admin` for now — CLAUDE.md notes this as a known limitation)
- Password reset / email verification
- Pagination on supplier list (count expected to be small)
- Real-time log streaming
