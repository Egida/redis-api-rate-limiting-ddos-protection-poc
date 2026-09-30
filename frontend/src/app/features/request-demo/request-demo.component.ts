import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { DEMO_ROUTES, MAX_REQUEST_COUNT, clampRequestCount } from '../../core/demo-catalog';
import { DemoSummary } from '../../core/models';
import { DemoRunnerService } from './demo-runner.service';

@Component({
  selector: 'app-request-demo',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './request-demo.component.html',
  styleUrl: './request-demo.component.scss',
})
export class RequestDemoComponent {
  private readonly runner = inject(DemoRunnerService);

  readonly routes = DEMO_ROUTES;
  readonly maxCount = MAX_REQUEST_COUNT;

  readonly routeId = signal(DEMO_ROUTES[0].id);
  readonly requestCount = signal(20);
  readonly username = signal('');
  readonly password = signal('');
  readonly progressSent = signal(0);
  readonly progressTotal = signal(0);
  readonly formError = signal<string | null>(null);

  readonly running = this.runner.running;
  readonly summary = this.runner.summary;

  readonly route = computed(() => DEMO_ROUTES.find((r) => r.id === this.routeId()) ?? DEMO_ROUTES[0]);
  readonly needsAuth = computed(() => this.route().needsAuth);
  readonly progressPercent = computed(() => {
    const total = this.progressTotal();
    return total === 0 ? 0 : Math.round((this.progressSent() / total) * 100);
  });
  readonly statusBreakdown = computed(() => {
    const statuses = this.summary()?.statuses ?? {};
    return Object.entries(statuses)
      .map(([code, count]) => `HTTP ${code}: ${count}`)
      .join(', ');
  });

  onCountChange(value: string): void {
    this.requestCount.set(clampRequestCount(value));
  }

  onStart(): Promise<DemoSummary | null> {
    this.formError.set(null);
    if (this.running()) return Promise.resolve(null);

    const route = this.route();
    let credentials: { username: string; password: string } | null = null;
    if (route.needsAuth) {
      if (!this.username().trim() || !this.password()) {
        this.formError.set(
          'This route requires HTTP Basic credentials. Without them the API answers 401 and the ' +
            'run would stop on the first request.',
        );
        return Promise.resolve(null);
      }
      credentials = { username: this.username().trim(), password: this.password() };
    }

    const total = clampRequestCount(this.requestCount());
    this.progressTotal.set(total);
    this.progressSent.set(0);

    return this.runner.run(route, total, credentials, (sent, target) => {
      this.progressSent.set(sent);
      this.progressTotal.set(target);
    }).then((summary) => {
      // Credentials are dropped as soon as the run ends.
      this.password.set('');
      return summary;
    });
  }

  onCancel(): void {
    this.runner.cancel();
  }
}
