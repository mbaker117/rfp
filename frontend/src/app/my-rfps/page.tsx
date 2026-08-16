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
    if (!token) { setLoading(false); return; }
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
