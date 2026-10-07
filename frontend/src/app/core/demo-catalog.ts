import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';

import { AdminApiService } from './admin-api.service';

export interface DemoRoute {
  id: string;
  label: string;
  method: 'GET' | 'POST';
  path: string;
  needsAuth: boolean;
  note: string;
}

/** Fallback when the backend catalog is unavailable. Marked as stale in the UI. */
const FALLBACK_ROUTES: readonly DemoRoute[] = [
  { id: 'products', label: 'GET /api/products', method: 'GET', path: '/api/products', needsAuth: false, note: 'Public read route.' },
  { id: 'login', label: 'POST /api/login', method: 'POST', path: '/api/login', needsAuth: false, note: 'Credential route.' },
  { id: 'orders', label: 'POST /api/orders', method: 'POST', path: '/api/orders', needsAuth: true, note: 'Authenticated route.' },
];

export const DEFAULT_REQUEST_COUNT = 20;
export const MAX_REQUEST_COUNT = 150;

export function clampRequestCount(value: number | string): number {
  const parsed = typeof value === 'number' ? value : Number.parseInt(value, 10);
  if (!Number.isFinite(parsed) || parsed < 1) return 1;
  return Math.min(Math.floor(parsed), MAX_REQUEST_COUNT);
}

export function formatWindow(seconds: number): string {
  if (seconds < 60) return `${seconds} s`;
  const minutes = seconds / 60;
  return minutes === 1 ? '1 minute' : `${minutes} minutes`;
}

@Injectable({ providedIn: 'root' })
export class DemoCatalogService {
  private readonly http = inject(HttpClient);
  private readonly admin = inject(AdminApiService);

  readonly defaultCount = DEFAULT_REQUEST_COUNT;
  readonly maxCount = MAX_REQUEST_COUNT;

  /** Fetches the authoritative demo-capable endpoint catalog from the backend. */
  fetchCatalog(): Observable<{ entries: DemoRoute[] }> {
    let headers = new HttpHeaders({ Accept: 'application/json' });
    const auth = this.admin.authorizationHeader;
    if (auth) {
      headers = headers.set('Authorization', auth);
    }
    return this.http.get<{ entries: DemoRoute[] }>('/api/poc/demo-catalog', { headers });
  }

  /** Fallback routes for local dev when the backend is unreachable. */
  get fallbackRoutes(): readonly DemoRoute[] {
    return FALLBACK_ROUTES;
  }
}
