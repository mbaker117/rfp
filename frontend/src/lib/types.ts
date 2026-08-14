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

export interface SelectedProduct {
  id: number;
  name: string;
  mpn: string | null;
  price: number | null;
  currency: string;
  supplierName: string;
  description: string | null;
  manualLink: string | null;
}

export interface ProposalAlternative {
  productId: number;
  name: string;
  mpn: string | null;
  score: number;
  price: number | null;
  currency: string;
  description: string | null;
  supplierName: string;
}

export interface ProposalLine {
  lineId: number;
  description: string | null;
  qty: number | null;
  matchScore: number | null;
  selectedProduct: SelectedProduct | null;
  acceptanceProbability: number | null;
  llmReasoning: string | null;
  isOverridden: boolean;
  alternatives: ProposalAlternative[];
}

export interface Proposal {
  id: number;
  variant: 'PERFECT' | 'BEST_ACCEPTANCE' | 'CHEAPEST';
  status: 'GENERATING' | 'READY' | 'FAILED';
  acceptanceRate: number | null;
  matchScore: number | null;
  isComplete: boolean;
  lines: ProposalLine[];
}

export interface ProductSearchResult {
  productId: number;
  name: string;
  mpn: string | null;
  score: number;
  price: number | null;
  currency: string;
  supplierName: string;
}
