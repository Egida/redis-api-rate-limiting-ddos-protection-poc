import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AdminLoginComponent } from './admin-login.component';

const BASE = '/api/admin/rate-limit';

describe('AdminLoginComponent', () => {
  let fixture: ComponentFixture<AdminLoginComponent>;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminLoginComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(AdminLoginComponent);
    fixture.detectChanges();
  });

  /** Type into the form, then let NgModel's queued microtask process the events. */
  const type = async (name: string, value: string) => {
    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      `input[name="${name}"]`,
    )!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await new Promise((resolve) => setTimeout(resolve, 0));
  };

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('refuses an empty submit without calling the backend', async () => {
    await fixture.componentInstance.onLogin();
    fixture.detectChanges();
    expect(text()).toContain('Enter the administrator username and password');
    http.expectNone(`${BASE}/policies`);
  });

  it('signs in, clears the password field and routes to the requested page or overview', async () => {
    await type('admin-user', 'test-operator');
    await type('admin-password', 'test-only-not-a-real-secret');
    const pending = fixture.componentInstance.onLogin();
    http.expectOne(`${BASE}/policies`).flush([]);
    await pending;
    // NgModel writes the new model to the DOM in a microtask, so yield a macrotask first.
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    expect(navigate).toHaveBeenCalledWith('/overview');
    const password = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      'input[name="admin-password"]',
    )!;
    expect(password.value).toBe('');
  });

  it('explains a rejected login without echoing the password', async () => {
    await type('admin-user', 'pocadmin');
    await type('admin-password', 'wrong-secret');
    const pending = fixture.componentInstance.onLogin();
    http.expectOne(`${BASE}/policies`).flush(
      { error: 'unauthorized', message: 'Bad credentials' },
      { status: 401, statusText: 'Unauthorized' },
    );
    await pending;
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text()).toContain('Wrong administrator username or password.');
    expect(text()).not.toContain('wrong-secret');
  });

  it('reports an unreachable backend instead of a credential problem', async () => {
    await type('admin-user', 'pocadmin');
    await type('admin-password', 'whatever');
    const pending = fixture.componentInstance.onLogin();
    http.expectOne(`${BASE}/policies`).error(new ProgressEvent('network error'));
    await pending;
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text()).toContain('Backend unreachable');
  });

  it('blames the network on a timeout, never the password, and never renders "undefined"', async () => {
    // Type with real timers first: the type() helper awaits setTimeout, which fake timers freeze.
    await type('admin-user', 'pocadmin');
    await type('admin-password', 'timeout-secret');
    vi.useFakeTimers();
    try {
      const pending = fixture.componentInstance.onLogin();
      http.expectOne(`${BASE}/policies`);
      await vi.advanceTimersByTimeAsync(8000);
      await pending;
      // No whenStable() here: under fake timers the zone never reports stable, so it would hang.
      fixture.detectChanges();

      expect(text()).toContain('did not answer within');
      expect(text()).not.toContain('Wrong administrator');
      expect(text()).not.toContain('undefined');
      expect(text()).not.toContain('timeout-secret');
    } finally {
      vi.useRealTimers();
    }
  });
});