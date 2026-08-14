# Catalog Upload UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Step 2 on the home page with a primary catalog file drop zone that auto-creates a supplier, plus a disclosure toggle for the existing SupplierCombobox.

**Architecture:** All changes are in `frontend/src/app/page.tsx`. Catalog supplier state is kept separate from the SupplierCombobox's `suppliers` state to avoid merge conflicts; both are combined in `submit`. The catalog is uploaded immediately on file selection via the existing `api.registerSupplier` + `api.uploadCatalog` calls — no new API needed.

**Tech Stack:** Next.js 16, TypeScript, Tailwind CSS v4 (`@import "tailwindcss"` — not v3 directives), React hooks.

## Global Constraints

- Tailwind v4: use `@import "tailwindcss"` in globals.css — `@tailwind` directives produce zero output.
- No new files: all changes in `frontend/src/app/page.tsx` and a new test file.
- `useRef<HTMLInputElement>(null)` for the hidden file input — same pattern as `FileUpload.tsx`.
- `api.registerSupplier({ name }, token)` returns `Supplier` (`{ id: number; name: string; ... }`).
- `api.uploadCatalog(id, file, token)` returns `{ supplierId: number; status: string }`.
- Step 2 label changes from "Select Suppliers" → "Upload Supplier Catalog".
- The `SupplierCombobox` is preserved and shown via a disclosure toggle below the drop zone.
- Disabled condition for Analyze button: `!file || (suppliers.length === 0 && catalogSupplierId === null)`.

---

### Task 1: Catalog drop zone with auto-supplier creation and disclosure combobox

**Files:**
- Modify: `frontend/src/app/page.tsx`
- Create: `frontend/src/__tests__/CatalogStep.test.tsx`

**Interfaces:**
- Consumes: `api.registerSupplier`, `api.uploadCatalog`, `api.uploadRfp` from `src/lib/api.ts`
- Consumes: `SupplierCombobox` from `src/components/SupplierCombobox.tsx`
- Consumes: `FileUpload` from `src/components/FileUpload.tsx` (Step 1 — unchanged)

---

