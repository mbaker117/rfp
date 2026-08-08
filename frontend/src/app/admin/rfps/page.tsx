'use client';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '@/src/lib/api';
import type { AdminTender } from '@/src/lib/types';
import { useAuth } from '@/src/hooks/useAuth';

function StatusBadge({ status }: { status: string }) {
  const cls = status === 'done'
    ? 'bg-emerald-100 text-emerald-700'
    : status === 'failed'
    ? 'bg-red-100 text-red-700'
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
