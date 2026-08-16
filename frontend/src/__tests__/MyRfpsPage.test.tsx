import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import MyRfpsPage from '@/src/app/my-rfps/page';
import { api } from '@/src/lib/api';
import { useAuth } from '@/src/hooks/useAuth';

jest.mock('@/src/lib/api');
jest.mock('@/src/hooks/useAuth');
jest.mock('@/src/components/SubmissionCard', () => ({
  SubmissionCard: ({ tender }: { tender: { filename: string } }) => (
    <div data-testid="submission-card">{tender.filename}</div>
  ),
}));

const mockApi = api as jest.Mocked<typeof api>;
const mockUseAuth = useAuth as jest.Mock;

const sampleTender = {
  id: 1,
  filename: 'instruments.pdf',
  status: 'done' as const,
  createdAt: '2026-08-17T09:00:00Z',
  lineCount: 3,
  proposalCount: 1,
  supplierIds: [],
};

describe('MyRfpsPage', () => {
  beforeEach(() => {
    mockUseAuth.mockReturnValue({ token: 'test-token', logout: jest.fn() });
  });

  afterEach(() => jest.clearAllMocks());

  it('shows loading skeleton initially', () => {
    mockApi.myRfps = { list: jest.fn().mockResolvedValue([]), delete: jest.fn() };
    render(<MyRfpsPage />);
    expect(screen.getByText(/loading/i)).toBeInTheDocument();
  });

  it('renders submission cards after load', async () => {
    mockApi.myRfps = {
      list: jest.fn().mockResolvedValue([sampleTender]),
      delete: jest.fn(),
    };
    render(<MyRfpsPage />);
    await waitFor(() =>
      expect(screen.getByTestId('submission-card')).toBeInTheDocument()
    );
    expect(screen.getByText('instruments.pdf')).toBeInTheDocument();
  });

  it('shows empty state when no tenders', async () => {
    mockApi.myRfps = { list: jest.fn().mockResolvedValue([]), delete: jest.fn() };
    render(<MyRfpsPage />);
    await waitFor(() =>
      expect(screen.getByText(/no submissions yet/i)).toBeInTheDocument()
    );
  });

  it('shows error state when list call fails', async () => {
    mockApi.myRfps = {
      list: jest.fn().mockRejectedValue(new Error('network error')),
      delete: jest.fn(),
    };
    render(<MyRfpsPage />);
    await waitFor(() =>
      expect(screen.getByText(/could not load/i)).toBeInTheDocument()
    );
  });
});
