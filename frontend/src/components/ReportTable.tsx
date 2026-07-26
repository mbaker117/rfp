import type { MatchResultItem, AttributeVerdict, Alternative } from '@/src/lib/types';

const scoreColor = (score: number) => {
  if (score >= 80) return 'text-green-700';
  if (score >= 40) return 'text-yellow-700';
  return 'text-red-600';
};

const statusColor = (status: MatchResultItem['status']) => {
  if (status === 'matched') return 'text-green-700 font-semibold';
  if (status === 'partial') return 'text-yellow-700 font-semibold';
  return 'text-red-600 font-semibold';
};

const verdictColor = (verdict: AttributeVerdict['verdict']) => {
  if (verdict === 'COMPLIANT') return 'text-green-700';
  if (verdict === 'DEVIATION') return 'text-red-600';
  return 'text-gray-500';
};

// The backend stores attribute_verdicts / alternatives as jsonb and serializes
// them as raw JSON strings, so accept either a parsed array or a string.
function asArray<T>(value: T[] | string | null | undefined): T[] {
  if (Array.isArray(value)) return value;
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      return Array.isArray(parsed) ? (parsed as T[]) : [];
    } catch {
      return [];
    }
  }
  return [];
}

const show = (v: unknown) =>
  v === null || v === undefined ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v);

function VerdictList({ verdicts }: { verdicts: AttributeVerdict[] }) {
  if (verdicts.length === 0) return <span className="text-gray-400">—</span>;
  return (
    <ul className="space-y-0.5">
      {verdicts.map((v, i) => (
        <li key={`${v.attr}-${i}`} className="whitespace-nowrap">
          <span className="font-medium">{v.attr}</span>
          <span className="text-gray-500">
            {' '}
            req {show(v.required)} / off {show(v.offered)}{' '}
          </span>
          <span className={verdictColor(v.verdict)}>{v.verdict}</span>
        </li>
      ))}
    </ul>
  );
}

function AlternativeList({ alternatives }: { alternatives: Alternative[] }) {
  if (alternatives.length === 0) return <span className="text-gray-400">—</span>;
  return (
    <ul className="space-y-0.5">
      {alternatives.map(alt => (
        <li key={alt.productId} className="whitespace-nowrap">
          {alt.name}
          {alt.mpn ? <span className="text-gray-500"> ({alt.mpn})</span> : null}
          <span className={`ml-1 ${scoreColor(alt.score)}`}>{alt.score}</span>
        </li>
      ))}
    </ul>
  );
}

interface Props {
  items: MatchResultItem[];
}

export function ReportTable({ items }: Props) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm border-collapse">
        <thead>
          <tr className="bg-gray-100 text-left">
            <th className="p-2 border">Requirement</th>
            <th className="p-2 border">Qty</th>
            <th className="p-2 border">Match Type</th>
            <th className="p-2 border">Score</th>
            <th className="p-2 border">Status</th>
            <th className="p-2 border">Matched Product</th>
            <th className="p-2 border">MPN</th>
            <th className="p-2 border">Attribute Verdicts</th>
            <th className="p-2 border">Alternatives</th>
          </tr>
        </thead>
        <tbody>
          {items.map(item => (
            <tr
              key={item.lineId}
              className={item.status === 'not_found' ? 'bg-red-50' : ''}
            >
              <td className="p-2 border">{item.description}</td>
              <td className="p-2 border text-right">{item.qty ?? '—'}</td>
              <td className="p-2 border text-center">{item.matchType ?? '—'}</td>
              <td className={`p-2 border text-center ${scoreColor(item.score)}`}>
                {item.score}
              </td>
              <td className={`p-2 border text-center ${statusColor(item.status)}`}>
                {item.status}
              </td>
              <td className="p-2 border">{item.matchedProduct ?? '—'}</td>
              <td className="p-2 border">{item.mpn ?? '—'}</td>
              <td className="p-2 border text-xs">
                <VerdictList verdicts={asArray<AttributeVerdict>(item.attributeVerdicts)} />
              </td>
              <td className="p-2 border text-xs">
                <AlternativeList alternatives={asArray<Alternative>(item.alternatives)} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
