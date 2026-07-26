'use client';
import { useState, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import { FileUpload } from '@/src/components/FileUpload';
import SupplierSelector from '@/src/components/SupplierSelector';
import { JobStatusPoller } from '@/src/components/JobStatusPoller';
import { api } from '@/src/lib/api';

export default function Home() {
  const router = useRouter();
  const [token] = useState<string>(() =>
    typeof window !== 'undefined' ? (localStorage.getItem('token') ?? '') : '',
  );
  const [file, setFile] = useState<File | null>(null);
  const [selectedSupplierIds, setSelectedSupplierIds] = useState<number[]>([]);
  const [rfpId, setRfpId] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const submit = async () => {
    if (!file || selectedSupplierIds.length === 0) {
      setError('Select a file and at least one supplier');
      return;
    }
    setError('');
    setLoading(true);
    try {
      const { rfpId: id } = await api.uploadRfp(file, selectedSupplierIds, token);
      // Matching is auto-triggered server-side after extraction completes
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

  return (
    <main className="max-w-2xl mx-auto py-12 px-4 space-y-6">
      <h1 className="text-2xl font-bold">RFP Instrument Matching</h1>

      {error && (
        <p className="text-red-600 bg-red-50 border border-red-200 rounded px-3 py-2">
          {error}
        </p>
      )}

      {rfpId !== null ? (
        <JobStatusPoller
          rfpId={rfpId}
          token={token}
          onComplete={onComplete}
          onError={setError}
        />
      ) : (
        <>
          <FileUpload onFile={setFile} />
          {file && (
            <p className="text-sm text-gray-600">
              Selected: <span className="font-medium">{file.name}</span>
            </p>
          )}
          <SupplierSelector
            token={token}
            selected={selectedSupplierIds}
            onChange={setSelectedSupplierIds}
          />
          <button
            onClick={submit}
            disabled={loading}
            className="w-full bg-blue-600 text-white py-2 rounded font-semibold hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading ? 'Uploading...' : 'Analyze RFP'}
          </button>
        </>
      )}
    </main>
  );
}
