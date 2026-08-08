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
