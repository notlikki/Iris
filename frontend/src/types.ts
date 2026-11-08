export type MatchMethod = 'CHECKSUM' | 'LSB' | 'SPREAD' | 'PHASH' | 'NONE';

export interface ImageRecordDto {
  id: number;
  token: string;
  originalFilename: string;
  contentType: string;
  width: number;
  height: number;
  originalSize: number;
  signedSize: number;
  originalChecksum: string;
  signedChecksum: string;
  perceptualHash: string;
  lsbEmbedded: boolean;
  lsbCopies: number;
  robustEmbedded: boolean;
  robustCopies: number;
  createdAt: string;
  originalUrl: string;
  signedUrl: string;
  downloadUrl: string;
  signedDownloadUrl: string;
}

export interface VerificationResponseDto {
  id: number;
  matched: boolean;
  method: MatchMethod;
  token?: string | null;
  record?: ImageRecordDto | null;
  exactMatch: boolean;
  dimensionMatch: boolean;
  perceptualDistance?: number | null;
  similarity?: number | null;
  meanDiff?: number | null;
  changedPercent?: number | null;
  candidateUrl: string;
  diffUrl?: string | null;
  originalUrl?: string | null;
  signedUrl?: string | null;
  restoreUrl?: string | null;
}

export interface AuditEventDto {
  id: number;
  eventType: string;
  recordId?: number | null;
  summary: string;
  details?: string | null;
  timestamp: string;
}

export interface ApiError {
  status: number;
  error: string;
  message: string;
  timestamp: string;
  details?: Record<string, string>;
}
