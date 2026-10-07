import { describe, expect, it } from 'vitest';

import { PolicyTargetRecord } from './admin-models';
import {
  clampRequestCount,
  configuredRoute,
  DEFAULT_REQUEST_COUNT,
  MAX_REQUEST_COUNT,
  formatWindow,
  requestUrl,
  targetLabel,
} from './demo-catalog';

function target(overrides: Partial<PolicyTargetRecord> = {}): PolicyTargetRecord {
  return {
    id: 'products-read',
    policyId: 'products-read',
    enabled: true,
    algorithm: 'FIXED_WINDOW',
    scope: 'IP',
    parameterSummary: '100 per 1 minute',
    configuredMethod: 'GET',
    configuredPath: '/api/products',
    method: 'GET',
    concretePath: '/api/products',
    sampleQuery: '',
    matchedHandler: true,
    testable: true,
    requiresCredentials: false,
    note: '',
    reason: '',
    enforcedWith: [],
    exemptions: [],
    ...overrides,
  };
}

describe('policy test target helpers', () => {
  it('clamps the request count', () => {
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

  it('builds the request URL from the policy target concrete path and sample query', () => {
    expect(requestUrl(target())).toBe('/api/products');
    expect(requestUrl(target({ sampleQuery: 'user=demo' }))).toBe('/api/products?user=demo');
  });

  it('shows each policy id, configured route, algorithm and own parameters in its label', () => {
    expect(targetLabel(target())).toBe(
      'products-read — GET /api/products · FIXED_WINDOW · 100 per 1 minute',
    );
    expect(
      targetLabel(
        target({
          policyId: 'orders-user',
          configuredMethod: 'POST',
          configuredPath: '/api/orders',
          algorithm: 'TOKEN_BUCKET',
          scope: 'USER',
          parameterSummary: 'capacity 50, refill 1 per 5 s',
        }),
      ),
    ).toBe('orders-user — POST /api/orders · TOKEN_BUCKET · capacity 50, refill 1 per 5 s');
  });

  it('labels disabled and non-testable policies without hiding them', () => {
    expect(targetLabel(target({ enabled: false, testable: false }))).toContain('disabled, not enforced');
    expect(targetLabel(target({ testable: false }))).toContain('cannot test automatically');
    expect(configuredRoute(target({ configuredPath: null, scope: 'APPLICATION' }))).toBe(
      'APPLICATION (no path)',
    );
  });
});
