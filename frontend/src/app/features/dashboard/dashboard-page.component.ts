import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { OverviewSnapshot } from '../../core/dashboard-api.service';
import { formatWindow } from '../../core/demo-catalog';
import { PolicySummary } from '../../core/models';

interface FallbackPolicy extends PolicySummary {
  readonly sample: true;
}

/** Shipped defaults, used only when the API cannot be reached. Rendered with an explicit label. */
const SAMPLE_POLICIES: FallbackPolicy[] = [
  { id: 'products-read', method: 'GET', path: '/api/products', limit: 100, windowSeconds: 60, identity: 'IP', redisFailureMode: 'FAIL_OPEN', redisFailureModeLabel: 'Fail open', sample: true },
  { id: 'login-attempt', method: 'POST', path: '/api/login', limit: 10, windowSeconds: 60, identity: 'IP', redisFailureMode: 'FAIL_CLOSED', redisFailureModeLabel: 'Fail closed', sample: true },
  { id: 'order-create', method: 'POST', path: '/api/orders', limit: 30, windowSeconds: 60, identity: 'USER', redisFailureMode: 'FAIL_OPEN', redisFailureModeLabel: 'Fail open', sample: true },
];

@Component({
  selector: 'app-dashboard-page',
  standalone: true,
  imports: [],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dashboard-page.component.html',
  styleUrl: './dashboard-page.component.scss',
})
export class DashboardPageComponent {
  // App owns the single polling loop and passes the snapshot down, so the page never fetches again.
  readonly snapshot = input<OverviewSnapshot | null>(null);

  readonly loading = computed(() => this.snapshot() === null);

  readonly healthText = computed(() => {
    const snapshot = this.snapshot();
    if (!snapshot) return 'Checking';
    if (!snapshot.health.available) return 'Unavailable';
    return snapshot.health.value.state === 'healthy' ? 'Healthy' : 'Degraded';
  });

  readonly redisText = computed(() => {
    const snapshot = this.snapshot();
    if (!snapshot) return 'Checking';
    if (!snapshot.health.available) return 'Unknown';
    return snapshot.health.value.state === 'healthy' ? 'Connected' : 'Unavailable';
  });

  readonly policies = computed<PolicySummary[]>(() => {
    const result = this.snapshot()?.policies;
    if (result?.available) return result.value.policies;
    return SAMPLE_POLICIES;
  });

  readonly usingSamplePolicies = computed(() => !this.snapshot()?.policies.available);
  readonly policyCount = computed(() => this.snapshot()?.policies.available ? this.snapshot()!.policies.value.policyCount : null);
  readonly allowedShare = computed(() => {
    const snapshot = this.snapshot();
    if (!snapshot?.countersAvailable || snapshot.total === 0) return 0;
    return Math.round((snapshot.allowed / snapshot.total) * 100);
  });
  readonly rejectedShare = computed(() => {
    const snapshot = this.snapshot();
    if (!snapshot?.countersAvailable || snapshot.total === 0) return 0;
    return Math.round((snapshot.rejected / snapshot.total) * 100);
  });
  readonly redisErrorShare = computed(() => {
    const snapshot = this.snapshot();
    if (!snapshot?.countersAvailable || snapshot.total === 0) return 0;
    return Math.round((snapshot.redisError / snapshot.total) * 100);
  });

  formatWindow(seconds: number): string {
    return formatWindow(seconds);
  }
}
