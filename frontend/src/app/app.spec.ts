import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { App } from './app';

const METRIC_URL = '/actuator/metrics/ratelimit.requests';
const DEMO_ROUTES_ON_DISK = ['/api/products', '/api/login', '/api/orders'];

const POLICIES = {
  source: 'rate-limit.policies (application.yml)',
  editable: false,
  limiterEnabled: true,
  defaultRedisFailureMode: 'FAIL_OPEN',
  policyCount: 3,
  policies: [
    { id: 'products-read', method: 'GET', path: '/api/products', limit: 100, windowSeconds: 60, identity: 'IP', redisFailureMode: 'FAIL_OPEN', redisFailureModeLabel: 'Fail open' },
    { id: 'login-attempt', method: 'POST', path: '/api/login', limit: 10, windowSeconds: 60, identity: 'IP', redisFailureMode: 'FAIL_CLOSED', redisFailureModeLabel: 'Fail closed' },
    { id: 'order-create', method: 'POST', path: '/api/orders', limit: 30, windowSeconds: 60, identity: 'USER', redisFailureMode: 'FAIL_OPEN', redisFailureModeLabel: 'Fail open' },
  ],
};

/** Answer every pending request once, so the component settles without a real backend. */
function answerAll(http: HttpTestingController, status = 'UP'): void {
  for (const request of http.match(() => true)) {
    const url = request.request.url;
    if (url === '/actuator/health') {
      request.flush({ status });
    } else if (url === METRIC_URL) {
      // The unfiltered meter has not been created yet: no data, which is a real state, not an error.
      request.flush('', { status: 404, statusText: 'Not Found' });
    } else if (url === '/api/poc/policies') {
      request.flush(POLICIES);
    } else {
      request.flush('', { status: 500, statusText: 'Unexpected call' });
    }
  }
}

describe('App', () => {
  let fixture: ComponentFixture<App>;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    answerAll(http);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  afterEach(() => {
    fixture.destroy();
    // Drain anything the polling interval produced so it cannot leak into the next test.
    http.match(() => true);
  });

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('renders the RateGuard shell', () => {
    expect(text()).toContain('RateGuard');
    expect(text()).toContain('API Protection Console');
    expect(text()).toContain('LOCAL POC');
  });

  it('reports a healthy service and reads policies from the API', () => {
    expect(text()).toContain('Healthy');
    expect(text()).toContain('/api/products');
    expect(text()).toContain('Fail closed');
    // The counter endpoint 404s, so the card must say so rather than show a fabricated number.
    expect(text()).toContain('No counter data yet');
  });

  it('never renders credentials, Redis keys or authorization headers', () => {
    expect(text()).not.toContain('alice-pw');
    expect(text()).not.toContain('rate-limit:v1:');
    expect(text()).not.toContain('Authorization');
  });

  it('never sends demo traffic without the operator pressing Start', () => {
    for (const route of DEMO_ROUTES_ON_DISK) {
      expect(http.match((r) => r.url === route).length).toBe(0);
    }
  });

  it('marks the counter scope as a cumulative total, not a per-minute rate', () => {
    expect(text()).toContain('cumulative');
    expect(text()).toContain('not');
  });
});
