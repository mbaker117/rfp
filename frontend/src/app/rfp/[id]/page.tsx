'use client';
import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { ReportTable } from '@/src/components/ReportTable';
import { api } from '@/src/lib/api';
import { useAuth } from '@/src/hooks/useAuth';
import type { RfpReport } from '@/src/lib/types';

function StatusBadge({ status }: { status: string }) {
  const cls = status === 'done' ? 'bg-emerald-100 text-emerald-700'
    : status === 'failed' ? 'bg-red-100 text-red-700'
    : 'bg-amber-100 text-amber-700';
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${cls}`}>
      {status}
    </span>
  );
}

export default function ReportPage() {
  const { token } = useAuth();
  const { id } = useParams<{ id: string }>();
  const [report, setReport] = useState<RfpReport | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    if (token) {
      api.getReport(Number(id), token).then(setReport).catch(e => setError(String(e)));
    }
  }, [id, token]);

  const handleExport = async (format: 'pdf' | 'xlsx') => {
    const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';
    const res = await fetch(`${apiUrl}/rfp/${id}/report/export?format=${format}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) return;
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `report-${id}.${format}`;
    a.click();
    URL.revokeObjectURL(url);
  };

  if (!token) return null;

  if (error) return (
    <div className="min-h-screen bg-slate-50 flex items-center justify-center">
      <p className="text-red-600 bg-red-50 border border-red-200 rounded-lg px-6 py-4">{error}</p>
    </div>
  );

  if (!report) return (
    <div className="min-h-screen bg-slate-50 flex items-center justify-center">
      <p className="text-slate-500 animate-pulse">Loading report...</p>
    </div>
  );

  const matched = report.items.filter(i => i.status === 'matched').length;
  const partial = report.items.filter(i => i.status === 'partial').length;
  const notFound = report.items.filter(i => i.status === 'not_found').length;

  return (
    <main className="min-h-screen bg-slate-50">
      <nav className="bg-white border-b border-slate-200 px-6 py-3 flex items-center gap-4">
        <a href="/" className="text-sm text-slate-500 hover:text-indigo-600 transition-colors">← Home</a>
        <span className="text-slate-300">|</span>
        <span className="text-sm font-medium text-slate-700">Report #{report.rfpId}</span>
        <StatusBadge status={report.status} />
        <div className="ml-auto flex gap-2">
          <button
            onClick={() => handleExport('xlsx')}
            className="text-xs bg-emerald-600 text-white px-3 py-1.5 rounded-md hover:bg-emerald-700 transition-colors"
          >
            Export Excel
          </button>
          <button
            onClick={() => handleExport('pdf')}
            className="text-xs bg-slate-600 text-white px-3 py-1.5 rounded-md hover:bg-slate-700 transition-colors"
          >
            Export PDF
          </button>
        </div>
      </nav>

      <div className="max-w-7xl mx-auto py-8 px-4 space-y-6">
        <div className="flex gap-3">
          <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm bg-emerald-100 text-emerald-700 font-medium">
            {matched} matched
          </span>
          <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm bg-amber-100 text-amber-700 font-medium">
            {partial} partial
          </span>
          <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm bg-slate-100 text-slate-600 font-medium">
            {notFound} not found
          </span>
        </div>

        <ReportTable items={report.items} />
      </div>
    </main>
  );
}
