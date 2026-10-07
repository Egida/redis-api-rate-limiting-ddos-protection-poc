/** Read-only policy metadata served by GET /api/poc/policies (added for this console). */
export interface PolicySummary {
  id: string;
  method: string;
  path: string | null;
  limit: number | null;
  windowSeconds: number | null;
  identity: string;
  /** Absent on backends older than the managed-metadata change, so the table must tolerate null. */
  algorithm?: string | null;
  scope?: string | null;
  parameterSummary?: string | null;
  enabled?: boolean;
  version?: number;
  redisFailureMode: 'FAIL_OPEN' | 'FAIL_CLOSED';
  redisFailureModeLabel: string;
}

export interface PolicyResponse {
  source: string;
  editable: boolean;
  limiterEnabled: boolean;
  defaultRedisFailureMode: string;
  policyCount: number;
  policies: PolicySummary[];
}

export interface HealthResponse {
  status: 'UP' | 'DOWN' | string;
}

/**
 * Actuator metric payload. Verified shape:
 * { name, description, measurements: [{ statistic: 'COUNT', value }], availableTags: [{ tag, values }] }
 * An unknown tag value returns HTTP 404 with an empty body, which is "no data", not an error.
 */
export interface MetricResponse {
  name: string;
  description?: string;
  measurements: { statistic: string; value: number }[];
  availableTags?: { tag: string; values: string[] }[];
}

export interface RejectionHeaders {
  retryAfter: string | null;
  limit: string | null;
  remaining: string | null;
  policy: string | null;
}

export interface RejectionInfo {
  index: number;
  status: number;
  headers: RejectionHeaders;
  message: string | null;
}

export type DemoOutcome = 'success' | 'rejected' | 'error';

export interface DemoSummary {
  routeId: string | null;
  totalSent: number;
  success: number;
  rejected: number;
  error: number;
  statuses: Record<number, number>;
  first429Index: number | null;
  elapsedMs: number;
  completed: boolean;
  cancelled: boolean;
  inconclusive: boolean;
  lastRejection: RejectionInfo | null;
  lastError: { index: number; status: number; reason: string } | null;
}
