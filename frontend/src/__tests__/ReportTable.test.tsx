import { render, screen } from '@testing-library/react';
import { ReportTable } from '../components/ReportTable';
import type { ReportItem } from '../lib/types';

const items: ReportItem[] = [
  { requiredInstrument: 'Oscilloscope 200MHz', matchedInstrument: 'Osc Pro 200', manualLink: 'https://example.com/manual.pdf', score: 92, status: 'MATCHED', price: 1200, currency: 'JOD' },
  { requiredInstrument: 'Signal Gen 1GHz', matchedInstrument: null, manualLink: null, score: 0, status: 'NOT_FOUND', price: null, currency: 'JOD' },
];

test('renders matched item with score', () => {
  render(<ReportTable items={items} />);
  expect(screen.getByText('Oscilloscope 200MHz')).toBeInTheDocument();
  expect(screen.getByText('92')).toBeInTheDocument();
  expect(screen.getByText('MATCHED')).toBeInTheDocument();
});

test('highlights NOT_FOUND items', () => {
  render(<ReportTable items={items} />);
  const notFound = screen.getByText('NOT_FOUND');
  expect(notFound.className).toContain('text-red');
});