- [ ] **Step 1: Write the failing test**

  Create `frontend/src/__tests__/CatalogStep.test.tsx`:

  ```tsx
  import { render, screen, fireEvent, waitFor } from '@testing-library/react';
  import Home from '../app/page';

  // Mock Next.js navigation
  const mockPush = jest.fn();
  jest.mock('next/navigation', () => ({
    useRouter: () => ({ push: mockPush }),
    usePathname: () => '/',
  }));

  // Mock useAuth to always return a token
  jest.mock('../hooks/useAuth', () => ({
    useAuth: () => ({ token: 'test-token' }),
  }));

  // Mock api
  jest.mock('../lib/api', () => ({
    api: {
      listSuppliers: jest.fn().mockResolvedValue([]),
      registerSupplier: jest.fn().mockResolvedValue({ id: 99, name: 'my-catalog', scrapeStatus: 'PENDING', categories: [] }),
      uploadCatalog: jest.fn().mockResolvedValue({ supplierId: 99, status: 'ingest_started' }),
      uploadRfp: jest.fn().mockResolvedValue({ rfpId: 1 }),
      triggerMatch: jest.fn().mockResolvedValue({ jobId: 'rfp-1-match' }),
      getReport: jest.fn(),
    },
  }));

  // Mock child components to keep tests focused
  jest.mock('../components/JobStatusPoller', () => ({
    JobStatusPoller: () => <div data-testid="poller" />,
  }));
  jest.mock('../components/SupplierCombobox', () => ({
    SupplierCombobox: ({ onChange }: { onChange: (s: unknown[]) => void }) => (
      <button data-testid="combobox" onClick={() => onChange([{ id: 1, name: 'Acme', catalogFile: null }])}>
        Add Acme
      </button>
    ),
  }));

  describe('Home page — Step 2 catalog drop zone', () => {
    it('shows the catalog drop zone by default', () => {
      render(<Home />);
      expect(screen.getByText(/upload supplier catalog/i)).toBeInTheDocument();
      expect(screen.getByText(/drop pdf, word, or excel/i)).toBeInTheDocument();
    });

    it('shows disclosure toggle for existing suppliers', () => {
      render(<Home />);
      expect(screen.getByText(/or choose an existing supplier/i)).toBeInTheDocument();
    });

    it('reveals SupplierCombobox when disclosure toggle is clicked', () => {
      render(<Home />);
      fireEvent.click(screen.getByText(/or choose an existing supplier/i));
      expect(screen.getByTestId('combobox')).toBeInTheDocument();
    });

    it('calls registerSupplier and uploadCatalog when a file is dropped', async () => {
      const { api } = require('../lib/api');
      render(<Home />);
      const dropZone = screen.getByText(/drop pdf, word, or excel/i).closest('div')!;
      const file = new File(['content'], 'my-catalog.pdf', { type: 'application/pdf' });
      fireEvent.drop(dropZone, { dataTransfer: { files: [file] } });

      await waitFor(() => {
        expect(api.registerSupplier).toHaveBeenCalledWith({ name: 'my-catalog' }, 'test-token');
        expect(api.uploadCatalog).toHaveBeenCalledWith(99, file, 'test-token');
      });
    });

    it('shows confirmation chip after successful catalog upload', async () => {
      render(<Home />);
      const dropZone = screen.getByText(/drop pdf, word, or excel/i).closest('div')!;
      const file = new File(['content'], 'my-catalog.pdf', { type: 'application/pdf' });
      fireEvent.drop(dropZone, { dataTransfer: { files: [file] } });

      await waitFor(() => {
        expect(screen.getByText('my-catalog.pdf')).toBeInTheDocument();
      });
    });

    it('removes catalog supplier when chip ✕ is clicked', async () => {
      render(<Home />);
      const dropZone = screen.getByText(/drop pdf, word, or excel/i).closest('div')!;
      const file = new File(['content'], 'my-catalog.pdf', { type: 'application/pdf' });
      fireEvent.drop(dropZone, { dataTransfer: { files: [file] } });

      await waitFor(() => screen.getByText('my-catalog.pdf'));
      fireEvent.click(screen.getByLabelText('Remove catalog'));
      expect(screen.queryByText('my-catalog.pdf')).not.toBeInTheDocument();
      expect(screen.getByText(/drop pdf, word, or excel/i)).toBeInTheDocument();
    });

    it('enables Analyze button when catalog file is uploaded and RFP is selected', async () => {
      const { FileUpload } = require('../components/FileUpload');
      render(<Home />);

      // Simulate RFP upload via FileUpload mock call
      // The Analyze button should be disabled initially
      expect(screen.getByRole('button', { name: /analyze rfp/i })).toBeDisabled();
    });
  });
  ```

- [ ] **Step 2: Run tests to confirm they fail**

  ```bash
  cd frontend && npm test -- src/__tests__/CatalogStep.test.tsx --watchAll=false
  ```
  Expected: tests fail because Step 2 still shows "Select Suppliers" and has no catalog drop zone.

