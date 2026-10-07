import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { AdminApiService } from '../../core/admin-api.service';
import { AdminPolicy, DemoRouteCatalogResponse, PolicyTargetRecord } from '../../core/admin-models';
import { RequestDemoComponent } from './request-demo.component';

const BASE = '/api/admin/rate-limit';

function policy(overrides: Partial<AdminPolicy> = {}): AdminPolicy {
  return {
    id: 'products-read',
    name: 'products-read',
    method: 'GET',
    path: '/api/products',
    algorithm: 'FIXED_WINDOW',
    algorithmImplemented: true,
    scope: 'IP',
    window: 'PT1M',
    limit: 125,
    capacity: null,
    refillInterval: null,
    cost: null,
    drainRate: null,
    queueCapacity: null,
    maxConcurrent: null,
    leaseDuration: null,
    enabled: true,
    onRedisError: null,
    version: 12,
    createdAt: '2026-10-05T09:00:00Z',
    updatedAt: '2026-10-05T09:30:00Z',
    updatedBy: 'pocadmin',
    parameterSummary: '125 per 1 minute',
    ...overrides,
  };
}

function target(overrides: Partial<PolicyTargetRecord> = {}): PolicyTargetRecord {
  const configuredPolicy = policy({
    id: overrides.policyId ?? 'products-read',
    method: overrides.configuredMethod ?? 'GET',
    path: overrides.configuredPath === undefined ? '/api/products' : overrides.configuredPath,
    algorithm: overrides.algorithm ?? 'FIXED_WINDOW',
    scope: overrides.scope ?? 'IP',
    enabled: overrides.enabled ?? true,
    parameterSummary: overrides.parameterSummary ?? '125 per 1 minute',
  });
  return {
    id: configuredPolicy.id,
    policyId: configuredPolicy.id,
    enabled: configuredPolicy.enabled,
    algorithm: configuredPolicy.algorithm,
    scope: configuredPolicy.scope,
    parameterSummary: configuredPolicy.parameterSummary,
    configuredMethod: configuredPolicy.method,
    configuredPath: configuredPolicy.path,
    method: 'GET',
    concretePath: '/api/products',
    sampleQuery: '',
    matchedHandler: true,
    testable: true,
    requiresCredentials: false,
    note: 'Safe read endpoint.',
    reason: '',
    enforcedWith: [configuredPolicy],
    exemptions: [],
    ...overrides,
  };
}

