import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { MAX_REQUEST_COUNT, clampRequestCount, DemoCatalogService, DemoRoute } from '../../core/demo-catalog';
import { DemoSummary, PolicyResponse } from '../../core/models';
import { ApiClientService } from '../../core/api-client.service';
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
  private readonly apiClient = inject(ApiClientService);
  private readonly catalog = inject(DemoCatalogService);

  readonly maxCount = MAX_REQUEST_COUNT;

  readonly routes = signal<DemoRoute[]>([]);
  readonly catalogLoaded = signal(false);
  readonly catalogError = signal<string | null>(null);

  readonly routeId = signal('products');
  readonly requestCount = signal(20);
  readonly username = signal('');
  readonly password = signal('');
  readonly progressSent = signal(0);
  readonly progressTotal = signal(0);
  readonly formError = signal<string | null>(null);

  readonly running = this.runner.running;
  readonly summary = computed(() => this.runner.summary());

  readonly route = computed(() => this.routes().find((r) => r.id === this.routeId()) ?? this.routes()[0]);
  readonly needsAuth = computed(() => this.route()?.needsAuth ?? false);

  readonly publicPolicies = signal<PolicyResponse['policies']>([]);

  constructor() {
    this.catalog.fetchCatalog().subscribe({
      next: (data) => {
        this.routes.set(data.entries);
        this.catalogLoaded.set(true);
        if (data.entries.length > 0) {
          this.routeId.set(data.entries[0].id);
        }
      },
      error: () => {
        this.routes.set([...this.catalog.fallbackRoutes]);
        this.catalogLoaded.set(true);
        this.catalogError.set('Backend catalog unavailable — showing fallback routes. Live limits may not match.');
        if (this.catalog.fallbackRoutes.length > 0) {
          this.routeId.set(this.catalog.fallbackRoutes[0].id);
        }
      },
    });
    this.apiClient.policies().subscribe((sourced) => {
      if (sourced.available && sourced.value) {
        this.publicPolicies.set(sourced.value.policies);
      }
    });
  }

  readonly liveLimit = computed(() => {
    const r = this.route();
    if (!r) return null;
    const policy = this.publicPolicies().find((p) => p.method === r.method && p.path === r.path);
    return policy?.limit ?? null;
  });

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
    if (!route) {
      this.formError.set('No route selected.');
      return Promise.resolve(null);
    }
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
      this.password.set('');
      return summary;
    });
  }

  onCancel(): void {
    this.runner.cancel();
  }
}
