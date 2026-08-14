'use client';
import { Fragment, useState, useCallback, useEffect } from 'react';
import type { Proposal, ProposalLine, ProposalAlternative, ProductSearchResult } from '@/src/lib/types';
import { api } from '@/src/lib/api';

function AcceptBar({ value }: { value: number | null }) {
  if (value === null) return <span className="text-xs text-slate-400 animate-pulse">computing…</span>;
  const color = value >= 70 ? 'bg-emerald-500' : value >= 40 ? 'bg-amber-400' : 'bg-red-400';
  return (
    <div className="flex items-center gap-2">
      <div className="w-16 h-1.5 bg-slate-100 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${value}%` }} />
      </div>
      <span className="text-xs text-slate-600">{value.toFixed(0)}%</span>
    </div>
  );
}

function ScoreBar({ value }: { value: number | null }) {
  if (value === null) return <span className="text-xs text-slate-400">—</span>;
  const color = value >= 80 ? 'bg-emerald-500' : value >= 40 ? 'bg-amber-400' : 'bg-slate-300';
  return (
    <div className="flex items-center gap-2">
      <div className="w-16 h-1.5 bg-slate-100 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${value}%` }} />
      </div>
      <span className="text-xs text-slate-600">{value.toFixed(0)}</span>
    </div>
  );
}

function AlternativeCard({
  alt,
  onSelect,
}: {
  alt: ProposalAlternative | ProductSearchResult;
  onSelect: () => void;
}) {
  return (
    <div
      onClick={onSelect}
      className="border border-slate-200 rounded-lg p-3 cursor-pointer hover:border-indigo-400 hover:bg-indigo-50 transition-colors"
    >
      <p className="text-sm font-medium text-slate-900">{alt.name}</p>
      {alt.mpn && <p className="text-xs text-slate-400 font-mono">{alt.mpn}</p>}
      <div className="flex items-center gap-3 mt-1 text-xs text-slate-500">
        <span>Score: {alt.score}</span>
        {alt.price !== null && alt.price !== undefined && (
          <span>{alt.price} {alt.currency}</span>
        )}
        <span>{alt.supplierName}</span>
      </div>
      {'description' in alt && alt.description && (
        <p className="text-xs text-slate-500 mt-1 italic">{alt.description}</p>
      )}
    </div>
  );
}

interface ChangePanelProps {
  rfpId: number;
  proposalId: number;
  line: ProposalLine;
  token: string;
  onOverride: (lineId: number, productId: number) => void;
}

