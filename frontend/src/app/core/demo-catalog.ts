import { Injectable } from '@angular/core';

export interface DemoRoute {
  id: string;
  label: string;
  method: 'GET' | 'POST';
  path: string;
  expectedLimit: number;
  identity: 'IP' | 'USER';
  needsAuth: boolean;
  /** Verified behaviour of the controller, shown in the UI so nothing is guessed at runtime. */
  note: string;
}

export const DEMO_ROUTES: readonly DemoRoute[] = [
  {
    id: 'products',
    label: 'GET /api/products',
    method: 'GET',
    path: '/api/products',
    expectedLimit: 100,
    identity: 'IP',
    needsAuth: false,
    note: 'Public read route, limited per client IP.',
  },
  {
    id: 'login',
    label: 'POST /api/login',
    method: 'POST',
    path: '/api/login',
    expectedLimit: 10,
    identity: 'IP',
    needsAuth: false,
    // Verified: DemoController.login takes an optional @RequestParam, so there is no JSON body
    // contract. Sending a body would be ignored, so the demo sends none.
    note: 'Credential route, fails closed while Redis is down. The controller takes an optional ' +
      '?user= query parameter and no request body, so this demo sends no payload.',
  },
  {
    id: 'orders',
    label: 'POST /api/orders',
    method: 'POST',
    path: '/api/orders',
    expectedLimit: 30,
    identity: 'USER',
    needsAuth: true,
    note: 'Authenticated route, limited per authenticated user. Enter HTTP Basic credentials below; ' +
      'they are held in memory for this run only and are never stored or logged.',
  },
];

export const DEFAULT_REQUEST_COUNT = 20;
export const MAX_REQUEST_COUNT = 150;

/** Hard bound on a single run, so a mistyped value can never become an unbounded burst. */
export function clampRequestCount(value: number | string): number {
  const parsed = typeof value === 'number' ? value : Number.parseInt(value, 10);
  if (!Number.isFinite(parsed) || parsed < 1) return 1;
  return Math.min(Math.floor(parsed), MAX_REQUEST_COUNT);
}

/** Human-readable window, e.g. 60 -> "1 minute". */
export function formatWindow(seconds: number): string {
  if (seconds < 60) return `${seconds} s`;
  const minutes = seconds / 60;
  return minutes === 1 ? '1 minute' : `${minutes} minutes`;
}

@Injectable({ providedIn: 'root' })
export class DemoCatalogService {
  readonly routes = DEMO_ROUTES;
  readonly defaultCount = DEFAULT_REQUEST_COUNT;
  readonly maxCount = MAX_REQUEST_COUNT;

  byId(id: string): DemoRoute | undefined {
    return this.routes.find((r) => r.id === id);
  }
}
