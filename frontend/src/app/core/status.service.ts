import { Injectable, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, interval, of } from 'rxjs';
import { startWith, switchMap } from 'rxjs/operators';

import { API_CONFIG } from './api-config';
import { DashboardApiService, OverviewSnapshot } from './dashboard-api.service';

/**
 * The single polling loop for read-only health and counter data.
 *
 * It lives in a root service rather than in a page so the header keeps showing service state on
 * every route while only one request set runs per tick. Polling pauses while the document is hidden
 * and never sends demo traffic.
 */
@Injectable({ providedIn: 'root' })
export class StatusService {
  private readonly api = inject(DashboardApiService);
  private readonly config = inject(API_CONFIG);
  private readonly destroyRef = inject(DestroyRef);

  readonly snapshot = signal<OverviewSnapshot | null>(null);
  readonly lastChecked = signal<Date | null>(null);

  private polling?: Subscription;

  constructor() {
    this.polling = interval(this.config.pollIntervalMs)
      .pipe(
        startWith(0),
        switchMap(() => (this.isHidden() ? of(null) : this.api.load())),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((snapshot) => {
        if (snapshot) {
          this.snapshot.set(snapshot);
          this.lastChecked.set(new Date());
        }
      });
  }

  refresh(): void {
    this.api.load().subscribe((snapshot) => {
      this.snapshot.set(snapshot);
      this.lastChecked.set(new Date());
    });
  }

  /** Kick off the first poll without waiting a full interval. Used by pages that mount directly. */
  prime(): void {
    if (this.snapshot() === null) this.refresh();
  }

  stateText(): string {
    const snapshot = this.snapshot();
    if (!snapshot) return 'Checking';
    if (!snapshot.health.available) return 'Unavailable';
    return snapshot.health.value.state === 'healthy' ? 'Healthy' : 'Degraded';
  }

  dotClass(): string {
    const snapshot = this.snapshot();
    if (!snapshot) return 'dot dot-unknown';
    if (!snapshot.health.available) return 'dot dot-bad';
    return snapshot.health.value.state === 'healthy' ? 'dot dot-ok' : 'dot dot-warn';
  }

  lastCheckedText(): string {
    const value = this.lastChecked();
    return value ? `Last checked ${value.toLocaleTimeString()}` : 'Not checked yet';
  }

  private isHidden(): boolean {
    return typeof document !== 'undefined' && document.hidden;
  }
}