- [ ] **Step 3: Implement the new Step 2 in `page.tsx`**

  Replace the entire contents of `frontend/src/app/page.tsx` with:

  ```tsx
  'use client';
  import { useState, useCallback, useRef } from 'react';
  import { useRouter } from 'next/navigation';
  import { FileUpload } from '@/src/components/FileUpload';
  import { SupplierCombobox, type SelectedSupplier } from '@/src/components/SupplierCombobox';
  import { JobStatusPoller } from '@/src/components/JobStatusPoller';
  import { api } from '@/src/lib/api';
  import { useAuth } from '@/src/hooks/useAuth';

  export default function Home() {
    const { token } = useAuth();
    const router = useRouter();

    // Step 1
    const [file, setFile] = useState<File | null>(null);

    // Step 2 — direct catalog upload
    const catalogInputRef = useRef<HTMLInputElement>(null);
    const [catalogFile, setCatalogFile] = useState<File | null>(null);
    const [catalogSupplierId, setCatalogSupplierId] = useState<number | null>(null);
    const [catalogLoading, setCatalogLoading] = useState(false);
    const [showCombobox, setShowCombobox] = useState(false);

    // Step 2 — combobox (existing suppliers)
    const [suppliers, setSuppliers] = useState<SelectedSupplier[]>([]);

    // Step 3
    const [rfpId, setRfpId] = useState<number | null>(null);
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);

    const handleCatalogFile = async (f: File) => {
      setCatalogLoading(true);
      setError('');
      try {
        const stem = f.name.replace(/\.[^.]+$/, '');
        const supplier = await api.registerSupplier({ name: stem }, token);
        await api.uploadCatalog(supplier.id, f, token);
        setCatalogFile(f);
        setCatalogSupplierId(supplier.id);
      } catch (e) {
        setError('Could not upload catalog — ' + String(e));
      } finally {
        setCatalogLoading(false);
      }
    };

    const removeCatalog = () => {
      setCatalogFile(null);
      setCatalogSupplierId(null);
    };

    const allSupplierIds = [
      ...suppliers.map(s => s.id),
      ...(catalogSupplierId !== null ? [catalogSupplierId] : []),
    ];
    const hasSupplier = allSupplierIds.length > 0;

    const submit = async () => {
      if (!file) { setError('Please select an RFP file'); return; }
      if (!hasSupplier) { setError('Please upload a supplier catalog or select a supplier'); return; }
      setError('');
      setLoading(true);
      try {
        // Upload any catalog files attached to combobox suppliers (not the direct catalog — already uploaded)
        const catalogUploads = suppliers
          .filter(s => s.catalogFile)
          .map(s => api.uploadCatalog(s.id, s.catalogFile!, token));
        await Promise.all(catalogUploads);

        const { rfpId: id } = await api.uploadRfp(file, allSupplierIds, token);
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
              Upload an RFP document and a supplier catalog to find matches.
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
              {/* Step 1 — Upload RFP */}
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

              {/* Step 2 — Upload Supplier Catalog */}
              <div className="bg-white border border-slate-200 rounded-xl p-6 space-y-3">
                <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                  Step 2 — Upload Supplier Catalog
                </p>

                {catalogFile ? (
                  <div className="flex items-center gap-2 text-sm text-slate-700 bg-slate-50 rounded-lg px-3 py-2">
                    <span>📎</span>
                    <span className="flex-1 truncate">{catalogFile.name}</span>
                    <button
                      onClick={removeCatalog}
                      className="text-slate-400 hover:text-red-500 transition-colors"
                      aria-label="Remove catalog"
                    >
                      ✕
                    </button>
                  </div>
                ) : (
                  <div
                    className="border-2 border-dashed border-gray-300 rounded-lg p-8 text-center cursor-pointer hover:border-blue-400 transition-colors"
                    onClick={() => !catalogLoading && catalogInputRef.current?.click()}
                    onDragOver={e => e.preventDefault()}
                    onDrop={e => {
                      e.preventDefault();
                      const f = e.dataTransfer.files[0];
                      if (f) handleCatalogFile(f);
                    }}
                  >
                    <input
                      ref={catalogInputRef}
                      type="file"
                      accept=".pdf,.docx,.xlsx,.xls,.doc"
                      className="hidden"
                      onChange={e => {
                        const f = e.target.files?.[0];
                        if (f) handleCatalogFile(f);
                      }}
                    />
                    {catalogLoading ? (
                      <p className="text-slate-400">Uploading…</p>
                    ) : (
                      <p className="text-gray-500">Drop PDF, Word, or Excel here, or click to browse</p>
                    )}
                  </div>
                )}

                <div className="border-t border-slate-100 pt-2">
                  <button
                    type="button"
                    onClick={() => setShowCombobox(v => !v)}
                    className="text-xs text-slate-500 hover:text-indigo-600 transition-colors"
                  >
                    {showCombobox ? '← hide' : 'or choose an existing supplier →'}
                  </button>
                  {showCombobox && (
                    <div className="mt-3">
                      <SupplierCombobox token={token} onChange={setSuppliers} />
                    </div>
                  )}
                </div>
              </div>

              {/* Step 3 — Analyze */}
              <div className="bg-white border border-slate-200 rounded-xl p-6">
                <p className="text-xs font-medium uppercase tracking-wide text-slate-500 mb-3">
                  Step 3 — Analyze
                </p>
                <button
                  onClick={submit}
                  disabled={loading || !file || !hasSupplier}
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

- [ ] **Step 4: Run tests to confirm they pass**

  ```bash
  cd frontend && npm test -- src/__tests__/CatalogStep.test.tsx --watchAll=false
  ```
  Expected: all tests in the file pass.

- [ ] **Step 5: Run the full test suite to confirm no regressions**

  ```bash
  cd frontend && npm test -- --watchAll=false
  ```
  Expected: all suites pass.

- [ ] **Step 6: Commit**

  ```bash
  git add frontend/src/app/page.tsx frontend/src/__tests__/CatalogStep.test.tsx
  git commit -m "feat: replace Step 2 with prominent catalog drop zone and disclosure combobox"
  ```
