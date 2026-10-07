import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { AdminApiService } from '../../core/admin-api.service';
import { PoliciesPageComponent } from './policies-page.component';

const BASE = '/api/admin/rate-limit';

const CAPABILITIES = {
  algorithms: [
    { name: 'FIXED_WINDOW', implemented: true, note: 'Counts requests per fixed window.' },
    { name: 'TOKEN_BUCKET', implemented: false, note: 'Not enforced by this build.' },
  ],
  scopes: [
    { name: 'IP', implemented: true, note: 'per client IP' },
    { name: 'USER', implemented: true, note: 'per authenticated user' },
  ],
  composition: 'AND',
  topology: 'single-redis',
};

const POLICIES = [
  {
    id: 'products-read',
    name: 'products-read',
    method: 'GET',
    path: '/api/products',
    algorithm: 'FIXED_WINDOW',
    algorithmImplemented: true,
    scope: 'IP',
    window: 'PT1M',
    limit: 100,
    capacity: null,
    refillInterval: null,
    cost: null,
    drainRate: null,
    queueCapacity: null,
    maxConcurrent: null,
    leaseDuration: null,
    enabled: true,
    onRedisError: 'FAIL_OPEN',
    version: 3,
    createdAt: '2026-10-05T09:00:00Z',
    updatedAt: '2026-10-05T09:30:00Z',
    updatedBy: 'pocadmin',
    parameterSummary: '100 per 1 minute',
  },
  {
    id: 'order-create',
    name: 'order-create',
    method: 'POST',
    path: '/api/orders',
    algorithm: 'FIXED_WINDOW',
    algorithmImplemented: true,
    scope: 'USER',
    window: 'PT1M',
    limit: 50,
    capacity: null,
    refillInterval: null,
    cost: null,
    drainRate: null,
    queueCapacity: null,
    maxConcurrent: null,
    leaseDuration: null,
    enabled: false,
    onRedisError: null,
    version: 7,
    createdAt: '2026-10-05T09:00:00Z',
    updatedAt: '2026-10-05T09:45:00Z',
    updatedBy: 'pocadmin',
    parameterSummary: '50 per 1 minute',
  },
];

describe('PoliciesPageComponent', () => {
  let fixture: ComponentFixture<PoliciesPageComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PoliciesPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    // Sign in for real: an unauthenticated console must not receive policy data at all.
    const login = firstValueFrom(TestBed.inject(AdminApiService).login('pocadmin', 'test-only'));
    http.expectOne(`${BASE}/policies`).flush([]);
    await login;
    fixture = TestBed.createComponent(PoliciesPageComponent);
    fixture.detectChanges();
  });

  /** The page loads capabilities and policies on entry. */
  async function loadWorkspace(policies: unknown[] = POLICIES): Promise<void> {
    http.expectOne(`${BASE}/capabilities`).flush(CAPABILITIES);
    http.expectOne(`${BASE}/policies`).flush(policies);
    await settle();
  }

  const settle = async () => {
    await fixture.whenStable();
    fixture.detectChanges();
  };

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';
  const query = <T extends Element>(sel: string) =>
    (fixture.nativeElement as HTMLElement).querySelector<T>(sel);
  const click = async (sel: string) => {
    query<HTMLButtonElement>(sel)!.click();
    fixture.detectChanges();
    await settle();
  };

  it('shows a loading state before the first response arrives', () => {
    expect(text()).toContain('Loading policies');
  });

  it('lists policies with algorithm, scope and humanized parameters', async () => {
    await loadWorkspace();
    expect(text()).toContain('products-read');
    expect(text()).toContain('100 per 1 minute');
    expect(text()).toContain('FIXED_WINDOW');
    expect(text()).toContain('v3');
    expect(text()).not.toContain('PT1M');
  });

  it('explains an empty store instead of rendering a bare table', async () => {
    await loadWorkspace([]);
    expect(text()).toContain('No policies are stored');
  });

  it('offers a retry when the workspace fails to load', async () => {
    http.expectOne(`${BASE}/capabilities`).flush('', { status: 500, statusText: 'Server Error' });
    http.expectOne(`${BASE}/policies`).flush('', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(text()).toContain('HTTP 500');
    await click('.alert-bad + button');
    // The retry re-requests both, proving the failure state is recoverable.
    http.expectOne(`${BASE}/capabilities`);
    http.expectOne(`${BASE}/policies`);
  });

  it('filters the list without another request', async () => {
    await loadWorkspace();
    const filter = query<HTMLInputElement>('input[name="policy-filter"]')!;
    filter.value = 'orders';
    filter.dispatchEvent(new Event('input'));
    await settle();

    expect(text()).toContain('order-create');
    expect(text()).not.toContain('products-read');
    expect(text()).toContain('1 of 2 policies');
  });

  it('keeps the editor closed until Create policy is pressed', async () => {
    await loadWorkspace();
    expect(query('[role="dialog"]')).toBeNull();
    expect(text()).not.toContain('Algorithm and scope');
  });

  it('opens a capability-driven editor that refuses an unimplemented algorithm', async () => {
    await loadWorkspace();
    await click('.page-head button');
    expect(query('[role="dialog"]')).not.toBeNull();
    // Only enforced algorithms are selectable; the unimplemented one is visibly disabled.
    const options = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('select[name="f-algorithm"] option'),
    );
    expect(options.some((o) => (o as HTMLOptionElement).disabled)).toBe(true);

    const id = query<HTMLInputElement>('input[name="f-id"]')!;
    id.value = 'new-policy';
    id.dispatchEvent(new Event('input'));
    const path = query<HTMLInputElement>('input[name="f-path"]')!;
    path.value = '/api/new';
    path.dispatchEvent(new Event('input'));
    const algo = query<HTMLSelectElement>('select[name="f-algorithm"]')!;
    algo.value = 'TOKEN_BUCKET';
    algo.dispatchEvent(new Event('change'));
    await settle();

    await click('button[type="submit"]');
    expect(text()).toContain('not enforced by this build');
    http.expectNone((req) => req.url === `${BASE}/policies` && req.method === 'POST');
  });

  it('requires a two-step confirmation before deleting', async () => {
    await loadWorkspace();
    await click('.btn-danger-ghost');
    expect(text()).toContain('Confirm delete');
    // Nothing is sent by merely arming the row.
    http.expectNone((req) => req.url === `${BASE}/policies/products-read` && req.method === 'DELETE');

    query<HTMLButtonElement>('.btn-danger')!.click();
    fixture.detectChanges();
    http.expectOne((req) => req.url === `${BASE}/policies/products-read` && req.method === 'DELETE').flush({});
    await settle();
    // The list is re-read from the server, which is what proves the delete landed.
    http.expectOne(`${BASE}/policies`).flush(POLICIES);
  });
});
