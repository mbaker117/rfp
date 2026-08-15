'use client';

import { useState } from 'react';
import type { CrawlRunDetail } from '@/src/lib/types';

interface Props {
  run: CrawlRunDetail;
  onAction: (action: 'resume' | 'cancel' | 'retry-failed') => Promise<void>;
}

const STATUS_BADGE: Record<CrawlRunDetail['status'], string> = {
  CRAWLING:  'bg-blue-100 text-blue-700',
  COMPLETE:  'bg-emerald-100 text-emerald-700',
  PARTIAL:   'bg-amber-100 text-amber-700',
  FAILED:    'bg-red-100 text-red-700',
  CANCELLED: 'bg-slate-100 text-slate-500',
  QUEUED:    'bg-indigo-100 text-indigo-700',
};

const TERMINAL_STATUSES = new Set<CrawlRunDetail['status']>(['COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED']);

function fmt(n: number): string {
  return n.toLocaleString('en-US');
}

export function CrawlRunPanel({ run, onAction }: Props) {
  const [pending, setPending] = useState(false);

  const isTerminal = TERMINAL_STATUSES.has(run.status);
  const canResume = run.status === 'PARTIAL' || run.status === 'FAILED';
  const canCancel = run.status === 'QUEUED' || run.status === 'CRAWLING';
  const canRetry = (run.status === 'PARTIAL' || run.status === 'FAILED') && run.failedUrlCount > 0;

  async function handleAction(action: 'resume' | 'cancel' | 'retry-failed') {
    setPending(true);
    try {
      await onAction(action);
    } finally {
      setPending(false);
    }
  }

  const badgeClass = STATUS_BADGE[run.status] ?? 'bg-slate-100 text-slate-500';

  const score = run.completenessScore;
  const reason = run.completenessReason;

  return (
    <div className="border border-slate-200 rounded-xl p-4 space-y-3 text-sm">
      {/* Header */}
      <div className="flex items-center gap-3">
        <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${badgeClass}`}>
          {run.status}
        </span>
        <span className="text-slate-500 text-xs">Run #{run.id} · {run.mode}</span>
        {run.startedAt && (
          <span className="text-slate-400 text-xs ml-auto">
            Started: {new Date(run.startedAt).toLocaleString()}
          </span>
        )}
      </div>

      {/* Partial warning */}
      {run.status === 'PARTIAL' && (
        <div className="rounded-md bg-amber-50 border border-amber-200 px-3 py-2 text-amber-800 text-xs">
          Partial crawl cannot mark products stale
        </div>
      )}

      {/* Counts */}
      <div className="flex flex-wrap gap-x-6 gap-y-1 text-xs text-slate-600">
        <span>{fmt(run.discoveredUrlCount)} discovered</span>
        <span>{fmt(run.fetchedUrlCount)} fetched</span>
        {run.failedUrlCount > 0 && (
          <span className="text-red-600">{fmt(run.failedUrlCount)} failed</span>
        )}
        <span>{fmt(run.observedProductCount)} products observed</span>
        {run.insertedProductCount > 0 && (
          <span className="text-emerald-600">{fmt(run.insertedProductCount)} inserted</span>
        )}
        {run.updatedProductCount > 0 && (
          <span className="text-indigo-600">{fmt(run.updatedProductCount)} updated</span>
        )}
      </div>

      {/* Completeness */}
      {(score != null || reason) && (
        <div className="text-xs text-slate-500">
          {score != null
            ? `Completeness: ${Math.round(score * 100)}%`
            : reason}
        </div>
      )}

      {/* Failure details */}
      {run.failureDetails && (
        <p className="text-xs text-red-600">{run.failureDetails}</p>
      )}

      {/* Finished */}
      {isTerminal && run.finishedAt && (
        <p className="text-xs text-slate-400">
          Finished: {new Date(run.finishedAt).toLocaleString()}
        </p>
      )}

      {/* Actions */}
      {(canResume || canCancel || canRetry) && (
        <div className="flex gap-2 pt-1">
          {canResume && (
            <button
              onClick={() => handleAction('resume')}
              disabled={pending}
              aria-label="Resume crawl run"
              className="text-xs bg-indigo-600 text-white px-3 py-1 rounded-md hover:bg-indigo-700 disabled:opacity-40 transition-colors"
            >
              Resume
            </button>
          )}
          {canRetry && (
            <button
              onClick={() => handleAction('retry-failed')}
              disabled={pending}
              aria-label="Retry failed URLs"
              className="text-xs bg-amber-600 text-white px-3 py-1 rounded-md hover:bg-amber-700 disabled:opacity-40 transition-colors"
            >
              Retry Failed
            </button>
          )}
          {canCancel && (
            <button
              onClick={() => handleAction('cancel')}
              disabled={pending}
              aria-label="Cancel crawl run"
              className="text-xs bg-slate-600 text-white px-3 py-1 rounded-md hover:bg-slate-700 disabled:opacity-40 transition-colors"
            >
              Cancel
            </button>
          )}
        </div>
      )}
    </div>
  );
}
