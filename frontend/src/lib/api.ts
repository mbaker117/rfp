import type { Company, RfpReport } from './types';

const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`API error ${res.status}`);
  return res.json();
}

export const api = {
  async resolveCompanies(names: string[], token: string): Promise<Company[]> {
    const res = await fetch(`${BASE}/companies/resolve`, {
      method: 'POST',
      headers: authHeaders(token),
      body: JSON.stringify({ names }),
    });
    const data = await json<{ companies: Company[] }>(res);
    return data.companies;
  },

  async uploadRfp(file: File, companyIds: number[], token: string): Promise<{ rfpId: number }> {
    const form = new FormData();
    form.append('file', file);
    companyIds.forEach(id => form.append('companyIds', String(id)));
    const res = await fetch(`${BASE}/rfp/upload`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    });
    return json<{ rfpId: number }>(res);
  },

  async triggerMatch(rfpId: number, companyIds: number[], token: string): Promise<{ jobId: string }> {
    const res = await fetch(`${BASE}/rfp/${rfpId}/match`, {
      method: 'POST',
      headers: authHeaders(token),
      body: JSON.stringify({ companyIds }),
    });
    return json<{ jobId: string }>(res);
  },

  async getReport(rfpId: number, token: string): Promise<RfpReport> {
    const res = await fetch(`${BASE}/rfp/${rfpId}/report`, {
      headers: authHeaders(token),
    });
    return json<RfpReport>(res);
  },
};
