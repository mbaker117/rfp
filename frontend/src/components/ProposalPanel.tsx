'use client';
import type { Proposal } from '@/src/lib/types';

const VARIANT_LABELS: Record<string, string> = {
  PERFECT: 'Perfect Match',
  BEST_ACCEPTANCE: 'Best Acceptance',
  CHEAPEST: 'Cheapest',
};

const VARIANT_DESCRIPTIONS: Record<string, string> = {
  PERFECT: 'Highest technical compliance',
  BEST_ACCEPTANCE: 'Most likely to be accepted',
  CHEAPEST: 'Lowest total cost',
};

function RateBar({ value, color }: { value: number; color: string }) {
  return (
    <div className="flex items-center gap-2 mt-1">
      <div className="flex-1 h-2 bg-slate-100 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${value}%` }} />
      </div>
      <span className="text-sm font-semibold w-10 text-right">{value.toFixed(0)}%</span>
    </div>
  );
}

function ProposalCard({ proposal, onSelect }: { proposal: Proposal; onSelect: () => void }) {
  const isGenerating = proposal.status === 'GENERATING';
  const isFailed = proposal.status === 'FAILED';
  const gapCount = proposal.lines.filter(l => l.selectedProduct === null).length;

  const acceptanceColor =
    (proposal.acceptanceRate ?? 0) >= 70 ? 'bg-emerald-500' :
    (proposal.acceptanceRate ?? 0) >= 40 ? 'bg-amber-400' : 'bg-red-400';

  const scoreColor =
    (proposal.matchScore ?? 0) >= 80 ? 'bg-emerald-500' :
    (proposal.matchScore ?? 0) >= 40 ? 'bg-amber-400' : 'bg-slate-300';

  return (
    <div className={`flex flex-col border rounded-xl p-5 bg-white shadow-sm gap-3 ${
      isFailed ? 'border-red-200' : 'border-slate-200'
    }`}>
      <div>
        <p className="text-xs font-medium uppercase tracking-wide text-slate-400">
          {VARIANT_DESCRIPTIONS[proposal.variant]}
        </p>
        <h3 className="text-base font-semibold text-slate-900 mt-0.5">
          {VARIANT_LABELS[proposal.variant] ?? proposal.variant}
        </h3>
      </div>

      {isGenerating ? (
        <div className="space-y-3 animate-pulse">
          <div className="h-4 bg-slate-100 rounded w-3/4" />
          <div className="h-4 bg-slate-100 rounded w-2/3" />
          <p className="text-xs text-slate-400 mt-1">Computing&hellip;</p>
        </div>
      ) : isFailed ? (
        <p className="text-sm text-red-600">Generation failed</p>
      ) : (
        <div className="space-y-2">
          <div>
            <p className="text-xs text-slate-500">Acceptance rate</p>
            <RateBar value={proposal.acceptanceRate ?? 0} color={acceptanceColor} />
          </div>
          <div>
            <p className="text-xs text-slate-500">Match score</p>
            <RateBar value={proposal.matchScore ?? 0} color={scoreColor} />
          </div>
          <div className="flex items-center gap-1.5 text-xs mt-1">
            {proposal.isComplete ? (
              <span className="text-emerald-600 font-medium">&#10003; Complete</span>
            ) : (
              <span className="text-amber-600 font-medium">&#9888; {gapCount} gap{gapCount !== 1 ? 's' : ''}</span>
            )}
          </div>
        </div>
      )}

      <button
        disabled={isGenerating || isFailed}
        onClick={onSelect}
        className="mt-auto text-sm bg-indigo-600 text-white px-3 py-1.5 rounded-md
          hover:bg-indigo-700 disabled:opacity-40 transition-colors"
      >
        View &amp; Edit
      </button>
    </div>
  );
}

interface Props {
  proposals: Proposal[];
  onSelect: (p: Proposal) => void;
}

export function ProposalPanel({ proposals, onSelect }: Props) {
  const order = ['PERFECT', 'BEST_ACCEPTANCE', 'CHEAPEST'];
  const sorted = order
    .map(v => proposals.find(p => p.variant === v))
    .filter(Boolean) as Proposal[];

  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
      {sorted.map(p => (
        <ProposalCard key={p.id} proposal={p} onSelect={() => onSelect(p)} />
      ))}
    </div>
  );
}
