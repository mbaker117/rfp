import '@testing-library/jest-dom';
import { render, screen, fireEvent } from '@testing-library/react';
import { ReportTable } from '../components/ReportTable';
import type { MatchResultItem } from '../lib/types';

const items: MatchResultItem[] = [
  {
    lineId: 1,
    description: 'Oscilloscope 200MHz',
    qty: 3,
    matchType: 'spec',
    score: 92,
    status: 'matched',
    matchedProduct: 'Osc Pro 200',
    mpn: 'OP-200',
    attributeVerdicts: [
      { attr: 'bandwidth', required: 200, offered: 250, verdict: 'COMPLIANT' },
      { attr: 'channels', required: 4, offered: 2, verdict: 'DEVIATION' },
    ],
    alternatives: [
      {
        productId: 9,
        name: 'Osc Lite 200',
        mpn: 'OL-200',
        score: 60,
        attributeVerdicts: [],
      },
    ],
  },
  {
    lineId: 2,
    description: 'Signal Gen 1GHz',
    qty: null,
    matchType: null,
    score: 0,
    status: 'not_found',
    matchedProduct: null,
    mpn: null,
    attributeVerdicts: [],
    alternatives: [],
  },
];

test('renders matched item with score, product and mpn', () => {
  render(<ReportTable items={items} />);
  expect(screen.getByText('Oscilloscope 200MHz')).toBeInTheDocument();
  expect(screen.getByText('92')).toBeInTheDocument();
  expect(screen.getByText('matched')).toBeInTheDocument();
  expect(screen.getByText('Osc Pro 200')).toBeInTheDocument();
  expect(screen.getByText('OP-200')).toBeInTheDocument();
  expect(screen.getByText('spec')).toBeInTheDocument();
  expect(screen.getByText('3')).toBeInTheDocument();
});

test('renders attribute verdicts per attribute after expanding row', () => {
  render(<ReportTable items={items} />);
  // Expand the first row by clicking it
  fireEvent.click(screen.getByText('Oscilloscope 200MHz').closest('tr')!);
  expect(screen.getByText('bandwidth')).toBeInTheDocument();
  expect(screen.getByText('channels')).toBeInTheDocument();
  expect(screen.getByText('COMPLIANT')).toBeInTheDocument();
  expect(screen.getByText('DEVIATION')).toBeInTheDocument();
});

test('renders alternatives with their score after expanding row', () => {
  render(<ReportTable items={items} />);
  // Expand the first row by clicking it
  fireEvent.click(screen.getByText('Oscilloscope 200MHz').closest('tr')!);
  expect(screen.getByText(/Osc Lite 200/)).toBeInTheDocument();
  expect(screen.getByText('score: 60')).toBeInTheDocument();
});

test('highlights not_found items with slate badge', () => {
  render(<ReportTable items={items} />);
  // StatusBadge replaces underscore with space
  const notFound = screen.getByText('not found');
  expect(notFound.className).toContain('text-slate-600');
});

test('renders no price column', () => {
  render(<ReportTable items={items} />);
  expect(screen.queryByText(/Price/i)).not.toBeInTheDocument();
});

test('parses attributeVerdicts and alternatives delivered as JSON strings', () => {
  const raw = [
    {
      ...items[0],
      attributeVerdicts: JSON.stringify(items[0].attributeVerdicts),
      alternatives: JSON.stringify(items[0].alternatives),
    },
  ] as unknown as MatchResultItem[];

  render(<ReportTable items={raw} />);
  // Expand the row to see the parsed content
  fireEvent.click(screen.getByText('Oscilloscope 200MHz').closest('tr')!);
  expect(screen.getByText('bandwidth')).toBeInTheDocument();
  expect(screen.getByText(/Osc Lite 200/)).toBeInTheDocument();
});
