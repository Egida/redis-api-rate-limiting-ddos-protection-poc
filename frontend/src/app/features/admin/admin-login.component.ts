import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ActivatedRoute } from '@angular/router';

import { AdminApiService, TIMEOUT_STATUS } from '../../core/admin-api.service';
import { AdminApiError } from '../../core/admin-models';

/**
 * The only place administrator credentials are entered. On success the router takes over to the
 * policies workspace; the credentials stay in the API service memory and are never stored.
 */
@Component({
  selector: 'app-admin-login',
  standalone: true,
  imports: [FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './admin-login.component.html',
  styleUrl: './admin-login.component.scss',
})
export class AdminLoginComponent {
  private readonly api = inject(AdminApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly username = signal('');
  readonly password = signal('');
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  constructor() {
    if (this.api.loggedIn()) {
      const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') || '/overview';
      void this.router.navigateByUrl(returnUrl);
    }
  }

  async onLogin(): Promise<void> {
    this.error.set(null);
    if (!this.username().trim() || !this.password()) {
      this.error.set('Enter the administrator username and password.');
      return;
    }
    this.busy.set(true);
    try {
      const result = await firstValueFrom(this.api.login(this.username(), this.password()));
      this.password.set('');
      if (result.ok) {
        this.username.set('');
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') || '/overview';
        await this.router.navigateByUrl(returnUrl);
      } else {
        this.error.set(describeLoginFailure(result.error));
      }
    } finally {
      this.busy.set(false);
    }
  }
}

/**
 * Turns a login failure into one operator-actionable sentence. A 401 is a credential problem; a
 * timeout or a transport failure is not, and telling someone to retype a correct password would be
 * wrong advice.
 */
export function describeLoginFailure(error: AdminApiError): string {
  if (error.status === 401) return 'Wrong administrator username or password.';
  if (error.status === 403) return 'That account is not an administrator.';
  if (error.status === TIMEOUT_STATUS) return error.message;
  if (error.status === 0) return 'Backend unreachable. Is the API running?';
  return error.message;
}
