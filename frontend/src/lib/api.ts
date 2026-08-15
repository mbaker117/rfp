import type { Supplier, RfpReport, AdminUser, AdminProduct, AdminTender, PageResult, IngestRecord, CrawlRunSummary, CrawlRunDetail } from './types';

const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`API error ${res.status}`);
  return res.json();
}

export const api = {
  auth: {
    async login(username: string, password: string): Promise<{ token: string }> {
      const res = await fetch(`${BASE}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      return json<{ token: string }>(res);
    },
    async register(username: string, password: string): Promise<{ token: string }> {
      const res = await fetch(`${BASE}/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      return json<{ token: string }>(res);
    },
  },

  async listSuppliers(token: string): Promise<Supplier[]> {
    const res = await fetch(`${BASE}/suppliers`, { headers: authHeaders(token) });
    return json<Supplier[]>(res);
  },

  async registerSupplier(
    data: { name: string; officialWebsite?: string },
    token: string
  ): Promise<Supplier> {
    const res = await fetch(`${BASE}/suppliers`, {
      method: 'POST',
      headers: authHeaders(token),
      body: JSON.stringify(data),
    });
    return json<Supplier>(res);
  },

  async triggerScrape(supplierId: number, token: string): Promise<{ supplierId: number; status: string }> {
    const res = await fetch(`${BASE}/suppliers/${supplierId}/catalog/scrape`, {
      method: 'POST',
      headers: authHeaders(token),
    });
    return json<{ supplierId: number; status: string }>(res);
  },

  async uploadCatalog(supplierId: number, file: File, token: string): Promise<{ supplierId: number; status: string }> {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch(`${BASE}/suppliers/${supplierId}/catalog/upload`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    });
    return json<{ supplierId: number; status: string }>(res);
  },

  async uploadRfp(
    file: File,
    supplierIds: number[],
    token: string
  ): Promise<{ rfpId: number }> {
    const form = new FormData();
    form.append('file', file);
    supplierIds.forEach(id => form.append('supplierIds', String(id)));
    const res = await fetch(`${BASE}/rfp/upload`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    });
    return json<{ rfpId: number }>(res);
  },

  async triggerMatch(rfpId: number, token: string): Promise<{ jobId: string }> {
    const res = await fetch(`${BASE}/rfp/${rfpId}/match`, {
      method: 'POST',
      headers: authHeaders(token),
    });
    return json<{ jobId: string }>(res);
  },

  async getReport(rfpId: number, token: string): Promise<RfpReport> {
    const res = await fetch(`${BASE}/rfp/${rfpId}/report`, {
      headers: authHeaders(token),
    });
    return json<RfpReport>(res);
  },

  admin: {
    async listUsers(token: string): Promise<AdminUser[]> {
      const res = await fetch(`${BASE}/admin/users`, { headers: authHeaders(token) });
      return json<AdminUser[]>(res);
    },
    async deleteUser(id: number, token: string): Promise<void> {
      const res = await fetch(`${BASE}/admin/users/${id}`, {
        method: 'DELETE',
        headers: authHeaders(token),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({})) as { error?: string };
        throw new Error(body.error ?? `API error ${res.status}`);
      }
    },
    async listProducts(
      token: string,
      opts?: { page?: number; size?: number; q?: string; supplierId?: number }
    ): Promise<PageResult<AdminProduct>> {
      const params = new URLSearchParams();
      if (opts?.page !== undefined) params.set('page', String(opts.page));
      if (opts?.size !== undefined) params.set('size', String(opts.size));
      if (opts?.q) params.set('q', opts.q);
      if (opts?.supplierId !== undefined) params.set('supplierId', String(opts.supplierId));
      const res = await fetch(`${BASE}/admin/products?${params}`, {
        headers: authHeaders(token),
      });
      return json<PageResult<AdminProduct>>(res);
    },
    async listTenders(token: string): Promise<AdminTender[]> {
      const res = await fetch(`${BASE}/admin/tenders`, { headers: authHeaders(token) });
      return json<AdminTender[]>(res);
    },
    async listIngests(supplierId: number, token: string): Promise<IngestRecord[]> {
      const res = await fetch(`${BASE}/suppliers/${supplierId}/ingests`, { headers: authHeaders(token) });
      return json<IngestRecord[]>(res);
    },
  },

  crawl: {
    async listRuns(supplierId: number, token: string): Promise<CrawlRunSummary[]> {
      const res = await fetch(`${BASE}/suppliers/${supplierId}/crawl-runs`, { headers: authHeaders(token) });
      return json<CrawlRunSummary[]>(res);
    },
    async getRun(runId: number, token: string): Promise<CrawlRunDetail> {
      const res = await fetch(`${BASE}/crawl-runs/${runId}`, { headers: authHeaders(token) });
      return json<CrawlRunDetail>(res);
    },
    async resume(runId: number, token: string): Promise<void> {
      const res = await fetch(`${BASE}/crawl-runs/${runId}/resume`, {
        method: 'POST',
        headers: authHeaders(token),
      });
      if (!res.ok) throw new Error(`API error ${res.status}`);
    },
    async cancel(runId: number, token: string): Promise<void> {
      const res = await fetch(`${BASE}/crawl-runs/${runId}/cancel`, {
        method: 'POST',
        headers: authHeaders(token),
      });
      if (!res.ok) throw new Error(`API error ${res.status}`);
    },
    async retryFailed(runId: number, token: string): Promise<void> {
      const res = await fetch(`${BASE}/crawl-runs/${runId}/retry-failed`, {
        method: 'POST',
        headers: authHeaders(token),
      });
      if (!res.ok) throw new Error(`API error ${res.status}`);
    },
  },
};
