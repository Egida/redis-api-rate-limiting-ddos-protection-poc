import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AdminStore } from './core/admin-store.service';
import { StatusService } from './core/status.service';

/**
 * Application shell: header, section navigation, and the routed workspace.
 *
 * The shell owns no data of its own beyond the header's service status, which comes from the shared
 * status service. Administration state lives in AdminStore, so navigating between workspaces keeps
 * the loaded policy list instead of refetching on every route change.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly status = inject(StatusService);
  protected readonly store = inject(AdminStore);
}