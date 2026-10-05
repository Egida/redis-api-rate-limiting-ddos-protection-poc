import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, timeout } from 'rxjs';

import { API_CONFIG } from './api-config';
import {
  AdminApiError,
  AdminPolicy,
  ApiKeyCreated,
  ApiKeyMetadata,
  AuditRecord,
  Capabilities,
  PolicyEdit,
} from './admin-models';

const REQUEST_TIMEOUT_MS = 8000;
const BASE = '/api/admin/rate-limit';

/**
 * Administration API client.
 *
 * Authentication is HTTP Basic with credentials supplied at login and held only in this service's
 * memory for the page session. They are never written to localStorage, sessionStorage, a URL, or a
 * log line. Logging out (or reloading the page) drops them.
 */
@Injectable({ providedIn: 'root' })
export class AdminApiService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);

  private credentials: { username: string; password: string } | null = null;

  /** True once a login attempt has succeeded against the backend. */
  readonly loggedIn = signal(false);
  readonly loginName = signal<string | null>(null);

  login(username: string, password: string): Observable<{ ok: true } | { ok: false; error: AdminApiError }> {
    const candidate = { username: username.trim(), password };
    return this.request<AdminPolicy[]>('GET', '/policies', candidate).pipe(
      map((result) => {
        if (isAdminError(result)) {
          return { ok: false, error: result } as const;
        }
        this.credentials = candidate;
        this.loggedIn.set(true);
        this.loginName.set(candidate.username);
        return { ok: true } as const;
      }),
    );
  }

  logout(): void {
    this.credentials = null;
    this.loggedIn.set(false);
    this.loginName.set(null);
  }

  capabilities(): Observable<Capabilities | AdminApiError> {
    return this.authed<Capabilities>('GET', '/capabilities');
  }

  list(): Observable<AdminPolicy[] | AdminApiError> {
    return this.authed<AdminPolicy[]>('GET', '/policies');
  }

  get(id: string): Observable<AdminPolicy | AdminApiError> {
    return this.authed<AdminPolicy>('GET', `/policies/${encodeURIComponent(id)}`);
  }

  create(edit: PolicyEdit): Observable<AdminPolicy | AdminApiError> {
    return this.authed<AdminPolicy>('POST', '/policies', edit);
  }

  update(id: string, edit: PolicyEdit): Observable<AdminPolicy | AdminApiError> {
    return this.authed<AdminPolicy>('PUT', `/policies/${encodeURIComponent(id)}`, edit);
  }

  setEnabled(
    id: string,
    enabled: boolean,
    version: number,
  ): Observable<AdminPolicy | AdminApiError> {
    return this.authed<AdminPolicy>('PATCH', `/policies/${encodeURIComponent(id)}/enabled`, {
      enabled,
      version,
    });
  }

  remove(id: string): Observable<{ deleted: true } | AdminApiError> {
    return this.authed<void>('DELETE', `/policies/${encodeURIComponent(id)}`).pipe(
      map((result) => (isAdminError(result) ? result : { deleted: true as const })),
    );
  }

  audit(limit = 50): Observable<AuditRecord[] | AdminApiError> {
    return this.authed<AuditRecord[]>('GET', `/audit?limit=${limit}`);
  }

  createKey(owner: string, tier: string): Observable<ApiKeyCreated | AdminApiError> {
    return this.authed<ApiKeyCreated>('POST', '/keys', { owner, tier });
  }

  listKeys(): Observable<ApiKeyMetadata[] | AdminApiError> {
    return this.authed<ApiKeyMetadata[]>('GET', '/keys');
  }

  revokeKey(keyId: string): Observable<{ revoked: true } | AdminApiError> {
    return this.authed<void>('DELETE', `/keys/${encodeURIComponent(keyId)}`).pipe(
      map((result) => (isAdminError(result) ? result : { revoked: true as const })),
    );
  }

  private authed<T>(method: string, path: string, body?: unknown): Observable<T | AdminApiError> {
    if (!this.credentials) {
      return of({ status: 0, code: 'not-logged-in', message: 'Log in first.', problems: [] });
    }
    return this.request<T>(method, path, this.credentials, body);
  }

  private request<T>(
    method: string,
    path: string,
    credentials: { username: string; password: string },
    body?: unknown,
  ): Observable<T | AdminApiError> {
    const headers = new HttpHeaders({
      Accept: 'application/json',
      Authorization: 'Basic ' + btoa(`${credentials.username}:${credentials.password}`),
    });
    return this.http
      .request<T>(method, `${this.config.baseUrl}${BASE}${path}`, { headers, body })
      .pipe(
        timeout(REQUEST_TIMEOUT_MS),
        catchError((error: HttpErrorResponse) => of(this.toError(error))),
      );
  }

  private toError(error: HttpErrorResponse): AdminApiError {
    if (error.status === 0) {
      return { status: 0, code: 'unreachable', message: 'Backend unreachable.', problems: [] };
    }
    const body = error.error as { error?: string; message?: string; problems?: string[] } | null;
    return {
      status: error.status,
      code: body?.error ?? 'http-error',
      message: body?.message ?? `HTTP ${error.status}`,
      problems: body?.problems ?? [],
    };
  }
}

/** Type guard: a service result is an error when it carries a numeric status. */
export function isAdminError(value: unknown): value is AdminApiError {
  return typeof value === 'object' && value !== null && 'status' in value;
}
