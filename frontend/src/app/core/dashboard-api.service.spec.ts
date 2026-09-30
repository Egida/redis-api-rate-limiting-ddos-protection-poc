import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ApiClientService } from './api-client.service';
import { DashboardApiService } from './dashboard-api.service';
import { MetricResponse } from './models';

const METRIC_URL = '/actuator/metrics/ratelimit.requests';

function metric(availableOutcomes: string[], policies: string[] = ['products-read']): MetricResponse {
  return {
    name: 'ratelimit.requests',
    description: 'Rate limit decisions',
    measurements: [{ statistic: 'COUNT', value: 10 }],
    availableTags: [
      { tag: 'identity', values: ['ip'] },
      { tag: 'outcome', values: availableOutcomes },
      { tag: 'policy', values: policies },
    ],
  };
}

describe('ApiClientService', () => {
  let api: ApiClientService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClientTesting()] });
    api = TestBed.inject(ApiClientService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('maps UP to healthy', async () => {
    const promise = firstValue(api.health());
    http.expectOne('/actuator/health').flush({ status: 'UP' });
    const result = await promise;
    expect(result.available).toBe(true);
    expect(result.value.state).toBe('healthy');
  });

  it('maps DOWN to degraded rather than claiming healthy', async () => {
    const promise = firstValue(api.health());
    http.expectOne('/actuator/health').flush({ status: 'DOWN' });
    const result = await promise;
    expect(result.value.state).toBe('degraded');
  });

  it('treats a 404 counter as no-data rather than an error', async () => {
    const promise = firstValue(api.counter());
    http.expectOne(METRIC_URL).flush('', { status: 404, statusText: 'Not Found' });
    const result = await promise;
    expect(result.available).toBe(false);
    expect(result.reason).toBe('no-data');
  });

  it('reports an unreachable backend', async () => {
    const promise = firstValue(api.counter());
    http.expectOne(METRIC_URL).error(new ProgressEvent('network error'));
    const result = await promise;
    expect(result.available).toBe(false);
    expect(result.reason).toBe('unreachable');
  });
});

describe('DashboardApiService', () => {
  let api: DashboardApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClientTesting()] });
    api = TestBed.inject(DashboardApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests only outcome tags that actually exist', async () => {
    const promise = firstValue(api.load());
    http.expectOne('/actuator/health').flush({ status: 'UP' });
    http.expectOne(METRIC_URL).flush(metric(['allowed', 'rejected']));
    http.expectOne('/api/poc/policies').flush({
      source: 'rate-limit.policies (application.yml)',
      editable: false,
      limiterEnabled: true,
      defaultRedisFailureMode: 'FAIL_OPEN',
      policyCount: 1,
      policies: [],
    });
    http.expectOne(`${METRIC_URL}?tag=outcome%3Aallowed`).flush(metric(['allowed']));
    http.expectOne(`${METRIC_URL}?tag=outcome%3Arejected`).flush(metric(['rejected']));
    // No outcome=error request, because the meter has never recorded one.
    http.expectNone(`${METRIC_URL}?tag=outcome%3Aerror`);

    const snapshot = await promise;
    expect(snapshot.countersAvailable).toBe(true);
    expect(snapshot.allowed).toBe(10);
    expect(snapshot.rejected).toBe(10);
    expect(snapshot.redisError).toBe(0);
    expect(snapshot.neverRecorded).toContain('no Redis errors');
  });

  it('reports unavailable metrics without pretending they are zero', async () => {
    const promise = firstValue(api.load());
    http.expectOne('/actuator/health').flush({ status: 'UP' });
    http.expectOne(METRIC_URL).flush('', { status: 404, statusText: 'Not Found' });
    http.expectOne('/api/poc/policies').flush(
      { source: 'x', editable: false, limiterEnabled: true, defaultRedisFailureMode: 'FAIL_OPEN', policyCount: 0, policies: [] },
    );

    const snapshot = await promise;
    expect(snapshot.countersAvailable).toBe(false);
    expect(snapshot.countersReason).toBe('no-data');
    expect(snapshot.total).toBe(0);
  });

  it('falls back to sample policies when the policy endpoint fails', async () => {
    const promise = firstValue(api.load());
    http.expectOne('/actuator/health').flush({ status: 'UP' });
    http.expectOne(METRIC_URL).flush(metric(['allowed']));
    http.expectOne('/api/poc/policies').flush('', { status: 500, statusText: 'Server Error' });
    http.expectOne(`${METRIC_URL}?tag=outcome%3Aallowed`).flush(metric(['allowed']));

    const snapshot = await promise;
    expect(snapshot.policies.available).toBe(false);
    expect(snapshot.policies.reason).toBe('http-error');
  });
});

function firstValue<T>(source: { subscribe: (fn: (v: T) => void, err: (e: unknown) => void) => unknown }): Promise<T> {
  return new Promise<T>((resolve, reject) => source.subscribe(resolve, reject));
}
