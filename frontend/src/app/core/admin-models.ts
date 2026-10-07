/** Admin policy as returned by GET /api/admin/rate-limit/policies. Durations arrive as ISO-8601 strings. */
export interface AdminPolicy {
  id: string;
  name: string;
  method: string;
  path: string | null;
  algorithm: string;
  algorithmImplemented: boolean;
  scope: string;
  window: string | null;
  limit: number | null;
  capacity: number | null;
  refillInterval: string | null;
  cost: number | null;
  drainRate: number | null;
  queueCapacity: number | null;
  maxConcurrent: number | null;
  leaseDuration: string | null;
  enabled: boolean;
  onRedisError: 'FAIL_OPEN' | 'FAIL_CLOSED' | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  updatedBy: string | null;
  parameterSummary: string;
}

/** Body for create and update. Version carries the value the editor read. */
export interface PolicyEdit {
  id?: string;
  name?: string;
  method?: string;
  path?: string | null;
  algorithm?: string;
  scope?: string;
  window?: string | null;
  limit?: number | null;
  capacity?: number | null;
  refillInterval?: string | null;
  cost?: number | null;
  drainRate?: number | null;
  queueCapacity?: number | null;
  maxConcurrent?: number | null;
  leaseDuration?: string | null;
  enabled?: boolean | null;
  onRedisError?: 'FAIL_OPEN' | 'FAIL_CLOSED' | null;
  version?: number | null;
}

export interface CapabilityOption {
  name: string;
  implemented: boolean;
  note: string;
}

export interface Capabilities {
  algorithms: CapabilityOption[];
  scopes: CapabilityOption[];
  composition: string;
  topology: string;
}

export interface AuditRecord {
  at: string;
  actor: string;
  policyId: string;
  operation: string;
  resultingVersion: number;
  changedFields: string[];
}

export interface AdminApiError {
  status: number;
  code: string;
  message: string;
  problems: string[];
}

/** ISO-8601 duration (PT60S, PT1M, PT1H) to seconds. Null when absent or unparseable. */
export function durationToSeconds(value: string | null | undefined): number | null {
  if (!value) return null;
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(value);
  if (!match) return null;
  const hours = Number(match[1] ?? 0);
  const minutes = Number(match[2] ?? 0);
  const seconds = Number(match[3] ?? 0);
  return hours * 3600 + minutes * 60 + seconds;
}

/** Seconds to an ISO-8601 duration the backend accepts. */
export function secondsToDuration(seconds: number): string {
  return `PT${Math.max(1, Math.floor(seconds))}S`;
}
