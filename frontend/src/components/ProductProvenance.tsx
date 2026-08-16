'use client';

import type { ProductProvenance as ProductProvenanceType } from '@/src/lib/types';

interface Props {
  provenance: ProductProvenanceType;
}

function isSafeExternalUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}

export function ProductProvenance({ provenance }: Props) {
  const { canonicalSourceUrl, lastObservedAt, extractionMethod } = provenance;

  const hasSourceLink = canonicalSourceUrl != null && isSafeExternalUrl(canonicalSourceUrl);

  return (
    <div className="mt-3 flex flex-wrap items-center gap-3 text-xs text-slate-500">
      {hasSourceLink && (
        <a
          href={canonicalSourceUrl!}
          target="_blank"
          rel="noreferrer"
          className="inline-flex items-center gap-1 text-indigo-600 hover:underline"
          aria-label="Source"
        >
          Source
        </a>
      )}
      {extractionMethod && (
        <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-slate-100 text-slate-600">
          {extractionMethod}
        </span>
      )}
      {lastObservedAt && (
        <span>
          Last seen: {new Date(lastObservedAt).toLocaleDateString()}
        </span>
      )}
    </div>
  );
}