function ChangePanel({ rfpId, proposalId, line, token, onOverride }: ChangePanelProps) {
  const [q, setQ] = useState('');
  const [searchResults, setSearchResults] = useState<ProductSearchResult[]>([]);
  const [searching, setSearching] = useState(false);

  const handleSearch = async (query: string) => {
    setQ(query);
    if (query.length < 2) { setSearchResults([]); return; }
    setSearching(true);
    try {
      const results = await api.proposals.search(rfpId, proposalId, line.lineId, query, token);
      setSearchResults(results);
    } finally { setSearching(false); }
  };

  return (
    <div className="p-4 space-y-3">
      {line.alternatives.length > 0 && (
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-slate-400 mb-2">
            Pre-computed alternatives
          </p>
          <div className="space-y-2">
            {line.alternatives.map(alt => (
              <AlternativeCard
                key={alt.productId}
                alt={alt}
                onSelect={() => onOverride(line.lineId, alt.productId)}
              />
            ))}
          </div>
        </div>
      )}
      <div>
        <p className="text-xs font-medium uppercase tracking-wide text-slate-400 mb-2">
          Search all supplier products
        </p>
        <input
          value={q}
          onChange={e => handleSearch(e.target.value)}
          placeholder="Search by name or MPN…"
          className="border border-slate-300 rounded-md px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
        {searching && <p className="text-xs text-slate-400 mt-1">Searching…</p>}
        {searchResults.length > 0 && (
          <div className="mt-2 space-y-2">
            {searchResults.map(r => (
              <AlternativeCard
                key={r.productId}
                alt={r}
                onSelect={() => onOverride(line.lineId, r.productId)}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

interface Props {
  rfpId: number;
  proposal: Proposal;
  token: string;
  onProposalChange: () => void;
}

export function ProposalDetail({ rfpId, proposal, token, onProposalChange }: Props) {
  const [expandedLineId, setExpandedLineId] = useState<number | null>(null);
  const [changingLineId, setChangingLineId] = useState<number | null>(null);
  const [overridingLineId, setOverridingLineId] = useState<number | null>(null);
  const [overrideStartTime, setOverrideStartTime] = useState<number | null>(null);

  // Poll every 2s while an override is in flight, until acceptanceProbability appears or 30s timeout
  useEffect(() => {
    if (overridingLineId === null) return;
    const targetLine = proposal.lines.find(l => l.lineId === overridingLineId);
    const isDone = targetLine?.acceptanceProbability !== null && targetLine?.acceptanceProbability !== undefined;
    const isTimedOut = overrideStartTime !== null && Date.now() - overrideStartTime > 30000;
    if (isDone || isTimedOut) {
      setOverridingLineId(null);
      setOverrideStartTime(null);
      return;
    }
    const t = setTimeout(() => onProposalChange(), 2000);
    return () => clearTimeout(t);
  }, [overridingLineId, proposal.lines, onProposalChange, overrideStartTime]);

  const handleOverride = useCallback(async (lineId: number, productId: number) => {
    setOverridingLineId(lineId);
    setOverrideStartTime(Date.now());
    setChangingLineId(null);
    try {
      await api.proposals.override(rfpId, proposal.id, lineId, productId, token);
      onProposalChange();
      // overridingLineId is intentionally NOT cleared here — the useEffect polls until done
    } catch {
      setOverridingLineId(null);
      setOverrideStartTime(null);
    }
  }, [rfpId, proposal.id, token, onProposalChange]);

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-3 mb-4">
        <div className="text-sm text-slate-500">
          Acceptance rate:
          <span className="font-semibold text-slate-900 ml-1">
            {proposal.acceptanceRate?.toFixed(1) ?? '—'}%
          </span>
        </div>
        <div className="text-sm text-slate-500">
          Match score:
          <span className="font-semibold text-slate-900 ml-1">
            {proposal.matchScore?.toFixed(1) ?? '—'}%
          </span>
        </div>
        {!proposal.isComplete && (
          <span className="text-xs text-amber-600 font-medium">
            ⚠ {proposal.lines.filter(l => l.selectedProduct === null).length} gap(s)
          </span>
        )}
      </div>

      <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 border-b border-slate-200">
            <tr>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Requirement</th>
              <th className="text-right px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Qty</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Selected Product</th>
              <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Price</th>
              <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Match</th>
              <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Acceptance</th>
              <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {proposal.lines.map(line => (
              <Fragment key={line.lineId}>
                <tr
                  onClick={() => setExpandedLineId(expandedLineId === line.lineId ? null : line.lineId)}
                  className="hover:bg-slate-50 cursor-pointer transition-colors"
                >
                  <td className="px-4 py-3 text-slate-900 max-w-xs">
                    <span className="line-clamp-2">{line.description}</span>
                    {line.isOverridden && (
                      <span className="ml-1 text-xs text-indigo-500 font-medium">edited</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-right text-slate-600">{line.qty ?? '—'}</td>
                  <td className="px-4 py-3 text-slate-700">
                    {line.selectedProduct
                      ? (
                        <span>
                          {line.selectedProduct.name}
                          {line.selectedProduct.mpn && (
                            <span className="text-slate-400 font-mono text-xs ml-1">({line.selectedProduct.mpn})</span>
                          )}
                        </span>
                      )
                      : <span className="text-slate-400 italic">not found</span>
                    }
                  </td>
                  <td className="px-4 py-3 text-slate-600 text-xs">
                    {line.selectedProduct?.price != null
                      ? `${line.selectedProduct.price} ${line.selectedProduct.currency}`
                      : '—'}
                  </td>
                  <td className="px-4 py-3"><ScoreBar value={line.matchScore} /></td>
                  <td className="px-4 py-3">
                    {overridingLineId === line.lineId
                      ? <span className="text-xs text-slate-400 animate-pulse">updating…</span>
                      : <AcceptBar value={line.acceptanceProbability} />
                    }
                  </td>
                  <td className="px-4 py-3" onClick={e => e.stopPropagation()}>
                    <button
                      onClick={() => setChangingLineId(changingLineId === line.lineId ? null : line.lineId)}
                      className="text-xs border border-slate-300 rounded px-2 py-1 hover:bg-slate-50 transition-colors"
                    >
                      {changingLineId === line.lineId ? 'Cancel' : 'Change'}
                    </button>
                  </td>
                </tr>

                {expandedLineId === line.lineId && line.llmReasoning && (
                  <tr className="bg-slate-50">
                    <td colSpan={7} className="px-6 py-2 text-xs text-slate-500 italic">
                      {line.llmReasoning}
                    </td>
                  </tr>
                )}

                {changingLineId === line.lineId && (
                  <tr className="bg-indigo-50">
                    <td colSpan={7} className="px-4 pb-4">
                      <ChangePanel
                        rfpId={rfpId}
                        proposalId={proposal.id}
                        line={line}
                        token={token}
                        onOverride={handleOverride}
                      />
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
