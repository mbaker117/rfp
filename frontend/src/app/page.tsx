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
                  data-testid="catalog-drop-zone"
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
