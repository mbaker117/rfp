import '@testing-library/jest-dom';
import { render, screen } from '@testing-library/react';
import { ProductAttributePanel } from '../components/ProductAttributePanel';

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
