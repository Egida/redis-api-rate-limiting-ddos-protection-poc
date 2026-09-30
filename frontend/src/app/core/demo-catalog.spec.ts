import { describe, expect, it } from 'vitest';

import { clampRequestCount, DEFAULT_REQUEST_COUNT, DEMO_ROUTES, MAX_REQUEST_COUNT, formatWindow } from './demo-catalog';

describe('demo catalog', () => {
  it('exposes only verified demo routes', () => {
    expect(DEMO_ROUTES.map((r) => r.id)).toEqual(['products', 'login', 'orders']);
    expect(DEMO_ROUTES.map((r) => r.method + ' ' + r.path)).toEqual([
      'GET /api/products',
      'POST /api/login',
      'POST /api/orders',
    ]);
  });

  it('matches the limits configured in application.yml', () => {
    expect(DEMO_ROUTES.map((r) => r.expectedLimit)).toEqual([100, 10, 30]);
    expect(DEMO_ROUTES.find((r) => r.id === 'orders')?.needsAuth).toBe(true);
    expect(DEMO_ROUTES.find((r) => r.id === 'login')?.needsAuth).toBe(false);
  });

  it('documents that /api/login has no body contract', () => {
    const login = DEMO_ROUTES.find((r) => r.id === 'login');
    expect(login?.note).toContain('no request body');
  });

  it('caps the request count', () => {
    expect(clampRequestCount(20)).toBe(20);
    expect(clampRequestCount('20')).toBe(20);
    expect(clampRequestCount(0)).toBe(1);
    expect(clampRequestCount(-10)).toBe(1);
    expect(clampRequestCount('abc')).toBe(1);
    expect(clampRequestCount(MAX_REQUEST_COUNT + 500)).toBe(MAX_REQUEST_COUNT);
    expect(MAX_REQUEST_COUNT).toBe(150);
    expect(DEFAULT_REQUEST_COUNT).toBe(20);
  });

  it('formats windows in plain language', () => {
    expect(formatWindow(60)).toBe('1 minute');
    expect(formatWindow(120)).toBe('2 minutes');
    expect(formatWindow(30)).toBe('30 s');
  });
});
