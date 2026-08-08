import type { Supplier, RfpReport, AdminUser, AdminProduct, AdminTender, PageResult } from './types';

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

  async uploadCatalog(supplierId: number, file: File, token: string): Promise<{ jobId: string }> {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch(`${BASE}/suppliers/${supplierId}/catalog/upload`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    });
    return json<{ jobId: string }>(res);
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
      if (!res.ok) throw new Error(`API error ${res.status}`);
    },
    async listProducts(
      token: string,
      opts?: { page?: number; size?: number; q?: string }
    ): Promise<PageResult<AdminProduct>> {
      const params = new URLSearchParams();
      if (opts?.page !== undefined) params.set('page', String(opts.page));
      if (opts?.size !== undefined) params.set('size', String(opts.size));
      if (opts?.q) params.set('q', opts.q);
      const res = await fetch(`${BASE}/admin/products?${params}`, {
        headers: authHeaders(token),
      });
      return json<PageResult<AdminProduct>>(res);
    },
    async listTenders(token: string): Promise<AdminTender[]> {
      const res = await fetch(`${BASE}/admin/tenders`, { headers: authHeaders(token) });
      return json<AdminTender[]>(res);
    },
  },
};
