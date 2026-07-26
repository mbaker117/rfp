import { api } from '../lib/api';

global.fetch = jest.fn();

const mockOk = (body: unknown) =>
  (fetch as jest.Mock).mockResolvedValueOnce({ ok: true, json: async () => body });

beforeEach(() => {
  (fetch as jest.Mock).mockReset();
});

describe('api.listSuppliers', () => {
  it('GETs /suppliers and returns the supplier array', async () => {
    mockOk([{ id: 1, name: 'Tektronix', categories: [], scrapeStatus: 'DONE' }]);

    const result = await api.listSuppliers('token');

    expect(result).toHaveLength(1);
    expect(result[0].name).toBe('Tektronix');
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining('/suppliers'),
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer token' }),
      }),
    );
  });

  it('throws on non-ok response', async () => {
    (fetch as jest.Mock).mockResolvedValueOnce({ ok: false, status: 500 });
    await expect(api.listSuppliers('token')).rejects.toThrow('API error 500');
  });
});

describe('api.registerSupplier', () => {
  it('POSTs the supplier payload', async () => {
    mockOk({ id: 2, name: 'Fluke', categories: [], scrapeStatus: 'PENDING' });

    const result = await api.registerSupplier(
      { name: 'Fluke', officialWebsite: 'https://fluke.com' },
      'token',
    );

    expect(result.id).toBe(2);
    const [, init] = (fetch as jest.Mock).mock.calls[0];
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({
      name: 'Fluke',
      officialWebsite: 'https://fluke.com',
    });
  });
});

describe('api.uploadRfp', () => {
  it('POSTs multipart form data with each supplier id', async () => {
    mockOk({ rfpId: 7 });
    const file = new File(['content'], 'rfp.pdf', { type: 'application/pdf' });

    const result = await api.uploadRfp(file, [1, 2], 'token');

    expect(result.rfpId).toBe(7);
    const [url, init] = (fetch as jest.Mock).mock.calls[0];
    expect(url).toContain('/rfp/upload');
    expect(init.method).toBe('POST');
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).getAll('supplierIds')).toEqual(['1', '2']);
    // Content-Type must be left to the browser so the multipart boundary is set
    expect(init.headers).toEqual({ Authorization: 'Bearer token' });
  });
});

describe('api.triggerMatch', () => {
  it('POSTs to the match endpoint with only the rfp id', async () => {
    mockOk({ jobId: 'rfp-7-match' });

    const result = await api.triggerMatch(7, 'token');

    expect(result.jobId).toBe('rfp-7-match');
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining('/rfp/7/match'),
      expect.objectContaining({ method: 'POST' }),
    );
  });
});

describe('api.getReport', () => {
  it('GETs the report and returns lowercase status', async () => {
    mockOk({ rfpId: 7, status: 'done', items: [] });

    const report = await api.getReport(7, 'token');

    expect(report.status).toBe('done');
    expect(report.items).toEqual([]);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining('/rfp/7/report'),
      expect.anything(),
    );
  });

  it('throws on non-ok response', async () => {
    (fetch as jest.Mock).mockResolvedValueOnce({ ok: false, status: 404 });
    await expect(api.getReport(7, 'token')).rejects.toThrow('API error 404');
  });
});
