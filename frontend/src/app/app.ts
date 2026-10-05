import { ChangeDetectionStrategy, Component, DestroyRef, OnDestroy, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, interval, of, timer } from 'rxjs';
import { startWith, switchMap } from 'rxjs/operators';

import { API_CONFIG } from './core/api-config';
import { DashboardApiService, OverviewSnapshot } from './core/dashboard-api.service';
import { DashboardPageComponent } from './features/dashboard/dashboard-page.component';
import { RequestDemoComponent } from './features/request-demo/request-demo.component';
import { AdminPoliciesComponent } from './features/admin/admin-policies.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [DashboardPageComponent, RequestDemoComponent, AdminPoliciesComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnDestroy {
  private readonly api = inject(DashboardApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly config = inject(API_CONFIG);

  readonly snapshot = signal<OverviewSnapshot | null>(null);
  readonly lastChecked = signal<Date | null>(null);

  private polling?: Subscription;

  constructor() {
    // The header, cards, policy table and metrics all read this one snapshot, so a single request
    // set serves the whole page. Polling pauses while the document is hidden.
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

  ngOnDestroy(): void {
    this.polling?.unsubscribe();
  }

  refresh(): void {
    this.api.load().subscribe((snapshot) => {
      this.snapshot.set(snapshot);
      this.lastChecked.set(new Date());
    });
  }

  private isHidden(): boolean {
    return typeof document !== 'undefined' && document.hidden;
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
}
