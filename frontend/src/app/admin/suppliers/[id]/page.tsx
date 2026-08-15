'use client';
import { Fragment, useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'next/navigation';
import { api } from '@/src/lib/api';
import type { AdminProduct, CrawlRunDetail, CrawlRunSummary, IngestRecord, Supplier } from '@/src/lib/types';
import { useAuth } from '@/src/hooks/useAuth';
import { ProductAttributePanel } from '@/src/components/ProductAttributePanel';
import { CrawlRunPanel } from '@/src/components/CrawlRunPanel';

const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

const ACTIVE_STATUSES = new Set<CrawlRunSummary['status']>(['QUEUED', 'CRAWLING']);

function isValidHostname(value: string): boolean {
  if (value.includes('://') || value.includes(':') || value.includes('[')) return false;
  if (/^\d+\.\d+\.\d+\.\d+$/.test(value)) return false;
  return value.length > 0;
}

export default function SupplierDetailPage() {
  const { token } = useAuth();
  const { id } = useParams<{ id: string }>();
  const [supplier, setSupplier] = useState<Supplier | null>(null);
  const [products, setProducts] = useState<AdminProduct[]>([]);
  const [ingests, setIngests] = useState<IngestRecord[]>([]);
  const [crawlRuns, setCrawlRuns] = useState<CrawlRunSummary[]>([]);
  const [latestRunDetail, setLatestRunDetail] = useState<CrawlRunDetail | null>(null);
  const [tab, setTab] = useState<'products' | 'ingests' | 'crawls'>('products');
  const [error, setError] = useState('');
  const [scrapeMsg, setScrapeMsg] = useState('');
  const [websiteInput, setWebsiteInput] = useState('');
  const [expandedIngestId, setExpandedIngestId] = useState<number | null>(null);
  const [expandedProductId, setExpandedProductId] = useState<number | null>(null);

  // Crawl settings state
  const [allowedHostsInput, setAllowedHostsInput] = useState('');
  const [allowedHostsError, setAllowedHostsError] = useState('');
  const [allowedHosts, setAllowedHosts] = useState<string[]>([]);
  const [crawlThrottleMs, setCrawlThrottleMs] = useState<string>('');
  const [crawlMaxConcurrency, setCrawlMaxConcurrency] = useState<string>('');
  const [crawlBatchPages, setCrawlBatchPages] = useState<string>('');
  const [crawlMaxUrls, setCrawlMaxUrls] = useState<string>('');
  const [crawlSettingsSaved, setCrawlSettingsSaved] = useState(false);

  const pollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const supplierId = Number(id);

  const fetchCrawlRuns = useCallback(async () => {
    if (!token) return;
    try {
      const runs = await api.crawl.listRuns(supplierId, token);
      setCrawlRuns(runs);
      if (runs.length > 0) {
        const latest = runs[0];
        const detail = await api.crawl.getRun(latest.id, token);
        setLatestRunDetail(detail);
        return ACTIVE_STATUSES.has(latest.status);
      }
    } catch {
      // ignore polling errors silently
    }
    return false;
  }, [supplierId, token]);

  // Poll while latest run is active
  useEffect(() => {
    let cancelled = false;

    async function poll() {
      const stillActive = await fetchCrawlRuns();
      if (!cancelled && stillActive) {
        pollTimerRef.current = setTimeout(poll, 5000);
      }
    }

    if (token) {
      void poll();
    }

    return () => {
      cancelled = true;
      if (pollTimerRef.current) clearTimeout(pollTimerRef.current);
    };
  }, [fetchCrawlRuns, token]);

  useEffect(() => {
    if (!token) return;
    fetch(`${BASE}/suppliers/${id}`, { headers: { Authorization: `Bearer ${token}` } })
      .then(r => r.json() as Promise<Supplier>)
      .then(s => {
        setSupplier(s);
        setAllowedHosts(s.crawlAllowedHosts ?? []);
        setCrawlThrottleMs(s.crawlThrottleMs != null ? String(s.crawlThrottleMs) : '');
        setCrawlMaxConcurrency(s.crawlMaxConcurrency != null ? String(s.crawlMaxConcurrency) : '');
        setCrawlBatchPages(s.crawlBatchPages != null ? String(s.crawlBatchPages) : '');
        setCrawlMaxUrls(s.crawlMaxUrls != null ? String(s.crawlMaxUrls) : '');
      })
      .catch(e => setError(String(e)));
    api.admin.listProducts(token, { size: 200, supplierId: Number(id) })
      .then(result => setProducts(result.content))
      .catch(e => setError(String(e)));
    api.admin.listIngests(Number(id), token)
      .then(setIngests)
      .catch(e => setError(String(e)));
  }, [id, token]);

  const triggerScrape = async (websiteOverride?: string) => {
    setScrapeMsg('');
    setError('');
    try {
      if (websiteOverride) {
        const updateRes = await fetch(`${BASE}/suppliers/${id}`, {
          method: 'PUT',
          headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ name: supplier!.name, officialWebsite: websiteOverride }),
        });
        if (!updateRes.ok) throw new Error(`Could not save website: HTTP ${updateRes.status}`);
        const updated = await updateRes.json() as Supplier;
        setSupplier(updated);
        setWebsiteInput('');
      }
      const res = await fetch(`${BASE}/suppliers/${id}/catalog/scrape`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setScrapeMsg('Scrape started — LLM is discovering product pages. Check Ingest History for status.');
      setTab('ingests');
      setTimeout(() => {
        api.admin.listIngests(Number(id), token).then(setIngests).catch(() => {});
      }, 2000);
    } catch (e) {
      setError('Scrape failed: ' + String(e));
    }
  };

  const saveCrawlSettings = async () => {
    if (!supplier || !token) return;
    setError('');
    try {
      const body = {
        name: supplier.name,
        officialWebsite: supplier.officialWebsite,
        crawlAllowedHosts: allowedHosts,
        crawlThrottleMs: crawlThrottleMs ? Number(crawlThrottleMs) : null,
        crawlMaxConcurrency: crawlMaxConcurrency ? Number(crawlMaxConcurrency) : null,
        crawlBatchPages: crawlBatchPages ? Number(crawlBatchPages) : null,
        crawlMaxUrls: crawlMaxUrls ? Number(crawlMaxUrls) : null,
      };
      const res = await fetch(`${BASE}/suppliers/${id}`, {
        method: 'PUT',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const updated = await res.json() as Supplier;
      setSupplier(updated);
      setCrawlSettingsSaved(true);
      setTimeout(() => setCrawlSettingsSaved(false), 2000);
    } catch (e) {
      setError('Failed to save crawl settings: ' + String(e));
    }
  };

  const addAllowedHost = () => {
    const host = allowedHostsInput.trim();
    if (!host) return;
    if (!isValidHostname(host)) {
      setAllowedHostsError('Invalid hostname. Use plain domain names only (e.g. example.com).');
      return;
    }
    setAllowedHostsError('');
    if (!allowedHosts.includes(host)) {
      setAllowedHosts(prev => [...prev, host]);
    }
    setAllowedHostsInput('');
  };

  const handleCrawlAction = async (action: 'resume' | 'cancel' | 'retry-failed') => {
    if (!latestRunDetail || !token) return;
    setError('');
    try {
      if (action === 'resume') await api.crawl.resume(latestRunDetail.id, token);
      else if (action === 'cancel') await api.crawl.cancel(latestRunDetail.id, token);
      else if (action === 'retry-failed') await api.crawl.retryFailed(latestRunDetail.id, token);
      await fetchCrawlRuns();
    } catch (e) {
      setError('Crawl action failed: ' + String(e));
    }
  };

  if (!token) return null;

  return (
    <div className="p-8 space-y-6">
      {supplier && (
        <div className="bg-white border border-slate-200 rounded-xl p-6">
          <h2 className="text-xl font-semibold text-slate-900">{supplier.name}</h2>
          {supplier.officialWebsite && (
            <a href={supplier.officialWebsite} target="_blank" rel="noreferrer"
              className="text-sm text-indigo-600 hover:underline">{supplier.officialWebsite}</a>
          )}
          <div className="mt-3 flex items-center gap-3 flex-wrap">
            <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
              supplier.scrapeStatus === 'DONE' ? 'bg-emerald-100 text-emerald-700' :
              supplier.scrapeStatus === 'FAILED' ? 'bg-red-100 text-red-700' :
              supplier.scrapeStatus === 'RUNNING' ? 'bg-amber-100 text-amber-700' :
              'bg-slate-100 text-slate-500'
            }`}>{supplier.scrapeStatus}</span>

            {supplier.officialWebsite ? (
              <button
                onClick={() => triggerScrape()}
                className="text-sm bg-indigo-600 text-white px-3 py-1 rounded-md hover:bg-indigo-700 transition-colors"
              >
                Trigger Scrape
              </button>
            ) : (
              <div className="flex items-center gap-2">
                <input
                  type="url"
                  value={websiteInput}
                  onChange={e => setWebsiteInput(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter' && websiteInput.trim()) triggerScrape(websiteInput.trim()); }}
                  placeholder="https://supplier-website.com"
                  className="border border-slate-300 rounded-md px-2 py-1 text-xs w-56 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
                <button
                  onClick={() => { if (websiteInput.trim()) triggerScrape(websiteInput.trim()); }}
                  disabled={!websiteInput.trim()}
                  className="text-xs bg-indigo-600 text-white px-2 py-1 rounded-md hover:bg-indigo-700 disabled:opacity-40 transition-colors"
                >
                  Scrape
                </button>
              </div>
            )}

            {supplier.categories.length > 0 && (
              <span className="text-sm text-slate-500">Categories: {supplier.categories.join(', ')}</span>
            )}
          </div>
          {scrapeMsg && <p className="mt-2 text-sm text-emerald-600">{scrapeMsg}</p>}
        </div>
      )}

      {/* Crawl Settings */}
      {supplier && (
        <div className="bg-white border border-slate-200 rounded-xl p-6 space-y-4">
          <h3 className="text-base font-semibold text-slate-800">Crawl Settings</h3>

          {/* Allowed Hosts */}
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1">
              Additional Allowed Hosts
            </label>
            <p className="text-xs text-slate-400 mb-2">
              Root-domain subdomains are crawled automatically. Add additional hosts here.
            </p>
            <div className="flex gap-2 mb-2">
              <input
                type="text"
                value={allowedHostsInput}
                onChange={e => { setAllowedHostsInput(e.target.value); setAllowedHostsError(''); }}
                onKeyDown={e => { if (e.key === 'Enter') addAllowedHost(); }}
                placeholder="docs.example.com"
                className="border border-slate-300 rounded-md px-2 py-1 text-xs flex-1 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
              <button
                onClick={addAllowedHost}
                className="text-xs bg-slate-700 text-white px-3 py-1 rounded-md hover:bg-slate-800 transition-colors"
              >
                Add
              </button>
            </div>
            {allowedHostsError && (
              <p className="text-xs text-red-600 mb-2">{allowedHostsError}</p>
            )}
            {allowedHosts.length > 0 && (
              <ul className="space-y-1">
                {allowedHosts.map(host => (
                  <li key={host} className="flex items-center gap-2 text-xs text-slate-700">
                    <span className="font-mono">{host}</span>
                    <button
                      onClick={() => setAllowedHosts(prev => prev.filter(h => h !== host))}
                      aria-label={`Remove ${host}`}
                      className="text-red-400 hover:text-red-600 transition-colors"
                    >
                      &times;
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* Numeric overrides */}
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1">
                Throttle (ms)
              </label>
              <input
                type="number"
                value={crawlThrottleMs}
                onChange={e => setCrawlThrottleMs(e.target.value)}
                placeholder="e.g. 500"
                min={0}
                className="border border-slate-300 rounded-md px-2 py-1 text-xs w-full focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1">
                Max Concurrency
              </label>
              <input
                type="number"
                value={crawlMaxConcurrency}
                onChange={e => setCrawlMaxConcurrency(e.target.value)}
                placeholder="e.g. 3"
                min={1}
                className="border border-slate-300 rounded-md px-2 py-1 text-xs w-full focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1">
                Batch Pages
              </label>
              <input
                type="number"
                value={crawlBatchPages}
                onChange={e => setCrawlBatchPages(e.target.value)}
                placeholder="e.g. 50"
                min={1}
                className="border border-slate-300 rounded-md px-2 py-1 text-xs w-full focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1">
                Max URLs
              </label>
              <input
                type="number"
                value={crawlMaxUrls}
                onChange={e => setCrawlMaxUrls(e.target.value)}
                placeholder="e.g. 5000"
                min={1}
                className="border border-slate-300 rounded-md px-2 py-1 text-xs w-full focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
          </div>

          <div className="flex items-center gap-3">
            <button
              onClick={saveCrawlSettings}
              className="text-sm bg-indigo-600 text-white px-4 py-1.5 rounded-md hover:bg-indigo-700 transition-colors"
            >
              Save Crawl Settings
            </button>
            {crawlSettingsSaved && (
              <span className="text-xs text-emerald-600">Saved!</span>
            )}
          </div>
        </div>
      )}

      {error && (
        <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-4 py-3">{error}</p>
      )}

      {/* Crawl Runs (outside tabs — shown inline above tabs) */}
      {latestRunDetail && (
        <div className="bg-white border border-slate-200 rounded-xl p-6 space-y-3">
          <h3 className="text-base font-semibold text-slate-800">Latest Crawl Run</h3>
          <CrawlRunPanel run={latestRunDetail} onAction={handleCrawlAction} />
        </div>
      )}

      <div className="flex border-b border-slate-200 mb-4">
        {(['products', 'ingests', 'crawls'] as const).map(t => (
          <button key={t} onClick={() => setTab(t)}
            className={`px-4 py-2 text-sm font-medium capitalize transition-colors ${
              tab === t ? 'border-b-2 border-indigo-600 text-indigo-600' : 'text-slate-500 hover:text-slate-700'
            }`}>
            {t === 'ingests' ? 'Ingest History' : t === 'crawls' ? 'Crawl Runs' : 'Products'}
          </button>
        ))}
      </div>

      {tab === 'products' && (
        <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 border-b border-slate-200">
              <tr>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Name</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">MPN</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Class</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Source</th>
                <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Stale</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {products.map(p => (
                <Fragment key={p.id}>
                  <tr
                    onClick={() => setExpandedProductId(expandedProductId === p.id ? null : p.id)}
                    className="hover:bg-slate-50 cursor-pointer"
                  >
                    <td className="px-4 py-3 text-slate-900">{p.name}</td>
                    <td className="px-4 py-3 text-slate-500 font-mono text-xs">{p.mpn ?? '—'}</td>
                    <td className="px-4 py-3 text-slate-500">{p.productClass ?? '—'}</td>
                    <td className="px-4 py-3">
                      {p.source && (
                        <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
                          p.source === 'scrape' ? 'bg-blue-100 text-blue-700' : 'bg-violet-100 text-violet-700'
                        }`}>{p.source === 'scrape' ? 'web' : 'upload'}</span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      {p.isStale && (
                        <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-amber-100 text-amber-700">
                          stale
                        </span>
                      )}
                    </td>
                  </tr>
                  {expandedProductId === p.id && p.attributes && (
                    <tr className="bg-slate-50">
                      <td colSpan={5} className="px-6 py-4">
                        <ProductAttributePanel attributesJson={p.attributes} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {tab === 'ingests' && (
        <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 border-b border-slate-200">
              <tr>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Kind</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Status</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Started</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Finished</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Items Found</th>
                <th className="text-left px-4 py-3 text-xs font-medium uppercase tracking-wide text-slate-500">Error / Notes</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {ingests.map(i => (
                <Fragment key={i.id}>
                  <tr
                    onClick={() => setExpandedIngestId(expandedIngestId === i.id ? null : i.id)}
                    className={`cursor-pointer hover:bg-slate-50 ${i.status === 'FAILED' ? 'bg-red-50 hover:bg-red-100' : ''}`}
                  >
                    <td className="px-4 py-3 text-slate-700 font-mono text-xs">{i.kind}</td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
                        i.status === 'DONE' ? 'bg-emerald-100 text-emerald-700' :
                        i.status === 'FAILED' ? 'bg-red-100 text-red-700' :
                        i.status === 'RUNNING' ? 'bg-amber-100 text-amber-700' :
                        'bg-slate-100 text-slate-500'
                      }`}>{i.status}</span>
                    </td>
                    <td className="px-4 py-3 text-slate-500 text-xs">{i.startedAt ? new Date(i.startedAt).toLocaleString() : '—'}</td>
                    <td className="px-4 py-3 text-slate-500 text-xs">{i.finishedAt ? new Date(i.finishedAt).toLocaleString() : '—'}</td>
                    <td className="px-4 py-3 text-slate-700 text-sm font-medium">
                      {i.itemsFound !== null ? (
                        <span className={i.itemsFound === 0 ? 'text-amber-600' : 'text-emerald-600'}>
                          {i.itemsFound}
                        </span>
                      ) : '—'}
                    </td>
                    <td className="px-4 py-3 text-red-600 text-xs max-w-xs truncate" title={i.errorMsg ?? undefined}>{i.errorMsg ?? '—'}</td>
                  </tr>
                  {expandedIngestId === i.id && (i.stepLog || i.errorMsg) && (
                    <tr className="bg-slate-50">
                      <td colSpan={6} className="px-4 py-3">
                        {i.stepLog && (
                          <pre className="text-xs text-slate-600 whitespace-pre-wrap font-mono bg-white border border-slate-200 rounded-lg p-3 max-h-80 overflow-auto">
                            {i.stepLog}
                          </pre>
                        )}
                        {i.errorMsg && (
                          <p className="text-xs text-red-600 mt-1">{i.errorMsg}</p>
                        )}
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {tab === 'crawls' && (
        <div className="space-y-3">
          {crawlRuns.length === 0 ? (
            <p className="text-sm text-slate-500">No crawl runs yet.</p>
          ) : (
            crawlRuns.map(run => (
              <div key={run.id} className="bg-white border border-slate-200 rounded-xl p-4 text-xs text-slate-600">
                <div className="flex items-center gap-3">
                  <span className="font-medium">Run #{run.id}</span>
                  <span>{run.status}</span>
                  <span>{run.mode}</span>
                  <span>{run.discoveredUrlCount} discovered</span>
                  <span>{run.fetchedUrlCount} fetched</span>
                  {run.startedAt && (
                    <span className="ml-auto text-slate-400">{new Date(run.startedAt).toLocaleString()}</span>
                  )}
                </div>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
}
