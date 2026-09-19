import type { Supplier, RfpReport, AdminUser, AdminProduct, AdminTender, PageResult, IngestRecord, SelectedProduct, ProposalAlternative, ProposalLine, Proposal, ProductSearchResult, CrawlRunSummary, CrawlRunDetail, TenderSummary } from './types';

const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

// Our handlers return { error: "<message>" }. Spring's default error body also has an
// `error` field, but it only repeats the status text, so it's recognised by `path` and skipped.
async function serverErrorText(res: Response): Promise<string | null> {
  try {
    const body = JSON.parse(await res.text());
    if (body && typeof body.error === 'string' && body.path === undefined) return body.error;
  } catch {
    /* no body, or not JSON */
  }
  return null;
}

async function apiError(res: Response): Promise<Error> {
  if (res.status === 401) return new Error('Your session has expired. Please log in again.');
  if (res.status === 403) return new Error('You do not have permission for this. Admin access is required.');
  const detail = await serverErrorText(res);
  return new Error(detail ? `${detail} (API error ${res.status})` : `API error ${res.status}`);
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw await apiError(res);
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

  async triggerMatch(rfpId: number, token: string, supplierIds?: number[]): Promise<{ jobId: string }> {
    const res = await fetch(`${BASE}/rfp/${rfpId}/match`, {
      method: 'POST',
      headers: authHeaders(token),
      body: supplierIds !== undefined ? JSON.stringify({ supplierIds }) : undefined,
    });
    return json<{ jobId: string }>(res);
  },

  async getReport(rfpId: number, token: string): Promise<RfpReport> {
    const res = await fetch(`${BASE}/rfp/${rfpId}/report`, {
      headers: authHeaders(token),
    });
    return json<RfpReport>(res);
  },

  proposals: {
    async generate(rfpId: number, token: string): Promise<{ status: string }> {
      const res = await fetch(`${BASE}/rfp/${rfpId}/proposals`, {
        method: 'POST',
        headers: authHeaders(token),
      });
      return json<{ status: string }>(res);
    },
    async list(rfpId: number, token: string): Promise<Proposal[]> {
      const res = await fetch(`${BASE}/rfp/${rfpId}/proposals`, {
        headers: authHeaders(token),
      });
      return json<Proposal[]>(res);
    },
    async override(
      rfpId: number, proposalId: number, lineId: number, productId: number, token: string
    ): Promise<{ status: string }> {
      const res = await fetch(`${BASE}/rfp/${rfpId}/proposals/${proposalId}/lines/${lineId}`, {
        method: 'PATCH',
        headers: authHeaders(token),
        body: JSON.stringify({ productId }),
      });
      return json<{ status: string }>(res);
    },
    async search(
      rfpId: number, proposalId: number, lineId: number, q: string, token: string
    ): Promise<ProductSearchResult[]> {
      const params = new URLSearchParams({ q });
      const res = await fetch(
        `${BASE}/rfp/${rfpId}/proposals/${proposalId}/lines/${lineId}/search?${params}`,
        { headers: authHeaders(token) }
      );
      return json<ProductSearchResult[]>(res);
    },
  },

  myRfps: {
    async list(token: string): Promise<TenderSummary[]> {
      const res = await fetch(`${BASE}/rfp`, { headers: authHeaders(token) });
      return json<TenderSummary[]>(res);
    },
    async delete(rfpId: number, token: string): Promise<void> {
      const res = await fetch(`${BASE}/rfp/${rfpId}`, {
        method: 'DELETE',
        headers: authHeaders(token),
      });
      if (!res.ok) throw await apiError(res);
    },
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
      if (!res.ok) throw await apiError(res);
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
      if (!res.ok) throw await apiError(res);
    },
    async cancel(runId: number, token: string): Promise<void> {
      const res = await fetch(`${BASE}/crawl-runs/${runId}/cancel`, {
        method: 'POST',
        headers: authHeaders(token),
      });
      if (!res.ok) throw await apiError(res);
    },
    async retryFailed(runId: number, token: string): Promise<void> {
      const res = await fetch(`${BASE}/crawl-runs/${runId}/retry-failed`, {
        method: 'POST',
        headers: authHeaders(token),
      });
      if (!res.ok) throw await apiError(res);
    },
  },
};
