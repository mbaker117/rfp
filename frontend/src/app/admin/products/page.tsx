'use client';
import { useEffect, useState } from 'react';
import { api } from '@/src/lib/api';
import type { AdminProduct, Supplier } from '@/src/lib/types';
import { useAuth } from '@/src/hooks/useAuth';

export default function AdminProductsPage() {
  const { token } = useAuth();
  const [products, setProducts] = useState<AdminProduct[]>([]);
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [total, setTotal] = useState(0);
  const [q, setQ] = useState('');
  const [supplierId, setSupplierId] = useState<number | ''>('');
  const [page, setPage] = useState(0);
  const [error, setError] = useState('');
  const SIZE = 50;

  useEffect(() => {
    if (!token) return;
    api.listSuppliers(token).then(setSuppliers).catch(() => {});
  }, [token]);

  useEffect(() => {
    if (!token) return;
    api.admin.listProducts(token, {
      page, size: SIZE, q: q || undefined,
      supplierId: supplierId !== '' ? supplierId : undefined,
    })
      .then(r => { setProducts(r.content); setTotal(r.totalElements); })
      .catch(e => setError(String(e)));
  }, [token, page, q, supplierId]);

  const pages = Math.ceil(total / SIZE);

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <h2 className="text-xl font-semibold text-slate-900">
          Products <span className="text-slate-400 font-normal text-base">({total})</span>
        </h2>
        <div className="flex gap-2">
          <select
            value={supplierId}
            onChange={e => { setSupplierId(e.target.value === '' ? '' : Number(e.target.value)); setPage(0); }}
            className="border border-slate-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
          >
            <option value="">All suppliers</option>
            {suppliers.map(s => (
              <option key={s.id} value={s.id}>{s.name}</option>
            ))}
          </select>
          <input
            value={q}
            onChange={e => { setQ(e.target.value); setPage(0); }}
            placeholder="Search by name or MPN…"
            className="border border-slate-300 rounded-md px-3 py-2 text-sm w-56 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
        </div>
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
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Source</th>
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
                  {p.source && (
                    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
                      p.source === 'scrape' ? 'bg-blue-100 text-blue-700' : 'bg-violet-100 text-violet-700'
                    }`}>{p.source === 'scrape' ? 'web' : 'upload'}</span>
                  )}
                </td>
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
          <button
            onClick={() => setPage(p => Math.max(0, p - 1))}
            disabled={page === 0}
            className="text-sm px-3 py-1.5 border border-slate-300 rounded-md text-slate-600 hover:bg-slate-50 disabled:opacity-40"
          >
            Prev
          </button>
          <span className="text-sm text-slate-500">{page + 1} / {pages}</span>
          <button
            onClick={() => setPage(p => Math.min(pages - 1, p + 1))}
            disabled={page >= pages - 1}
            className="text-sm px-3 py-1.5 border border-slate-300 rounded-md text-slate-600 hover:bg-slate-50 disabled:opacity-40"
          >
            Next
          </button>
        </div>
      )}
    </div>
  );
}
