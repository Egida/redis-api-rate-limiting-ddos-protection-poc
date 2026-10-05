import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { AdminPoliciesComponent } from './admin-policies.component';

const BASE = '/api/admin/rate-limit';

describe('AdminPoliciesComponent', () => {
  let fixture: ComponentFixture<AdminPoliciesComponent>;
  let component: AdminPoliciesComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminPoliciesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    fixture = TestBed.createComponent(AdminPoliciesComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  it('shows the login form before authentication', () => {
    expect(component.loggedIn()).toBe(false);
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Rate Limit Policies');
    expect(text).toContain('Administrator credentials');
  });

  /**
   * Complete a login and the reload it triggers. The login flush must be followed by a macrotask
   * yield: onLogin only subscribes to the reload requests in a promise continuation, so flushing
   * them in the same synchronous block would find nothing outstanding (and awaiting onLogin first
   * would deadlock waiting for responses nobody flushed).
   */
  async function loginAndReload(
    caps: object = {
      algorithms: [{ name: 'FIXED_WINDOW', implemented: true, note: '' }],
      scopes: [{ name: 'IP', implemented: true, note: '' }],
      composition: 'AND',
      topology: 'single-redis',
    },
    policies: object = [],
  ): Promise<void> {
    component.loginUser.set('admin');
    component.loginPassword.set('secret');
    const loggingIn = component.onLogin();
    http.expectOne(`${BASE}/policies`).flush([]);
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne(`${BASE}/capabilities`).flush(caps);
    http.expectOne(`${BASE}/policies`).flush(policies);
    http.expectOne((req) => req.url.startsWith(`${BASE}/audit`)).flush([]);
    http.expectOne(`${BASE}/keys`).flush([]);
    await loggingIn;
  }

  it('loads capabilities, policies and audit after login', async () => {
    await loginAndReload(undefined, [
      { id: 'products-read', method: 'GET', path: '/api/products', algorithm: 'FIXED_WINDOW' },
    ]);
    fixture.detectChanges();

    expect(component.loggedIn()).toBe(true);
    expect(component.policies().map((p) => p.id)).toEqual(['products-read']);
    expect(component.implementedAlgorithms().map((a) => a.name)).toEqual(['FIXED_WINDOW']);
  });

  it('rejects an unimplemented algorithm in local validation', async () => {
    await loginAndReload({
      algorithms: [
        { name: 'FIXED_WINDOW', implemented: true, note: '' },
        { name: 'TOKEN_BUCKET', implemented: false, note: 'nope' },
      ],
      scopes: [{ name: 'IP', implemented: true, note: '' }],
      composition: 'AND',
      topology: 'single-redis',
    });

    component.onCreate();
    component.fId.set('x');
    component.fPath.set('/api/x');
    component.fAlgorithm.set('TOKEN_BUCKET');
    await component.onSave();
    expect(component.formError()).toContain('not enforced');
    http.expectNone(`${BASE}/policies`);
  });
});
