import { InjectionToken } from '@angular/core';

/**
 * Backend origin for the dev-server proxy. Kept in one typed place so no component ever hard-codes
 * a host. In production the SPA is expected to be served from the same origin as the API, in which
 * case these relative paths resolve without a proxy at all.
 */
export interface ApiConfig {
  readonly baseUrl: string;
  readonly pollIntervalMs: number;
}

export const API_CONFIG = new InjectionToken<ApiConfig>('api.config', {
  providedIn: 'root',
  factory: (): ApiConfig => ({
    baseUrl: '',
    // Restrained polling: enough to show counters moving, not enough to hammer the POC.
    pollIntervalMs: 10_000,
  }),
});
