import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, timeout } from 'rxjs';

import { API_CONFIG } from './api-config';
import { HealthResponse, MetricResponse, PolicyResponse } from './models';
import { AdminApiService } from './admin-api.service';

export type SourcedReason =
  /** The meter or the value has not been recorded yet; a real zero, not a failure. */
  | 'no-data'
  /** The outcome tag exists in the meter schema but has never been recorded. */
  | 'never-recorded'
  | 'unreachable'
  | 'timeout'
  | 'http-error'
  | null;

/** A value that may be absent, with the reason it is absent, so the UI never has to guess. */
export interface Sourced<T> {
  readonly value: T;
  readonly available: boolean;
  readonly reason: SourcedReason;
}

const REQUEST_TIMEOUT_MS = 8000;

/**
 * Every endpoint used here was measured against a running instance of this POC. There is no
 * latency endpoint, no per-minute rate endpoint, and no policy mutation API, and this service
 * does not pretend otherwise.
 */
@Injectable({ providedIn: 'root' })
export class ApiClientService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);
  private readonly admin = inject(AdminApiService);

  /**
   * Overall health only. Actuator runs with `show-details: never`, so there is no per-component
   * detail and no latency. DOWN means a health indicator failed, which in this POC means Redis.
   */
  health(): Observable<Sourced<{ status: string; state: 'healthy' | 'degraded' }>> {
    return this.http
      .get<HealthResponse>(this.url('/actuator/health'), { headers: new HttpHeaders({ Accept: 'application/json' }) })
      .pipe(
        timeout(REQUEST_TIMEOUT_MS),
        map(
          (body): Sourced<{ status: string; state: 'healthy' | 'degraded' }> => ({
            value: { status: body.status, state: body.status === 'UP' ? 'healthy' : 'degraded' },
            available: true,
            reason: null,
          }),
        ),
        catchError((error: HttpErrorResponse) =>
          of< Sourced<{ status: string; state: 'healthy' | 'degraded' }> >(this.toFailure(error)),
        ),
      );
  }

  /** Read-only policy metadata. */
  policies(): Observable<Sourced<PolicyResponse>> {
    return this.get<PolicyResponse>('/api/poc/policies');
  }

  /**
   * Read one counter. A 404 means "no data": the meter does not exist until a limited request has
   * been served, and an unseen tag value also 404s.
   */
  counter(tag?: string): Observable<Sourced<MetricResponse>> {
    const path = tag
      ? `/actuator/metrics/ratelimit.requests?tag=${encodeURIComponent(tag)}`
      : '/actuator/metrics/ratelimit.requests';
    return this.http
      .get<MetricResponse>(this.url(path), { headers: new HttpHeaders({ Accept: 'application/json' }) })
      .pipe(
        timeout(REQUEST_TIMEOUT_MS),
        map((body): Sourced<MetricResponse> => ({ value: body, available: true, reason: null })),
        catchError((error: HttpErrorResponse) => {
          if (error.status === 404) {
            return of< Sourced<MetricResponse> >({
              value: null as unknown as MetricResponse,
              available: false,
              reason: 'no-data',
            });
          }
          return of(this.toFailure<MetricResponse>(error));
        }),
      );
  }

  private get<T>(path: string): Observable<Sourced<T>> {
    let headers = new HttpHeaders({ Accept: 'application/json' });
    const auth = this.admin.authorizationHeader;
    if (path.startsWith('/api/poc/') && auth) {
      headers = headers.set('Authorization', auth);
    }
    return this.http
      .get<T>(this.url(path), { headers })
      .pipe(
        timeout(REQUEST_TIMEOUT_MS),
        map((body): Sourced<T> => ({ value: body, available: true, reason: null })),
        catchError((error: HttpErrorResponse) => of(this.toFailure<T>(error))),
      );
  }

  private toFailure<T>(error: HttpErrorResponse): Sourced<T> {
    const reason = error.status === 0 ? 'unreachable' : 'http-error';
    return { value: null as unknown as T, available: false, reason };
  }

  private url(path: string): string {
    return `${this.config.baseUrl}${path}`;
  }
}
