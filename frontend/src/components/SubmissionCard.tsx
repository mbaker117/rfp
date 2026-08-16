'use client';
import { useState } from 'react';
import Link from 'next/link';
import { SupplierCombobox, type SelectedSupplier } from './SupplierCombobox';
import { api } from '@/src/lib/api';
import type { TenderSummary } from '@/src/lib/types';

const STATUS_COLORS: Record<string, string> = {
  uploading: 'bg-slate-200 text-slate-700',
  extracting: 'bg-amber-100 text-amber-700',
  matching: 'bg-blue-100 text-blue-700',
  pending_match: 'bg-blue-100 text-blue-700',
  done: 'bg-green-100 text-green-700',
  failed: 'bg-red-100 text-red-700',
};

const REPORT_ENABLED_STATUSES = new Set(['matching', 'pending_match', 'done', 'failed']);

interface Props {
  tender: TenderSummary;
  token: string;
  onDeleted: (id: number) => void;
}

export function SubmissionCard({ tender, token, onDeleted }: Props) {
  const [rerunOpen, setRerunOpen] = useState(false);
  const [selectedSuppliers, setSelectedSuppliers] = useState<SelectedSupplier[]>([]);
  const [rerunError, setRerunError] = useState('');
  const [rerunLoading, setRerunLoading] = useState(false);
  const [deleteError, setDeleteError] = useState('');
  const [localStatus, setLocalStatus] = useState(tender.status);

  const reportEnabled = REPORT_ENABLED_STATUSES.has(localStatus);
  const rerunDisabled = localStatus === 'matching' || localStatus === 'extracting' || localStatus === 'pending_match' || localStatus === 'uploading';

  async function handleDelete() {
    if (!window.confirm('Delete this submission and all its results?')) return;
    try {
      await api.myRfps.delete(tender.id, token);
      onDeleted(tender.id);
    } catch {
      setDeleteError('Could not delete submission.');
    }
  }

  async function handleRerun() {
    setRerunLoading(true);
    setRerunError('');
    try {
      const ids = selectedSuppliers.map(s => s.id);
      await api.triggerMatch(tender.id, token, ids.length > 0 ? ids : undefined);
      setLocalStatus('pending_match');
      setRerunOpen(false);
    } catch {
      setRerunError('Could not start matching.');
    } finally {
      setRerunLoading(false);
    }
  }

  const statusColor = STATUS_COLORS[localStatus] ?? 'bg-gray-100 text-gray-700';
  const created = new Date(tender.createdAt).toLocaleString();

  return (
    <div className="border rounded-lg p-4 space-y-2">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <span className="font-medium">{tender.filename}</span>
          <span className={`ml-2 px-2 py-0.5 rounded text-xs font-semibold ${statusColor}`}>
            {localStatus}
          </span>
        </div>
        <span className="text-sm text-gray-500">{created}</span>
      </div>

      <div className="text-sm text-gray-600 flex gap-4">
        <span>{tender.lineCount} lines</span>
        <span>{tender.proposalCount} proposals</span>
      </div>

      <div className="flex gap-2 flex-wrap items-center">
        {reportEnabled ? (
          <Link
            href={`/rfp/${tender.id}`}
            className="text-sm px-3 py-1 rounded bg-blue-600 text-white hover:bg-blue-700"
          >
            View Report →
          </Link>
        ) : (
          <span className="text-sm px-3 py-1 rounded bg-gray-200 text-gray-400 cursor-not-allowed">
            View Report
          </span>
        )}

        <button
          disabled={rerunDisabled}
          onClick={() => setRerunOpen(o => !o)}
          className="text-sm px-3 py-1 rounded border border-gray-300 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          Re-run Matching
        </button>

        <button
          onClick={handleDelete}
          className="text-sm px-3 py-1 rounded border border-red-300 text-red-600 hover:bg-red-50"
        >
          Delete
        </button>
      </div>

      {deleteError && <p className="text-sm text-red-600">{deleteError}</p>}

      {rerunOpen && (
        <div className="border-t pt-3 space-y-2">
          <p className="text-sm font-medium">Select suppliers for re-run:</p>
          <SupplierCombobox
            token={token}
            defaultSelectedIds={tender.supplierIds}
            onChange={setSelectedSuppliers}
          />
          <div className="flex gap-2 items-center">
            <button
              onClick={handleRerun}
              disabled={rerunLoading}
              className="text-sm px-3 py-1 rounded bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {rerunLoading ? 'Starting…' : 'Run'}
            </button>
            <button
              onClick={() => { setRerunOpen(false); setSelectedSuppliers([]); }}
              className="text-sm text-gray-500 hover:underline"
            >
              Cancel
            </button>
          </div>
          {rerunError && <p className="text-sm text-red-600">{rerunError}</p>}
        </div>
      )}
    </div>
  );
}
