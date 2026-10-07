import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { StatusService } from '../../core/status.service';
import { AdminStore } from '../../core/admin-store.service';
import { DashboardPageComponent } from '../dashboard/dashboard-page.component';
import { RequestDemoComponent } from '../request-demo/request-demo.component';

/**
 * Read-only landing page: service health, cumulative request counters, the active policy snapshot,
 * and the explicitly user-triggered request demo. Nothing here writes.
 */
@Component({
  selector: 'app-overview-page',
  standalone: true,
  imports: [DashboardPageComponent, RequestDemoComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview-page.component.html',
})
export class OverviewPageComponent {
  protected readonly status = inject(StatusService);
  private readonly adminStore = inject(AdminStore);

  constructor() {
    this.status.prime();
    // Load admin store so live policy limits are available for the demo dropdown
    if (!this.adminStore.loaded()) {
      void this.adminStore.load();
    }
  }
}