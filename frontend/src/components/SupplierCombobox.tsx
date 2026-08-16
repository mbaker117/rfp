'use client';
import { useEffect, useState, useRef } from 'react';
import { api } from '@/src/lib/api';
import type { Supplier } from '@/src/lib/types';

export interface SelectedSupplier {
  id: number;
  name: string;
  catalogFile: File | null;
}

type SupplierState = 'checking' | 'ok' | 'empty' | 'scraping' | 'scraped';

interface Props {
  token: string;
  onChange: (selected: SelectedSupplier[]) => void;
  defaultSelectedIds?: number[];
}

export function SupplierCombobox({ token, onChange, defaultSelectedIds }: Props) {
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [selected, setSelected] = useState<SelectedSupplier[]>([]);
  const [supplierStates, setSupplierStates] = useState<Record<number, SupplierState>>({});
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (token) {
      api.listSuppliers(token).then(all => {
        setSuppliers(all);
        if (defaultSelectedIds !== undefined) {
          const preSelected = all
            .filter(s => defaultSelectedIds.includes(s.id))
            .map(s => ({ id: s.id, name: s.name, catalogFile: null }));
          setSelected(preSelected);
          onChange(preSelected);
        }
      }).catch(() => {});
    }
  }, [token]); // eslint-disable-line react-hooks/exhaustive-deps

  const filtered = suppliers.filter(
    s =>
      s.name.toLowerCase().includes(query.toLowerCase()) &&
      !selected.some(sel => sel.id === s.id)
  );

  const isExact = suppliers.some(
    s => s.name.toLowerCase() === query.trim().toLowerCase()
  );
  const showCreate = query.trim().length > 0 && !isExact;

  const addSupplier = (sup: SelectedSupplier) => {
    const next = [...selected, sup];
    setSelected(next);
    onChange(next);
    setQuery('');
    setOpen(false);
    inputRef.current?.focus();
  };

  const selectExisting = (s: Supplier) => {
    addSupplier({ id: s.id, name: s.name, catalogFile: null });
    // Async product count check
    setSupplierStates(prev => ({ ...prev, [s.id]: 'checking' }));
    api.admin.listProducts(token, { supplierId: s.id, size: 1 })
      .then(result => {
        setSupplierStates(prev => ({
          ...prev,
          [s.id]: result.totalElements === 0 ? 'empty' : 'ok',
        }));
      })
      .catch(() => {
        setSupplierStates(prev => ({ ...prev, [s.id]: 'ok' }));
      });
  };

  const triggerScrape = async (supplierId: number) => {
    setSupplierStates(prev => ({ ...prev, [supplierId]: 'scraping' }));
    try {
      await api.triggerScrape(supplierId, token);
      setSupplierStates(prev => ({ ...prev, [supplierId]: 'scraped' }));
    } catch {
      setSupplierStates(prev => ({ ...prev, [supplierId]: 'empty' }));
    }
  };

  const createNew = async () => {
    const name = query.trim();
    if (!name) return;
    setCreating(true);
    try {
      const created = await api.registerSupplier({ name }, token);
      setSuppliers(prev => [...prev, created]);
      addSupplier({ id: created.id, name: created.name, catalogFile: null });
    } catch {
      // ignore
    } finally {
      setCreating(false);
    }
  };

  const remove = (id: number) => {
    const next = selected.filter(s => s.id !== id);
    setSelected(next);
    onChange(next);
    setSupplierStates(prev => {
      const copy = { ...prev };
      delete copy[id];
      return copy;
    });
  };

  const setCatalog = (id: number, file: File | null) => {
    const next = selected.map(s => (s.id === id ? { ...s, catalogFile: file } : s));
    setSelected(next);
    onChange(next);
  };

  return (
    <div className="space-y-3">
      <label className="block text-xs font-medium uppercase tracking-wide text-slate-500">
        Suppliers
      </label>

      {/* Selected chips */}
      {selected.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {selected.map(s => {
            const state = supplierStates[s.id];
            return (
              <div key={s.id} className="space-y-1">
                <div className="flex items-center gap-1 bg-indigo-50 border border-indigo-200 rounded-full px-3 py-1 text-sm">
                  <span className="font-medium text-indigo-800">{s.name}</span>
                  <label className="cursor-pointer text-indigo-500 hover:text-indigo-700 text-xs ml-1">
                    {s.catalogFile ? (
                      <span title={s.catalogFile.name}>📎</span>
                    ) : (
                      <span>+ Catalog</span>
                    )}
                    <input
                      type="file"
                      accept=".pdf,.doc,.docx,.xls,.xlsx"
                      className="hidden"
                      onChange={e => setCatalog(s.id, e.target.files?.[0] ?? null)}
                    />
                  </label>
                  {s.catalogFile && (
                    <button
                      onClick={() => setCatalog(s.id, null)}
                      className="text-indigo-400 hover:text-indigo-600 text-xs"
                      title="Remove catalog"
                    >✕</button>
                  )}
                  <button
                    onClick={() => remove(s.id)}
                    className="text-indigo-400 hover:text-red-500 ml-1 text-xs"
                  >✕</button>
                </div>

                {/* Empty supplier warning */}
                {state === 'empty' && (
                  <div className="flex items-center gap-2 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2 text-xs">
                    <span className="text-amber-700">No products found for this supplier.</span>
                    <button
                      onClick={() => triggerScrape(s.id)}
                      className="text-xs bg-amber-600 text-white px-2 py-0.5 rounded hover:bg-amber-700 transition-colors"
                    >
                      Trigger Scrape
                    </button>
                  </div>
                )}
                {state === 'scraping' && (
                  <p className="text-xs text-amber-600 px-1">Scraping in progress… check admin portal for status.</p>
                )}
                {state === 'scraped' && (
                  <p className="text-xs text-emerald-600 px-1">Scrape started — products will appear once complete.</p>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* Combobox input */}
      <div className="relative">
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={e => { setQuery(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 150)}
          placeholder="Search or add supplier..."
          className="w-full border border-slate-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
        {open && (filtered.length > 0 || showCreate) && (
          <ul className="absolute z-10 mt-1 w-full bg-white border border-slate-200 rounded-md shadow-lg max-h-48 overflow-auto">
            {filtered.map(s => (
              <li
                key={s.id}
                onMouseDown={() => selectExisting(s)}
                className="px-3 py-2 text-sm cursor-pointer hover:bg-indigo-50 text-slate-800"
              >
                {s.name}
              </li>
            ))}
            {showCreate && (
              <li
                onMouseDown={createNew}
                className="px-3 py-2 text-sm cursor-pointer hover:bg-indigo-50 text-indigo-700 font-medium"
              >
                {creating ? 'Creating...' : `Create "${query.trim()}"`}
              </li>
            )}
          </ul>
        )}
      </div>
    </div>
  );
}
