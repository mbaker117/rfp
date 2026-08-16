'use client';

import type { ProductProvenance as ProductProvenanceType } from '@/src/lib/types';
import { ProductProvenance } from './ProductProvenance';

interface Props {
  attributesJson: string;
  provenance?: ProductProvenanceType;
}

function isSafeExternalUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}

/** Renders parsed product attributes as a simple key-value grid. */
export function ProductAttributePanel({ attributesJson, provenance }: Props) {
  let attrs: Record<string, unknown> = {};
  try { attrs = JSON.parse(attributesJson); } catch { return null; }

  const keys = Object.keys(attrs).filter(k => attrs[k] !== null && attrs[k] !== '');
  if (keys.length === 0 && !provenance) return null;

  const description = attrs['description'] as string | undefined;
  const rawManualLink = attrs['manualLink'];
  const manualLink = typeof rawManualLink === 'string' && isSafeExternalUrl(rawManualLink)
    ? rawManualLink
    : undefined;
  const rest = keys.filter(k => k !== 'description' && k !== 'manualLink');

  return (
    <div className="space-y-2 text-xs">
      {description && (
        <p className="text-slate-600 italic">{description}</p>
      )}
      {manualLink && (
        <a
          href={manualLink}
          target="_blank"
          rel="noreferrer"
          className="inline-flex items-center gap-1 text-indigo-600 hover:underline"
        >
          📄 Product manual / datasheet
        </a>
      )}
      {rest.length > 0 && (
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 sm:grid-cols-3">
          {rest.map(k => (
            <div key={k} className="flex flex-col">
              <dt className="text-slate-400 uppercase tracking-wide text-[10px]">{k}</dt>
              <dd className="text-slate-700 font-medium">{String(attrs[k])}</dd>
            </div>
          ))}
        </dl>
      )}
      {provenance && <ProductProvenance provenance={provenance} />}
    </div>
  );
}
