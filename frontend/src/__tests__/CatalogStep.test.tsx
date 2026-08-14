import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import Home from '../app/page';

// Mock Next.js navigation
const mockPush = jest.fn();
jest.mock('next/navigation', () => ({
  useRouter: () => ({ push: mockPush }),
  usePathname: () => '/',
}));

// Mock useAuth to always return a token
jest.mock('../hooks/useAuth', () => ({
  useAuth: () => ({ token: 'test-token' }),
}));

// Mock api
jest.mock('../lib/api', () => ({
  api: {
    listSuppliers: jest.fn().mockResolvedValue([]),
    registerSupplier: jest.fn().mockResolvedValue({ id: 99, name: 'my-catalog', scrapeStatus: 'PENDING', categories: [] }),
    uploadCatalog: jest.fn().mockResolvedValue({ supplierId: 99, status: 'ingest_started' }),
    uploadRfp: jest.fn().mockResolvedValue({ rfpId: 1 }),
    triggerMatch: jest.fn().mockResolvedValue({ jobId: 'rfp-1-match' }),
    getReport: jest.fn(),
  },
}));

// Mock child components to keep tests focused
jest.mock('../components/JobStatusPoller', () => ({
  JobStatusPoller: () => <div data-testid="poller" />,
}));
jest.mock('../components/SupplierCombobox', () => ({
  SupplierCombobox: ({ onChange }: { onChange: (s: unknown[]) => void }) => (
    <button data-testid="combobox" onClick={() => onChange([{ id: 1, name: 'Acme', catalogFile: null }])}>
      Add Acme
    </button>
  ),
}));
jest.mock('../components/FileUpload', () => ({
  FileUpload: ({ onFile }: { onFile: (f: File) => void }) => (
    <button
      data-testid="file-upload-btn"
      onClick={() => onFile(new File(['rfp'], 'rfp.pdf', { type: 'application/pdf' }))}
    >
      Upload RFP
    </button>
  ),
}));

describe('Home page — Step 2 catalog drop zone', () => {
  beforeEach(() => jest.clearAllMocks());

  it('shows the catalog drop zone by default', () => {
    render(<Home />);
    expect(screen.getByText(/upload supplier catalog/i)).toBeInTheDocument();
    expect(screen.getByTestId('catalog-drop-zone')).toBeInTheDocument();
  });

  it('shows disclosure toggle for existing suppliers', () => {
    render(<Home />);
    expect(screen.getByText(/or choose an existing supplier/i)).toBeInTheDocument();
  });

  it('reveals SupplierCombobox when disclosure toggle is clicked', () => {
    render(<Home />);
    fireEvent.click(screen.getByText(/or choose an existing supplier/i));
    expect(screen.getByTestId('combobox')).toBeInTheDocument();
  });

  it('calls registerSupplier and uploadCatalog when a file is dropped', async () => {
    const { api } = require('../lib/api');
    render(<Home />);
    const dropZone = screen.getByTestId('catalog-drop-zone');
    const file = new File(['content'], 'my-catalog.pdf', { type: 'application/pdf' });
    fireEvent.drop(dropZone, { dataTransfer: { files: [file] } });

    await waitFor(() => {
      expect(api.registerSupplier).toHaveBeenCalledWith({ name: 'my-catalog' }, 'test-token');
      expect(api.uploadCatalog).toHaveBeenCalledWith(99, file, 'test-token');
    });
  });

  it('shows confirmation chip after successful catalog upload', async () => {
    render(<Home />);
    const dropZone = screen.getByTestId('catalog-drop-zone');
    const file = new File(['content'], 'my-catalog.pdf', { type: 'application/pdf' });
    fireEvent.drop(dropZone, { dataTransfer: { files: [file] } });

    await waitFor(() => {
      expect(screen.getByText('my-catalog.pdf')).toBeInTheDocument();
    });
  });

  it('removes catalog supplier when chip ✕ is clicked', async () => {
    render(<Home />);
    const dropZone = screen.getByTestId('catalog-drop-zone');
    const file = new File(['content'], 'my-catalog.pdf', { type: 'application/pdf' });
    fireEvent.drop(dropZone, { dataTransfer: { files: [file] } });

    await waitFor(() => screen.getByText('my-catalog.pdf'));
    fireEvent.click(screen.getByLabelText('Remove catalog'));
    expect(screen.queryByText('my-catalog.pdf')).not.toBeInTheDocument();
    // After removal, the drop zone should be back
    expect(screen.getByTestId('catalog-drop-zone')).toBeInTheDocument();
  });

  it('enables Analyze button when catalog file is uploaded and RFP is selected', async () => {
    render(<Home />);

    // Initially, Analyze button should be disabled
    expect(screen.getByRole('button', { name: /analyze rfp/i })).toBeDisabled();

    // Step 1: Drop a catalog file
    const dropZone = screen.getByTestId('catalog-drop-zone');
    const catalogFile = new File(['content'], 'my-catalog.pdf', { type: 'application/pdf' });
    fireEvent.drop(dropZone, { dataTransfer: { files: [catalogFile] } });

    // Wait for catalog upload to complete
    await waitFor(() => {
      expect(screen.getByText('my-catalog.pdf')).toBeInTheDocument();
    });

    // Step 2: Click the FileUpload button to set the RFP file
    fireEvent.click(screen.getByTestId('file-upload-btn'));

    // Step 3: Verify the Analyze button is now enabled
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /analyze rfp/i })).not.toBeDisabled();
    });
  });
});
