import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { App } from './app';
import { routes } from './app.routes';

const METRIC_URL = '/actuator/metrics/ratelimit.requests';
const DEMO_ROUTES_ON_DISK = ['/api/products', '/api/login', '/api/orders'];

/** Answer the shell's read-only poll. A 404 on the counter is a real "no data yet" state. */
function answerStatus(http: HttpTestingController, status = 'UP'): void {
  for (const request of http.match(() => true)) {
    if (request.request.url === '/actuator/health') {
      request.flush({ status });
    } else if (request.request.url === METRIC_URL) {
      request.flush('', { status: 404, statusText: 'Not Found' });
    } else if (request.request.url === '/api/poc/policies') {
      request.flush({ source: 'managed', editable: true, policyCount: 0, policies: [] });
    } else {
      request.flush('', { status: 500, statusText: 'Unexpected call' });
    }
  }
}

describe('App shell', () => {
  let fixture: ComponentFixture<App>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(routes)],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(App);
    // TestBed does not bootstrap, so the router's first navigation is started explicitly.
    await TestBed.inject(Router).navigateByUrl('/overview');
    fixture.detectChanges();
    await fixture.whenStable();
    answerStatus(http);
    fixture.detectChanges();
    await fixture.whenStable();
    answerStatus(http);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    http.match(() => true);
  });

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('redirects to login when signed out and does not show nav links', () => {
    expect(text()).toContain('RateGuard');
    expect(text()).toContain('LOCAL POC');
    const links = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.app-nav a'),
    );
    expect(links.length).toBe(0);
  });

  it('shows login page when signed out', () => {
    expect(text()).toContain('Administrator login');
  });

  it('does not show header status when signed out', () => {
    expect(text()).not.toContain('Healthy');
    expect(text()).not.toContain('Last checked');
  });

  it('offers sign-in while logged out and never shows management controls', () => {
    expect(text()).toContain('Admin sign in');
    expect(text()).not.toContain('Create policy');
    expect(text()).not.toContain('Edit');
    expect(text()).not.toContain('Delete');
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
});