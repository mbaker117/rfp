export interface Supplier {
  id: number;
  name: string;
  officialWebsite?: string;
  contactEmail?: string;
  contactPhone?: string;
  country?: string;
  description?: string;
  categories: string[];
  scrapeStatus: string;
  crawlAllowedHosts?: string[];
  crawlThrottleMs?: number | null;
  crawlMaxConcurrency?: number | null;
  crawlBatchPages?: number | null;
  crawlMaxUrls?: number | null;
}

export interface AttributeVerdict {
  attr: string;
  required: unknown;
  offered: unknown;
  verdict: 'COMPLIANT' | 'DEVIATION' | 'UNVERIFIABLE';
}

export interface Alternative {
  productId: number;
  name: string;
  mpn: string | null;
  score: number;
  attributeVerdicts: AttributeVerdict[];
}

export interface MatchResultItem {
  lineId: number;
  description: string;
  qty: number | null;
  matchType: 'exact' | 'spec' | null;
  score: number;
  status: 'matched' | 'partial' | 'not_found';
  matchedProduct: string | null;
  mpn: string | null;
  attributeVerdicts: AttributeVerdict[];
  alternatives: Alternative[];
}

export interface RfpReport {
  rfpId: number;
  status: string;
  lineCount: number;
  items: MatchResultItem[];
}

export interface JobStatus {
  id: number;
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';
  error?: string;
}

export interface AdminUser {
  id: number;
  username: string;
  role: string;
}

export interface AdminProduct {
  id: number;
  name: string;
  mpn: string | null;
  supplierId: number;
  supplierName: string;
  productClass: string | null;
  isStale: boolean;
  source: string | null;
  attributes: string | null;
}

export interface IngestRecord {
  id: number;
  kind: string;
  filename: string | null;
  status: string;
  startedAt: string | null;
  finishedAt: string | null;
  errorMsg: string | null;
  itemsFound: number | null;
  stepLog: string | null;
}

export interface AdminTender {
  id: number;
  filename: string;
  userId: number;
  status: string;
  createdAt: string;
}

export interface PageResult<T> {
  content: T[];
  totalElements: number;
}

export interface CrawlRunSummary {
  id: number;
  status: 'QUEUED' | 'CRAWLING' | 'COMPLETE' | 'PARTIAL' | 'FAILED' | 'CANCELLED';
  mode: string;
  discoveredUrlCount: number;
  fetchedUrlCount: number;
  failedUrlCount: number;
  observedProductCount: number;
  insertedProductCount: number;
  updatedProductCount: number;
  completenessScore: number | null;
  completenessReason: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
}

export interface CrawlCounts {
  discovered: number;
  fetched: number;
  failed: number;
  rejected: number;
  retried: number;
  pending: number;
  observedProducts: number;
  insertedProducts: number;
  updatedProducts: number;
  unchangedProducts: number;
  staleProducts: number;
}

export interface Completeness {
  canReconcile: boolean;
  score: number | null;
  reason: string | null;
}

export interface CrawlRunDetail extends CrawlRunSummary {
  supplierId: number;
  configJson: string;
  counts: CrawlCounts;
  completeness: Completeness;
  batchCount: number;
  checkpointCount: number;
  cancellationRequested: boolean;
  failureCategory: string | null;
  failureDetails: string | null;
  heartbeatAt: string | null;
  updatedAt: string;
}

export interface ProductProvenance {
  canonicalSourceUrl: string | null;
  lastObservedAt: string | null;
  extractionMethod: string | null;
}
