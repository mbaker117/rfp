import '@testing-library/jest-dom';
import { render, screen } from '@testing-library/react';
import { ProductAttributePanel } from '../components/ProductAttributePanel';
import type { ProductProvenance } from '../lib/types';

test('renders description, safe manual link, and remaining attributes', () => {
  render(
    <ProductAttributePanel
      attributesJson={JSON.stringify({
        description: 'Portable true-RMS multimeter.',
        manualLink: 'https://example.com/manual.pdf',
        max_voltage: 1000,
      })}
    />,
  );

  expect(screen.getByText('Portable true-RMS multimeter.')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /product manual/i })).toHaveAttribute(
    'href',
    'https://example.com/manual.pdf',
  );
  expect(screen.getByText('max_voltage')).toBeInTheDocument();
  expect(screen.getByText('1000')).toBeInTheDocument();
});

test('does not render a link for an unsafe manual URL', () => {
  render(
    <ProductAttributePanel
      attributesJson={JSON.stringify({
        description: 'A product description.',
        manualLink: 'javascript:alert(document.domain)',
      })}
    />,
  );

  expect(screen.queryByRole('link')).not.toBeInTheDocument();
  expect(screen.getByText('A product description.')).toBeInTheDocument();
});

test('renders nothing for malformed attributes JSON', () => {
  const { container } = render(<ProductAttributePanel attributesJson="not-json" />);
  expect(container).toBeEmptyDOMElement();
});

test('shows source and extraction method in product details', () => {
  const provenance: ProductProvenance = {
    canonicalSourceUrl: 'https://example.com/product/123',
    lastObservedAt: '2024-06-01T00:00:00Z',
    extractionMethod: 'JSON-LD',
  };
  render(<ProductAttributePanel attributesJson="{}" provenance={provenance} />);
  expect(screen.getByRole('link', { name: /source/i })).toHaveAttribute(
    'href',
    provenance.canonicalSourceUrl,
  );
  expect(screen.getByText('JSON-LD')).toBeInTheDocument();
});

test('does not render source link for non-http URL', () => {
  const provenance: ProductProvenance = {
    canonicalSourceUrl: 'ftp://example.com/product/123',
    lastObservedAt: null,
    extractionMethod: 'LLM',
  };
  render(<ProductAttributePanel attributesJson="{}" provenance={provenance} />);
  expect(screen.queryByRole('link', { name: /source/i })).not.toBeInTheDocument();
  expect(screen.getByText('LLM')).toBeInTheDocument();
});
