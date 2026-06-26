export interface Company {
  id: number;
  name: string;
  scrapeStatus: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';
}

export interface ReportItem {
  requiredInstrument: string;
  matchedInstrument: string | null;
  manualLink: string | null;
  score: number | null;
  status: 'MATCHED' | 'PARTIAL' | 'NOT_FOUND' | null;
  price: number | null;
  currency: string;
}

export interface RfpReport {
  rfpId: number;
  status: string;
  items: ReportItem[];
}

export interface JobStatus {
  id: number;
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';
  error?: string;
}
