'use client';
import { useEffect, useState } from 'react';
import type { Supplier } from '../lib/types';
import { api } from '../lib/api';

interface Props {
  token: string;
  selected: number[];
  onChange: (ids: number[]) => void;
}

export default function SupplierSelector({ token, selected, onChange }: Props) {
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.listSuppliers(token)
      .then(setSuppliers)
      .finally(() => setLoading(false));
  }, [token]);

  function toggle(id: number) {
    onChange(selected.includes(id) ? selected.filter(s => s !== id) : [...selected, id]);
  }

  if (loading) return <p className="text-sm text-gray-500">Loading suppliers…</p>;

  return (
    <div className="space-y-2">
      <p className="text-sm font-medium">Select suppliers to match against:</p>
      {suppliers.map(s => (
        <label key={s.id} className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            checked={selected.includes(s.id)}
            onChange={() => toggle(s.id)}
          />
          <span>{s.name}</span>
          {s.scrapeStatus === 'PENDING' && (
            <span className="text-xs text-yellow-600">(no catalog yet — will scrape)</span>
          )}
        </label>
      ))}
      {suppliers.length === 0 && (
        <p className="text-sm text-gray-400">No suppliers registered yet.</p>
      )}
    </div>
  );
}
