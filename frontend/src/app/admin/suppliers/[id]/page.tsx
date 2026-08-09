'use client';
import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { api } from '@/src/lib/api';
import type { AdminProduct, IngestRecord, Supplier } from '@/src/lib/types';
import { useAuth } from '@/src/hooks/useAuth';

const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

export default function SupplierDetailPage() {
  const { token } = useAuth();
  const { id } = useParams<{ id: string }>();
  const [supplier, setSupplier] = useState<Supplier | null>(null);
  const [products, setProducts] = useState<AdminProduct[]>([]);
  const [ingests, setIngests] = useState<IngestRecord[]>([]);
  const [tab, setTab] = useState<'products' | 'ingests'>('products');
  const [error, setError] = useState('');

  useEffect(() => {
    if (!token) return;
    fetch(`${BASE}/suppliers/${id}`, { headers: { Authorization: `Bearer ${token}` } })
      .then(r => r.json())
      .then(setSupplier)
      .catch(e => setError(String(e)));
    api.admin.listProducts(token, { size: 200, q: '' })
      .then(result => setProducts(result.content))
      .catch(e => setError(String(e)));
    api.admin.listIngests(Number(id), token)
      .then(setIngests)
      .catch(e => setError(String(e)));
  }, [id, token]);

  if (!token) return null;

  const supplierId = Number(id);

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

      <div className="flex border-b border-slate-200 mb-4">
        {(['products', 'ingests'] as const).map(t => (
          <button key={t} onClick={() => setTab(t)}
            className={`px-4 py-2 text-sm font-medium capitalize transition-colors ${
              tab === t ? 'border-b-2 border-indigo-600 text-indigo-600' : 'text-slate-500 hover:text-slate-700'
            }`}>{t === 'ingests' ? 'Ingest History' : 'Products'}</button>
        ))}
      </div>

      {tab === 'products' && (
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
              {products.filter(p => p.supplierId === supplierId).map(p => (
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
      )}

      {tab === 'ingests' && (
        <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 border-b border-slate-200">
              <tr>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Kind</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Filename</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Status</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Started</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Finished</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {ingests.map(i => (
                <tr key={i.id} className="hover:bg-slate-50">
                  <td className="px-4 py-3 text-slate-700 font-mono text-xs">{i.kind}</td>
                  <td className="px-4 py-3 text-slate-500 text-xs">{i.filename ?? '—'}</td>
                  <td className="px-4 py-3 text-slate-700">{i.status}</td>
                  <td className="px-4 py-3 text-slate-500 text-xs">{i.startedAt ?? '—'}</td>
                  <td className="px-4 py-3 text-slate-500 text-xs">{i.finishedAt ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
