import '@testing-library/jest-dom';
import { render, screen, fireEvent } from '@testing-library/react';
import { CrawlRunPanel } from '../components/CrawlRunPanel';
import type { CrawlRunDetail } from '../lib/types';

const baseCounts = {
  discovered: 5300,
  fetched: 4800,
  failed: 12,
  rejected: 50,
  retried: 3,
  pending: 0,
  observedProducts: 500,
  insertedProducts: 10,
  updatedProducts: 5,
  unchangedProducts: 485,
  staleProducts: 0,
};

const baseRun: CrawlRunDetail = {
  id: 1,
  status: 'PARTIAL',
  mode: 'FULL',
  startedAt: '2024-01-01T10:00:00Z',
  finishedAt: '2024-01-01T11:00:00Z',
  createdAt: '2024-01-01T09:00:00Z',
  supplierId: 42,
  configJson: '{}',
  counts: baseCounts,
  completeness: {
    canReconcile: false,
    score: 90,
    reason: 'Reached max URL limit',
  },
  batchCount: 5,
  checkpointCount: 10,
  cancellationRequested: false,
  failureCategory: null,
  failureDetails: null,
  heartbeatAt: null,
  updatedAt: '2024-01-01T11:00:00Z',
};

const partialRun: CrawlRunDetail = { ...baseRun, status: 'PARTIAL' };

const completeRun: CrawlRunDetail = {
  ...baseRun,
  status: 'COMPLETE',
  counts: { ...baseCounts, failed: 0 },
};

const crawlingRun: CrawlRunDetail = {
  ...baseRun,
  status: 'CRAWLING',
  finishedAt: null,
};

const failedRun: CrawlRunDetail = {
  ...baseRun,
  status: 'FAILED',
  failureCategory: 'NETWORK',
  failureDetails: 'Connection refused',
};

test('warns that a partial crawl cannot stale products', () => {
  render(<CrawlRunPanel run={partialRun} onAction={jest.fn()} />);
  expect(screen.getByText(/partial crawl cannot mark products stale/i)).toBeInTheDocument();
  expect(screen.getByText('5,300 discovered')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /resume/i })).toBeEnabled();
});

test('COMPLETE run has no Resume button', () => {
  render(<CrawlRunPanel run={completeRun} onAction={jest.fn()} />);
  expect(screen.queryByRole('button', { name: /resume/i })).not.toBeInTheDocument();
  expect(screen.queryByText(/partial crawl cannot mark products stale/i)).not.toBeInTheDocument();
});

test('CRAWLING run has Cancel button but no Resume button', () => {
  render(<CrawlRunPanel run={crawlingRun} onAction={jest.fn()} />);
  expect(screen.getByRole('button', { name: /cancel/i })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /resume/i })).not.toBeInTheDocument();
});

test('Resume button triggers onAction callback', () => {
  const onAction = jest.fn();
  render(<CrawlRunPanel run={partialRun} onAction={onAction} />);
  fireEvent.click(screen.getByRole('button', { name: /resume/i }));
  expect(onAction).toHaveBeenCalledWith('resume');
});

test('Cancel button triggers onAction callback', () => {
  const onAction = jest.fn();
  render(<CrawlRunPanel run={crawlingRun} onAction={onAction} />);
  fireEvent.click(screen.getByRole('button', { name: /cancel/i }));
  expect(onAction).toHaveBeenCalledWith('cancel');
});

test('Retry Failed button triggers onAction callback', () => {
  const onAction = jest.fn();
  render(<CrawlRunPanel run={partialRun} onAction={onAction} />);
  fireEvent.click(screen.getByRole('button', { name: /retry failed/i }));
  expect(onAction).toHaveBeenCalledWith('retry-failed');
});

test('FAILED run shows Resume and Retry Failed buttons', () => {
  render(<CrawlRunPanel run={failedRun} onAction={jest.fn()} />);
  expect(screen.getByRole('button', { name: /resume/i })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /retry failed/i })).toBeInTheDocument();
  expect(screen.getByText('Connection refused')).toBeInTheDocument();
});

test('shows fetched count', () => {
  render(<CrawlRunPanel run={partialRun} onAction={jest.fn()} />);
  expect(screen.getByText('4,800 fetched')).toBeInTheDocument();
});
