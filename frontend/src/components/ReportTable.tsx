import type { ReportItem } from '@/src/lib/types';

const scoreColor = (score: number | null) => {
  if (score === null) return 'text-gray-400';
  if (score >= 80) return 'text-green-700';
  if (score >= 40) return 'text-yellow-700';
  return 'text-red-600';
};

const statusColor = (status: ReportItem['status']) => {
  if (status === 'MATCHED') return 'text-green-700 font-semibold';
  if (status === 'PARTIAL') return 'text-yellow-700 font-semibold';
  return 'text-red-600 font-semibold';
};

interface Props { items: ReportItem[] }

export function ReportTable({ items }: Props) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm border-collapse">
        <thead>
          <tr className="bg-gray-100 text-left">
            <th className="p-2 border">Required Instrument</th>
            <th className="p-2 border">Matched Instrument</th>
            <th className="p-2 border">Score</th>
            <th className="p-2 border">Status</th>
            <th className="p-2 border">Price (JOD)</th>
            <th className="p-2 border">Manual</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item, i) => (
            <tr key={i} className={item.status === 'NOT_FOUND' ? 'bg-red-50' : ''}>
              <td className="p-2 border">{item.requiredInstrument}</td>
              <td className="p-2 border">{item.matchedInstrument ?? '—'}</td>
              <td className={`p-2 border text-center ${scoreColor(item.score)}`}>{item.score ?? '—'}</td>
              <td className={`p-2 border text-center ${statusColor(item.status)}`}>{item.status}</td>
              <td className="p-2 border text-right">{item.price != null ? `${item.price} ${item.currency}` : '—'}</td>
              <td className="p-2 border">
                {item.manualLink
                  ? <a href={item.manualLink} target="_blank" rel="noreferrer" className="text-blue-600 underline">Manual</a>
                  : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
