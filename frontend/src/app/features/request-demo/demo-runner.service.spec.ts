import { TestRequest, HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { DemoRoute } from '../../core/demo-catalog';
import { DemoRunnerService } from './demo-runner.service';

const products: DemoRoute = { id: 'products', label: 'GET /api/products', method: 'GET', path: '/api/products', needsAuth: false, note: 'Public read route.' };
const orders: DemoRoute = { id: 'orders', label: 'POST /api/orders', method: 'POST', path: '/api/orders', needsAuth: true, note: 'Authenticated route.' };
const login: DemoRoute = { id: 'login', label: 'POST /api/login', method: 'POST', path: '/api/login', needsAuth: false, note: 'Credential route.' };

type Respond = (request: TestRequest, index: number) => void;

/**
 * Drive the runner one request at a time. It issues a request per microtask, so a fixed number of
 * sleeps either races the runner or leaves a request open. This answers whatever is pending and
 * stops as soon as the run settles.
 */
async function drive(
  http: HttpTestingController,
  run: Promise<unknown>,
  respond: Respond,
  maxRequests = 50,
): Promise<void> {
  let sent = 0;
  for (let step = 0; step < maxRequests; step += 1) {
    await Promise.resolve();
    await new Promise((resolve) => setTimeout(resolve, 0));
    const pending = http.match(() => true);
    if (pending.length === 0) {
      if (sent > 0) break;
      continue;
    }
    for (const request of pending) {
      sent += 1;
      respond(request, sent);
    }
  }
  await run;
}

/** A minimal 200 body; typed as object so flush() accepts it. */
function ok(): { ok: boolean } {
  return { ok: true };
}

describe('DemoRunnerService', () => {
  let runner: DemoRunnerService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClientTesting()] });
    runner = TestBed.inject(DemoRunnerService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // A cancel test may leave one request open; answer it so it is not reported as a leak.
    http.match(() => true).forEach((request) => request.flush(ok()));
    http.verify();
  });

  it('records the first 429 index and never retries it', async () => {
    const promise = runner.run(products, 6, null, () => undefined);
    await drive(
      http,
      promise,
      (request, index) => {
        if (index <= 3) {
          request.flush(ok());
        } else {
          request.flush(
            { message: 'Rate limit exceeded for this route. Retry after 42s.' },
            { status: 429, statusText: 'Too Many Requests' },
          );
        }
      },
      6,
    );

    const summary = await promise;
    expect(summary.totalSent).toBe(6);
    expect(summary.success).toBe(3);
    expect(summary.rejected).toBe(3);
    expect(summary.first429Index).toBe(4);
    expect(summary.completed).toBe(true);
    expect(summary.inconclusive).toBe(false);
    expect(summary.lastRejection?.message).toContain('Rate limit exceeded');
  });

  it('parses the 429 rate-limit headers', async () => {
    const promise = runner.run(products, 1, null, () => undefined);
    await drive(
      http,
      promise,
      (request) =>
        request.flush(
          { message: 'Rate limit exceeded', policy: 'products-read' },
          {
            status: 429,
            statusText: 'Too Many Requests',
            headers: {
              'Retry-After': '42',
              'X-RateLimit-Limit': '100',
              'X-RateLimit-Remaining': '0',
              'X-RateLimit-Policy': 'products-read',
            },
          },
        ),
      1,
    );

    const summary = await promise;
    expect(summary.lastRejection?.headers).toEqual({
      retryAfter: '42',
      limit: '100',
      remaining: '0',
      policy: 'products-read',
    });
  });

  it('stops on a 401 instead of spending the whole budget', async () => {
    const promise = runner.run(orders, 20, { username: 'alice', password: 'alice-pw' }, () => undefined);
    let sawAuthorization = false;
    await drive(
      http,
      promise,
      (request) => {
        sawAuthorization = /^Basic /.test(request.request.headers.get('Authorization') ?? '');
        request.flush({ error: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });
      },
      1,
    );

    const summary = await promise;
    expect(sawAuthorization).toBe(true);
    expect(summary.totalSent).toBe(1);
    expect(summary.error).toBe(1);
    expect(summary.lastError?.status).toBe(401);
    expect(summary.completed).toBe(false);
    expect(summary.inconclusive).toBe(true);
  });

  it('surfaces a 503 so a Redis outage is visible and inconclusive', async () => {
    const promise = runner.run(products, 10, null, () => undefined);
    await drive(
      http,
      promise,
      (request) =>
        request.flush(
          { message: 'Rate limiting is temporarily unavailable. Please retry later.' },
          { status: 503, statusText: 'Service Unavailable' },
        ),
      1,
    );

    const summary = await promise;
    expect(summary.error).toBe(1);
    expect(summary.lastError?.status).toBe(503);
    expect(summary.inconclusive).toBe(true);
  });

  it('sends no body and no auth header for POST /api/login', async () => {
    const promise = runner.run(login, 1, null, () => undefined);
    let body: unknown = 'unset';
    let hadAuthorization = true;
    await drive(
      http,
      promise,
      (request) => {
        body = request.request.body;
        hadAuthorization = request.request.headers.has('Authorization');
        request.flush({ user: 'alice', token: 'poc-token-alice' });
      },
      1,
    );

    expect(body).toBeNull();
    expect(hadAuthorization).toBe(false);
    const summary = await promise;
    expect(summary.success).toBe(1);
  });

  it('refuses to start a second run while one is active', async () => {
    const first = runner.run(products, 3, null, () => undefined);
    await Promise.resolve();
    const second = await runner.run(products, 3, null, () => undefined);
    expect(second.totalSent).toBe(0);

    await drive(http, first, (request) => request.flush(ok()), 3);
    const summary = await first;
    expect(summary.totalSent).toBe(3);
  });

  it('can be cancelled mid-run and reports the run as inconclusive', async () => {
    const promise = runner.run(products, 5, null, () => undefined);
    // Answer exactly one request, then cancel before the second is issued.
    await drive(
      http,
      promise,
      (request) => {
        request.flush(ok());
        runner.cancel();
      },
      1,
    );

    const summary = await promise;
    expect(summary.totalSent).toBe(1);
    expect(summary.cancelled).toBe(true);
    expect(summary.completed).toBe(false);
    expect(summary.inconclusive).toBe(true);
  });

  it('reports progress for each request', async () => {
    const seen: number[] = [];
    const promise = runner.run(products, 3, null, (sent) => seen.push(sent));
    await drive(http, promise, (request) => request.flush(ok()), 3);
    await promise;
    expect(seen).toEqual([1, 2, 3]);
  });
});
