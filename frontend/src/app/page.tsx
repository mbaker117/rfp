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
