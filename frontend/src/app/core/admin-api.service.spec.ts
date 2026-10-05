import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { firstValueFrom } from 'rxjs';

import { AdminApiService } from './admin-api.service';

const BASE = '/api/admin/rate-limit';

describe('AdminApiService', () => {
  let api: AdminApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClientTesting()] });
    api = TestBed.inject(AdminApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    api.logout();
  });

  it('logs in on 200 and then lists with Basic auth', async () => {
    const login = firstValueFrom(api.login('admin', 'secret'));
    http.expectOne(`${BASE}/policies`).flush([{ id: 'a' }]);
    expect(await login).toEqual({ ok: true });
    expect(api.loggedIn()).toBe(true);

    const list = firstValueFrom(api.list());
    const req = http.expectOne(`${BASE}/policies`);
    expect(req.request.headers.get('Authorization')).toBe('Basic ' + btoa('admin:secret'));
    req.flush([{ id: 'a' }]);
    expect(await list).toEqual([{ id: 'a' }]);
  });

  it('reports 401 as a login failure without storing credentials', async () => {
    const login = firstValueFrom(api.login('admin', 'wrong'));
    http.expectOne(`${BASE}/policies`).flush({ message: 'x' }, { status: 401, statusText: 'Unauthorized' });
    const result = await login;
    expect(result.ok).toBe(false);
    expect(api.loggedIn()).toBe(false);
  });

  it('maps a 409 body to a version_conflict error', async () => {
    await loginAsAdmin();
    const update = firstValueFrom(
      api.update('p', { id: 'p', limit: 1, version: 1 }),
    );
    http
      .expectOne(`${BASE}/policies/p`)
      .flush(
        { error: 'version_conflict', message: 'stored version is 2', problems: [] },
        { status: 409, statusText: 'Conflict' },
      );
    const result = await update;
    expect(result).toMatchObject({ status: 409, code: 'version_conflict' });
  });

  it('maps a 400 body with field problems', async () => {
    await loginAsAdmin();
    const create = firstValueFrom(api.create({ id: 'bad' }));
    http
      .expectOne(`${BASE}/policies`)
      .flush(
        { error: 'policy_invalid', message: 'policy is invalid', problems: ['limit must be at least 1'] },
        { status: 400, statusText: 'Bad Request' },
      );
    const result = await create;
    expect(result).toMatchObject({ status: 400, problems: ['limit must be at least 1'] });
  });

  it('maps a 204 delete to deleted:true and logout drops the session', async () => {
    await loginAsAdmin();
    const remove = firstValueFrom(api.remove('p'));
    http.expectOne(`${BASE}/policies/p`).flush(null);
    expect(await remove).toEqual({ deleted: true });

    api.logout();
    expect(api.loggedIn()).toBe(false);
    const list = await firstValueFrom(api.list());
    expect(list).toMatchObject({ code: 'not-logged-in' });
    http.expectNone(`${BASE}/policies`);
  });

  it('issues a key once and revokes it', async () => {
    await loginAsAdmin();
    const created = firstValueFrom(api.createKey('owner-a', 'standard'));
    http.expectOne(`${BASE}/keys`).flush({
      keyId: 'abc12345',
      owner: 'owner-a',
      tier: 'standard',
      enabled: true,
      createdAt: '2026-01-01T00:00:00Z',
      key: 'rg_secret-once',
    });
    expect(await created).toMatchObject({ keyId: 'abc12345', key: 'rg_secret-once' });

    const keys = firstValueFrom(api.listKeys());
    http.expectOne(`${BASE}/keys`).flush([
      { keyId: 'abc12345', owner: 'owner-a', tier: 'standard', enabled: true },
    ]);
    const listed = await keys;
    expect(JSON.stringify(listed)).not.toContain('rg_secret-once');

    const revoked = firstValueFrom(api.revokeKey('abc12345'));
    http.expectOne(`${BASE}/keys/abc12345`).flush(null);
    expect(await revoked).toEqual({ revoked: true });
  });

  async function loginAsAdmin(): Promise<void> {
    const login = firstValueFrom(api.login('admin', 'secret'));
    http.expectOne(`${BASE}/policies`).flush([]);
    expect((await login).ok).toBe(true);
  }
});
