import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, of, switchMap } from 'rxjs';
import { map } from 'rxjs/operators';

import { ApiClientService, Sourced } from './api-client.service';
import { MetricResponse, PolicyResponse } from './models';

export interface OverviewSnapshot {
  health: Sourced<{ status: string; state: 'healthy' | 'degraded' }>;
  allowed: number;
  rejected: number;
  redisError: number;
  total: number;
  /** False when the metric endpoint itself is unavailable, as opposed to zero rejections. */
  countersAvailable: boolean;
  countersReason: Sourced<unknown>['reason'];
  /** Human notes for outcomes the meter has never recorded, so "0" is not read as "no traffic". */
  neverRecorded: string[];
  fetchedAt: Date;
}

/** Marker for an outcome tag the meter has never seen: available with a zero value, not an error. */
function notRecorded(): Sourced<MetricResponse> {
  return { value: null as unknown as MetricResponse, available: true, reason: 'never-recorded' };
}

/**
 * Aggregates the three counter reads.
 *
 * <p>Policy rows are deliberately absent: they come from the authenticated managed-policies API via
 * {@code AdminStore}, so this never reads the unauthenticated YAML view as if it were live.
 *
 * The unfiltered metric response already lists which `outcome` values exist, so tag-filtered
 * follow-ups are only issued for values that are actually present. Requesting an unseen value
 * returns HTTP 404, which would fill the browser console with avoidable errors.
 */
@Injectable({ providedIn: 'root' })
export class DashboardApiService {
  private readonly api = inject(ApiClientService);

  load(): Observable<OverviewSnapshot> {
    return forkJoin({
      health: this.api.health(),
      base: this.api.counter(),
    }).pipe(
      switchMap(({ health, base }) => {
        const outcomes = base.available
          ? (base.value.availableTags?.find((t) => t.tag === 'outcome')?.values ?? [])
          : [];
        const has = (value: string) => outcomes.includes(value);

        return forkJoin({
          allowed: has('allowed') ? this.api.counter('outcome:allowed') : of(notRecorded()),
          rejected: has('rejected') ? this.api.counter('outcome:rejected') : of(notRecorded()),
          redisError: has('error') ? this.api.counter('outcome:error') : of(notRecorded()),
        }).pipe(
          map(({ allowed, rejected, redisError }) => {
            // A 'never-recorded' outcome is a real zero, but carries no payload, so guard on the body.
            const count = (c: Sourced<MetricResponse>) => {
              const measurements = c.value?.measurements;
              if (!measurements) return 0;
              return measurements.find((m) => m.statistic === 'COUNT')?.value ?? 0;
            };
            const neverRecorded: string[] = [];
            if (allowed.reason === 'never-recorded') neverRecorded.push('no requests yet');
            if (rejected.reason === 'never-recorded') neverRecorded.push('no 429 yet');
            if (redisError.reason === 'never-recorded') neverRecorded.push('no Redis errors');

            return {
              health,
              allowed: count(allowed),
              rejected: count(rejected),
              redisError: count(redisError),
              total: count(allowed) + count(rejected) + count(redisError),
              countersAvailable: base.available,
              countersReason: base.reason,
              neverRecorded,
              fetchedAt: new Date(),
            };
          }),
        );
      }),
    );
  }
}