describe('RequestDemoComponent', () => {
  let fixture: ComponentFixture<RequestDemoComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RequestDemoComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    const login = firstValueFrom(TestBed.inject(AdminApiService).login('pocadmin', 'test-only'));
    http.expectOne(`${BASE}/policies`).flush([]);
    await login;
    fixture = TestBed.createComponent(RequestDemoComponent);
    fixture.detectChanges();
  });

  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  };

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';
  const options = () =>
    Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLSelectElement>(
        'select[name="target"] option',
      ),
    ).map((option) => option.textContent?.trim());

  const load = async (targets: PolicyTargetRecord[]) => {
    const response: DemoRouteCatalogResponse = {
      targets,
      policies: targets.map((entry) => policy({ id: entry.policyId })),
    };
    http.expectOne(`${BASE}/demo-routes`).flush(response);
    await settle();
  };

  it('shows one dropdown option for every configured policy, including an orphan and non-testable policy', async () => {
    await load([
      target({ policyId: 'normal', parameterSummary: '50 per 1 minute' }),
      target({
        policyId: 'login-attempt',
        configuredMethod: 'POST',
        configuredPath: '/api/login',
        method: 'POST',
        concretePath: '/api/login',
        sampleQuery: 'user=demo',
        matchedHandler: true,
        testable: false,
        reason: 'This route must not be replayed automatically.',
      }),
      target({
        policyId: 'disabled-policy',
        enabled: false,
        testable: false,
        method: null,
        concretePath: null,
        reason: 'This policy is disabled, so it is not enforced.',
        enforcedWith: [],
      }),
      target({
        policyId: 'orphan-policy',
        configuredPath: '/api/my-new-read-route',
        concretePath: '/api/my-new-read-route',
        matchedHandler: false,
        note: 'No handler serves this path; the limiter runs before routing.',
      }),
    ]);

    expect(options()).toHaveLength(4);
    expect(options().join('\n')).toContain('normal');
    expect(options().join('\n')).toContain('login-attempt');
    expect(options().join('\n')).toContain('disabled-policy');
    expect(options().join('\n')).toContain('orphan-policy');
    expect(options().join('\n')).toContain('cannot test automatically');
    expect(options().join('\n')).toContain('disabled, not enforced');
  });

  it('lets the operator inspect a non-testable policy but prevents sending it', async () => {
    await load([
      target({
        policyId: 'unsafe-policy',
        testable: false,
        reason: 'This operation is not safe to replay automatically.',
      }),
    ]);

    const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
      'select[name="target"]',
    )!;
    select.value = 'unsafe-policy';
    select.dispatchEvent(new Event('change'));
    await settle();

    expect(text()).toContain('This operation is not safe to replay automatically.');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')
        ?.disabled,
    ).toBe(true);
  });

  it('changes selected policy details when the dropdown selection changes', async () => {
    await load([
      target({ policyId: 'products-read', configuredPath: '/api/products' }),
      target({
        policyId: 'new-read-policy',
        configuredPath: '/api/new-read',
        concretePath: '/api/new-read',
        parameterSummary: '7 per 1 minute',
        enforcedWith: [policy({ id: 'new-read-policy', path: '/api/new-read', limit: 7 })],
      }),
    ]);

    const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
      'select[name="target"]',
    )!;
    select.value = 'new-read-policy';
    select.dispatchEvent(new Event('change'));
    await settle();

    expect(select.value).toBe('new-read-policy');
    expect(text()).toContain('new-read-policy');
    expect(text()).toContain('GET /api/new-read');
    expect(text()).toContain('7 per 1 minute');
  });

  it('exits loading with an actionable error when backend returns an old or invalid catalog shape', async () => {
    http.expectOne(`${BASE}/demo-routes`).flush({ routes: [], policiesWithoutHandler: [] });
    await settle();

    expect(text()).not.toContain('Loading your policies');
    expect(text()).toContain('backend is probably running an older build');
    expect(options()).toEqual(['Targets unavailable']);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button.primary')
        ?.disabled,
    ).toBe(false);
  });

  it('shows each co-matching policy with its own algorithm and parameters', async () => {
    const normal = policy({ id: 'normal', limit: 50, parameterSummary: '50 per 1 minute' });
    const burst = policy({
      id: 'burst',
      algorithm: 'TOKEN_BUCKET',
      scope: 'APPLICATION',
      limit: null,
      capacity: 50,
      parameterSummary: 'capacity 50, refill 1 per 5 s',
    });
    await load([
      target({
        policyId: 'normal',
        parameterSummary: normal.parameterSummary,
        enforcedWith: [normal, burst],
      }),
    ]);

    expect(text()).toContain('normal');
    expect(text()).toContain('50 per 1 minute');
    expect(text()).toContain('burst');
    expect(text()).toContain('TOKEN_BUCKET');
    expect(text()).toContain('APPLICATION');
    expect(text()).toContain('capacity 50, refill 1 per 5 s');
    expect(text()).toContain('Selecting normal does not isolate it');
  });

  it('shows a loading state and provides a retry when policy targets fail to load', async () => {
    expect(text()).toContain('Loading your policies');

    http.expectOne(`${BASE}/demo-routes`).flush('', { status: 500, statusText: 'Server Error' });
    await settle();
    expect(text()).toContain('Policy targets could not be loaded');
    expect(options()).toEqual(['Targets unavailable']);

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('button.primary')!
      .click();
    fixture.detectChanges();
    await settle();
    await load([target()]);

    expect(options()).toHaveLength(1);
    expect(text()).not.toContain('Policy targets could not be loaded');
  });
});
