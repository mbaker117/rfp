'use client';
import { useEffect, useState } from 'react';
import { api } from '@/src/lib/api';
import type { RfpReport } from '@/src/lib/types';

interface Props {
  rfpId: number;
  token: string;
  onComplete: () => void;
  onError: (msg: string) => void;
}

function StepBadge({ label, state }: { label: string; state: 'done' | 'active' | 'waiting' }) {
  const base = 'inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-medium border';
  const styles = {
    done: 'bg-emerald-50 border-emerald-200 text-emerald-700',
    active: 'bg-indigo-50 border-indigo-200 text-indigo-700 animate-pulse',
    waiting: 'bg-slate-50 border-slate-200 text-slate-400',
  };
  const dot = { done: '✓', active: '⋯', waiting: '○' };
  return <span className={`${base} ${styles[state]}`}>{dot[state]} {label}</span>;
}

export function JobStatusPoller({ rfpId, token, onComplete, onError }: Props) {
  const [report, setReport] = useState<Partial<RfpReport> | null>(null);

  useEffect(() => {
    const interval = setInterval(async () => {
      try {
        const r = await api.getReport(rfpId, token);
        setReport(r);
        if (r.status === 'done') {
          clearInterval(interval);
          onComplete();
        } else if (r.status === 'failed') {
          clearInterval(interval);
          onError('Matching failed');
        }
      } catch (e) {
        clearInterval(interval);
        onError(String(e));
      }
    }, 3000);

    return () => clearInterval(interval);
  }, [rfpId, token, onComplete, onError]);

  const status = report?.status ?? 'uploading';
  const lineCount = report?.lineCount ?? 0;
  const matchedCount = report?.items?.length ?? 0;

  const extractStep: 'done' | 'active' | 'waiting' =
    status === 'uploading' ? 'active' :
    status === 'extracting' ? 'active' : 'done';

  const matchStep: 'done' | 'active' | 'waiting' =
    status === 'uploading' || status === 'extracting' ? 'waiting' :
    status === 'matching' ? 'active' :
    status === 'done' ? 'done' : 'waiting';

  const matchPct = lineCount > 0 ? Math.round((matchedCount / lineCount) * 100) : 0;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 flex-wrap">
        <StepBadge label="Extract requirements" state={extractStep} />
        <span className="text-slate-300 text-xs">→</span>
        <StepBadge label="Match to catalog" state={matchStep} />
        <span className="text-slate-300 text-xs">→</span>
        <StepBadge label="Report ready" state={status === 'done' ? 'done' : 'waiting'} />
      </div>

      {lineCount > 0 && (
        <div className="space-y-1">
          <div className="flex justify-between text-xs text-slate-500">
            <span>Requirements matched: {matchedCount} / {lineCount}</span>
            <span>{matchPct}%</span>
          </div>
          <div className="h-2 bg-slate-100 rounded-full overflow-hidden">
            <div
              className="h-full bg-indigo-500 rounded-full transition-all duration-700"
              style={{ width: `${matchPct}%` }}
            />
          </div>
        </div>
      )}

      {status === 'extracting' && (
        <p className="text-xs text-slate-500">Reading your document and extracting requirement lines…</p>
      )}
      {status === 'matching' && lineCount > 0 && matchedCount === 0 && (
        <p className="text-xs text-slate-500">Looking up supplier catalog for matches…</p>
      )}
    </div>
  );
}
