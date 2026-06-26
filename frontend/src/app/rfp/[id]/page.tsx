'use client';
import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { ReportTable } from '@/src/components/ReportTable';
import { api } from '@/src/lib/api';
import type { RfpReport } from '@/src/lib/types';

export default function ReportPage() {
  const { id } = useParams<{ id: string }>();
  const [report, setReport] = useState<RfpReport | null>(null);
  const [error, setError] = useState('');
  const token = typeof window !== 'undefined' ? localStorage.getItem('token') ?? '' : '';

  useEffect(() => {
    api.getReport(Number(id), token).then(setReport).catch(e => setError(String(e)));
  }, [id, token]);

  if (error) return <p className="text-red-600 p-8">{error}</p>;
  if (!report) return <p className="p-8">Loading report...</p>;

  const matched = report.items.filter(i => i.status === 'MATCHED').length;
  const notFound = report.items.filter(i => i.status === 'NOT_FOUND').length;
  const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

  return (
    <main className="max-w-5xl mx-auto py-8 px-4 space-y-4">
      <h1 className="text-2xl font-bold">Matching Report — RFP #{report.rfpId}</h1>
      <div className="flex gap-6 text-sm">
        <span className="text-green-700 font-semibold">{matched} matched</span>
        <span className="text-red-600 font-semibold">{notFound} not found</span>
        <span className="text-gray-600">{report.items.length} total</span>
      </div>
      <div className="flex gap-2">
        <a
          href={`${apiUrl}/rfp/${id}/report/export?format=xlsx`}
          className="bg-green-600 text-white px-3 py-1 rounded text-sm"
        >
          Export Excel
        </a>
        <a
          href={`${apiUrl}/rfp/${id}/report/export?format=pdf`}
          className="bg-gray-600 text-white px-3 py-1 rounded text-sm"
        >
          Export PDF
        </a>
      </div>
      <ReportTable items={report.items} />
    </main>
  );
}
