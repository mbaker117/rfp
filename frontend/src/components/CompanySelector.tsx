'use client';
import { useState } from 'react';
import type { Company } from '@/src/lib/types';

interface Props {
  companies: Company[];
  selected: number[];
  onToggle: (id: number) => void;
  onAddName: (name: string) => void;
}

export function CompanySelector({ companies, selected, onToggle, onAddName }: Props) {
  const [input, setInput] = useState('');

  return (
    <div className="space-y-2">
      <div className="flex gap-2">
        <input
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={e => {
            if (e.key === 'Enter' && input.trim()) {
              onAddName(input.trim());
              setInput('');
            }
          }}
          className="border rounded px-2 py-1 flex-1"
          placeholder="Add company name..."
        />
        <button
          onClick={() => {
            if (input.trim()) {
              onAddName(input.trim());
              setInput('');
            }
          }}
          className="bg-blue-600 text-white px-3 py-1 rounded hover:bg-blue-700 transition-colors"
        >
          Add
        </button>
      </div>
      {companies.map(c => (
        <label key={c.id} className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            checked={selected.includes(c.id)}
            onChange={() => onToggle(c.id)}
          />
          <span>{c.name}</span>
          <span
            className={`text-xs ${
              c.scrapeStatus === 'DONE' ? 'text-green-600' : 'text-yellow-600'
            }`}
          >
            {c.scrapeStatus}
          </span>
        </label>
      ))}
    </div>
  );
}
