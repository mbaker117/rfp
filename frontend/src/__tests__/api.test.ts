import { api } from '../lib/api';

global.fetch = jest.fn();

describe('api.resolveCompanies', () => {
  beforeEach(() => {
    (fetch as jest.Mock).mockReset();
  });

  it('posts names and returns company array', async () => {
    (fetch as jest.Mock).mockResolvedValueOnce({
      ok: true,
      json: async () => ({ companies: [{ id: 1, name: 'Tektronix', scrapeStatus: 'DONE' }] }),
    });

    const result = await api.resolveCompanies(['Tektronix'], 'token');

    expect(result).toHaveLength(1);
    expect(result[0].name).toBe('Tektronix');
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining('/companies/resolve'),
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('throws on non-ok response', async () => {
    (fetch as jest.Mock).mockResolvedValueOnce({ ok: false, status: 500 });
    await expect(api.resolveCompanies(['X'], 'token')).rejects.toThrow('API error 500');
  });
});
