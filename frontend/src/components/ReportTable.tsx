'use client';
import { Fragment, useState } from 'react';
import type { MatchResultItem, AttributeVerdict, Alternative } from '@/src/lib/types';

// The backend stores attribute_verdicts / alternatives as jsonb and serializes
// them as raw JSON strings, so accept either a parsed array or a string.
function asArray<T>(value: T[] | string | null | undefined): T[] {
  if (Array.isArray(value)) return value;
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      return Array.isArray(parsed) ? (parsed as T[]) : [];
    } catch { return []; }
  }
  return [];
}

const show = (v: unknown) =>
  v === null || v === undefined ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v);

function StatusBadge({ status }: { status: MatchResultItem['status'] }) {
  const cls = {
    matched: 'bg-emerald-100 text-emerald-700',
    partial: 'bg-amber-100 text-amber-700',
    not_found: 'bg-slate-100 text-slate-600',
  }[status];
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${cls}`}>
      {status.replace('_', ' ')}
    </span>
  );
}

function ScoreBar({ score }: { score: number }) {
  const color = score >= 80 ? 'bg-emerald-500' : score >= 40 ? 'bg-amber-400' : 'bg-slate-300';
  return (
    <div className="flex items-center gap-2">
      <div className="w-16 h-1.5 bg-slate-100 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${score}%` }} />
      </div>
      <span className="text-xs text-slate-600">{score}</span>
    </div>
  );
}

function ExpandedRow({ item }: { item: MatchResultItem }) {
  const verdicts = asArray<AttributeVerdict>(item.attributeVerdicts);
  const alts = asArray<Alternative>(item.alternatives);
  return (
    <tr>
      <td colSpan={7} className="bg-slate-50 px-6 py-4 border-b border-slate-200">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {verdicts.length > 0 && (
            <div>
              <p className="text-xs font-medium uppercase tracking-wide text-slate-400 mb-2">Attribute Verdicts</p>
              <table className="w-full text-xs">
                <tbody>
                  {verdicts.map((v, i) => (
                    <tr key={`${v.attr}-${i}`} className="border-b border-slate-100 last:border-0">
                      <td className="py-1 pr-2 font-medium text-slate-700">{v.attr}</td>
                      <td className="py-1 pr-2 text-slate-500">req {show(v.required)}</td>
                      <td className="py-1 pr-2 text-slate-500">off {show(v.offered)}</td>
                      <td className={`py-1 font-medium ${
                        v.verdict === 'COMPLIANT' ? 'text-emerald-600'
                        : v.verdict === 'DEVIATION' ? 'text-red-600'
                        : 'text-slate-400'
                      }`}>{v.verdict}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {alts.length > 0 && (
            <div>
              <p className="text-xs font-medium uppercase tracking-wide text-slate-400 mb-2">Alternatives</p>
              <ul className="space-y-1">
                {alts.map(alt => (
                  <li key={alt.productId} className="text-xs text-slate-700">
                    {alt.name}
                    {alt.mpn && <span className="text-slate-400 ml-1">({alt.mpn})</span>}
                    <span className="ml-2 text-slate-500">score: {alt.score}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </td>
    </tr>
  );
}

export function ReportTable({ items }: { items: MatchResultItem[] }) {
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  const toggle = (id: number) =>
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });

  return (
    <div className="overflow-x-auto rounded-xl border border-slate-200">
      <table className="w-full text-sm">
        <thead className="bg-slate-50 border-b border-slate-200">
          <tr>
            <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Requirement</th>
            <th className="text-right px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Qty</th>
            <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Type</th>
            <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Score</th>
            <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Status</th>
            <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Matched Product</th>
            <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">MPN</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {items.map(item => (
            <Fragment key={item.lineId}>
              <tr
                onClick={() => toggle(item.lineId)}
                className="hover:bg-slate-50 cursor-pointer transition-colors"
              >
                <td className="px-4 py-3 text-slate-900 max-w-xs truncate">{item.description}</td>
                <td className="px-4 py-3 text-right text-slate-600">{item.qty ?? '—'}</td>
                <td className="px-4 py-3 text-center text-slate-500 capitalize">{item.matchType ?? '—'}</td>
                <td className="px-4 py-3"><ScoreBar score={item.score} /></td>
                <td className="px-4 py-3"><StatusBadge status={item.status} /></td>
                <td className="px-4 py-3 text-slate-700">{item.matchedProduct ?? '—'}</td>
                <td className="px-4 py-3 text-slate-500 font-mono text-xs">{item.mpn ?? '—'}</td>
              </tr>
              {expanded.has(item.lineId) && <ExpandedRow item={item} />}
            </Fragment>
          ))}
        </tbody>
      </table>
    </div>
  );
}
