'use client';
import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { api } from '@/src/lib/api';
import type { AdminProduct, Supplier } from '@/src/lib/types';
import { useAuth } from '@/src/hooks/useAuth';

const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

export default function SupplierDetailPage() {
  const { token } = useAuth();
  const { id } = useParams<{ id: string }>();
  const [supplier, setSupplier] = useState<Supplier | null>(null);
  const [products, setProducts] = useState<AdminProduct[]>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!token) return;
    // Fetch supplier info
    fetch(`${BASE}/suppliers/${id}`, { headers: { Authorization: `Bearer ${token}` } })
      .then(r => r.json())
      .then(setSupplier)
      .catch(e => setError(String(e)));
    // Fetch products filtered by supplier via admin products endpoint
    api.admin.listProducts(token, { size: 200, q: '' })
      .then(result => setProducts(result.content))
      .catch(e => setError(String(e)));
  }, [id, token]);

  if (!token) return null;

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

      <div>
        <h3 className="text-base font-medium text-slate-700 mb-3">Products</h3>
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
              {products.filter(p => p.supplierName === supplier?.name).map(p => (
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
      </div>
    </div>
  );
}
