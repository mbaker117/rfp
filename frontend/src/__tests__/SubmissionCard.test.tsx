import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { SubmissionCard } from '@/src/components/SubmissionCard';
import { api } from '@/src/lib/api';

jest.mock('@/src/lib/api');
const mockApi = api as jest.Mocked<typeof api>;

const baseTender = {
  id: 1,
  filename: 'test.pdf',
  status: 'done' as const,
  createdAt: '2026-08-17T10:00:00Z',
  lineCount: 5,
  proposalCount: 2,
  supplierIds: [10, 20],
};

describe('SubmissionCard', () => {
  beforeEach(() => {
    // SupplierCombobox calls listSuppliers when it mounts (inside the re-run panel)
    mockApi.listSuppliers = jest.fn().mockResolvedValue([]);
  });
  afterEach(() => jest.clearAllMocks());

  it('renders filename and status badge', () => {
    render(
      <SubmissionCard tender={baseTender} token="tok" onDeleted={() => {}} />
    );
    expect(screen.getByText('test.pdf')).toBeInTheDocument();
    expect(screen.getByText('done')).toBeInTheDocument();
  });

  it('renders line and proposal counts', () => {
    render(
      <SubmissionCard tender={baseTender} token="tok" onDeleted={() => {}} />
    );
    expect(screen.getByText('5 lines')).toBeInTheDocument();
    expect(screen.getByText('2 proposals')).toBeInTheDocument();
  });

  it('View Report link is enabled for done status', () => {
    render(
      <SubmissionCard tender={baseTender} token="tok" onDeleted={() => {}} />
    );
    const link = screen.getByRole('link', { name: /view report/i });
    expect(link).toHaveAttribute('href', '/rfp/1');
  });

  it('View Report link is disabled while extracting', () => {
    render(
      <SubmissionCard
        tender={{ ...baseTender, status: 'extracting' }}
        token="tok"
        onDeleted={() => {}}
      />
    );
    expect(screen.queryByRole('link', { name: /view report/i })).toBeNull();
    expect(screen.getByText(/view report/i)).toBeInTheDocument();
  });

  it('Re-run panel expands on click', async () => {
    render(
      <SubmissionCard tender={baseTender} token="tok" onDeleted={() => {}} />
    );
    fireEvent.click(screen.getByRole('button', { name: /re-run matching/i }));
    expect(screen.getByRole('button', { name: /^run$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /cancel/i })).toBeInTheDocument();
  });

  it('Delete calls api.myRfps.delete and invokes onDeleted', async () => {
    const onDeleted = jest.fn();
    mockApi.myRfps = {
      list: jest.fn(),
      delete: jest.fn().mockResolvedValue(undefined),
    };
    window.confirm = jest.fn().mockReturnValue(true);

    render(
      <SubmissionCard tender={baseTender} token="tok" onDeleted={onDeleted} />
    );
    fireEvent.click(screen.getByRole('button', { name: /delete/i }));
    await waitFor(() => expect(mockApi.myRfps.delete).toHaveBeenCalledWith(1, 'tok'));
    expect(onDeleted).toHaveBeenCalledWith(1);
  });

  it('Delete does not call api when user cancels confirm', async () => {
    mockApi.myRfps = {
      list: jest.fn(),
      delete: jest.fn(),
    };
    window.confirm = jest.fn().mockReturnValue(false);

    render(
      <SubmissionCard tender={baseTender} token="tok" onDeleted={() => {}} />
    );
    fireEvent.click(screen.getByRole('button', { name: /delete/i }));
    expect(mockApi.myRfps.delete).not.toHaveBeenCalled();
  });
});
