import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { AdminApiService } from '../../core/admin-api.service';
import { AuditPageComponent } from './audit-page.component';

const BASE = '/api/admin/rate-limit';

const entry = (n: number, fields: string[]) => ({
  at: `2026-10-05T10:${String(n).padStart(2, '0')}:00.123456789Z`,
  actor: 'pocadmin',
  policyId: 'products-read',
  operation: 'UPDATE',
  resultingVersion: n,
  changedFields: fields,
});

const RECORDS = [entry(1, ['limit', 'window', 'scope', 'algorithm', 'enabled'])];

describe('AuditPageComponent', () => {
  let fixture: ComponentFixture<AuditPageComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AuditPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    // Authenticate before the page is constructed: an unauthenticated console gets no data.
    const login = firstValueFrom(TestBed.inject(AdminApiService).login('pocadmin', 'test-only'));
    http.expectOne(`${BASE}/policies`).flush([]);
    await login;
    fixture = TestBed.createComponent(AuditPageComponent);
    fixture.detectChanges();
  });

  const settle = async () => {
    await fixture.whenStable();
    fixture.detectChanges();
  };

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('requests a bounded window rather than the whole history', async () => {
    http.expectOne((req) => req.url.startsWith(`${BASE}/audit`)).flush([entry(1, ['limit'])]);
    await settle();
    expect(text()).toContain('1 records loaded');
  });

  it('reports an empty window without rendering a bare table', async () => {
    http.expectOne((req) => req.url.startsWith(`${BASE}/audit`)).flush([]);
    await settle();
    expect(text()).toContain('No audit entries in this window');
  });

  it('truncates a long field list and reveals the rest on demand', async () => {
    http.expectOne((req) => req.url.startsWith(`${BASE}/audit`)).flush(RECORDS);
    await settle();

    expect(text()).toContain('limit, window, scope');
    expect(text()).toContain('+2 more');
    expect(text()).not.toContain('algorithm, enabled');

    fixture.componentInstance['toggle'](`${RECORDS[0].at}|products-read|UPDATE`);
    await settle();
    expect(text()).toContain('algorithm');
    expect(text()).toContain('Hide details');
  });

  it('pages through the loaded window instead of rendering every row', async () => {
    const rows = Array.from({ length: 45 }, (_, i) => entry(i + 1, ['limit']));
    http.expectOne((req) => req.url.startsWith(`${BASE}/audit`)).flush(rows);
    await settle();
    expect(text()).toContain('45 records loaded, 1 of 3 pages');

    await fixture.componentInstance['nextPage']();
    await settle();
    expect(text()).toContain('2 of 3 pages');
    // Page two starts at record 21; assert the row, not a locale-formatted timestamp.
    expect(text()).toContain('v21');
  });
});