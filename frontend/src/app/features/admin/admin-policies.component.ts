import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';

import {
  AdminApiService,
  isAdminError,
} from '../../core/admin-api.service';
import {
  AdminPolicy,
  ApiKeyMetadata,
  AuditRecord,
  Capabilities,
  durationToSeconds,
  secondsToDuration,
} from '../../core/admin-models';
import { DemoRoute } from '../../core/demo-catalog';
import { DemoSummary } from '../../core/models';
import { DemoRunnerService } from '../request-demo/demo-runner.service';

const PROBE_MAX = 30;

type EditorMode = 'list' | 'create' | 'edit';

/**
 * Administration section for rate-limit policies.
 *
 * Everything shown here comes from the protected admin API; nothing is guessed locally. The
 * algorithm and scope dropdowns bind to /capabilities, so an option is selectable only when the
 * backend enforces it. Successful saves are re-read from the server before the list updates, which
 * is what proves the running instances picked the change up.
 */
@Component({
  selector: 'app-admin-policies',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './admin-policies.component.html',
  styleUrl: './admin-policies.component.scss',
})
export class AdminPoliciesComponent {
  private readonly api = inject(AdminApiService);
  private readonly probeRunner = inject(DemoRunnerService);

  readonly loggedIn = this.api.loggedIn;
  readonly loginName = this.api.loginName;

  readonly loginUser = signal('');
  readonly loginPassword = signal('');
  readonly loginError = signal<string | null>(null);
  readonly busy = signal(false);

  readonly capabilities = signal<Capabilities | null>(null);
  readonly policies = signal<AdminPolicy[]>([]);
  readonly audit = signal<AuditRecord[]>([]);
  readonly apiKeys = signal<ApiKeyMetadata[]>([]);
  readonly loadError = signal<string | null>(null);

  readonly mode = signal<EditorMode>('list');
  readonly editingId = signal<string | null>(null);
  readonly formError = signal<string | null>(null);
  readonly formInfo = signal<string | null>(null);
  readonly confirmDeleteId = signal<string | null>(null);

  // Editor fields. Version is read-only: it carries the value the form was loaded with.
  readonly fId = signal('');
  readonly fName = signal('');
  readonly fMethod = signal('GET');
  readonly fPath = signal('');
  readonly fAlgorithm = signal('FIXED_WINDOW');
  readonly fScope = signal('IP');
  readonly fWindowSeconds = signal(60);
  readonly fLimit = signal(100);
  readonly fCapacity = signal(100);
  readonly fRefillSeconds = signal(10);
  readonly fCost = signal(1);
  readonly fDrainRate = signal(10);
  readonly fQueueCapacity = signal(20);
  readonly fMaxConcurrent = signal(10);
  readonly fLeaseSeconds = signal(30);
  readonly fEnabled = signal(true);
  readonly fFailureMode = signal('' as '' | 'FAIL_OPEN' | 'FAIL_CLOSED');
  readonly fVersion = signal(1);

  // API-key management. The raw secret is shown exactly once after creation.
  readonly keyOwner = signal('');
  readonly keyTier = signal('standard');
  readonly newRawKey = signal<string | null>(null);
  readonly confirmRevokeId = signal<string | null>(null);
  readonly keysError = signal<string | null>(null);

  // Bounded probe state.
  readonly probePolicyId = signal<string | null>(null);
  readonly probeCount = signal(10);
  readonly probeSummary = signal<DemoSummary | null>(null);
  readonly probeRunning = this.probeRunner.running;

  readonly implementedAlgorithms = computed(
    () => this.capabilities()?.algorithms.filter((a) => a.implemented) ?? [],
  );
  readonly unimplementedAlgorithms = computed(
    () => this.capabilities()?.algorithms.filter((a) => !a.implemented) ?? [],
  );
  readonly implementedScopes = computed(
    () => this.capabilities()?.scopes.filter((s) => s.implemented) ?? [],
  );
  readonly editingPolicy = computed(
    () => this.policies().find((p) => p.id === this.editingId()) ?? null,
  );
  readonly windowSecondsOf = computed(() => {
    const policy = this.editingPolicy();
    return policy ? durationToSeconds(policy.window) : null;
  });

  async onLogin(): Promise<void> {
    this.loginError.set(null);
    if (!this.loginUser().trim() || !this.loginPassword()) {
      this.loginError.set('Enter the administrator username and password.');
      return;
    }
    this.busy.set(true);
    try {
      const result = await firstValueFrom(this.api.login(this.loginUser(), this.loginPassword()));
      this.loginPassword.set('');
      if (result.ok) {
        this.loginUser.set('');
        await this.reloadAll();
      } else {
        this.loginError.set(this.describeLoginFailure(result.error.status, result.error.message));
      }
    } finally {
      this.busy.set(false);
    }
  }

  onLogout(): void {
    this.api.logout();
    this.capabilities.set(null);
    this.policies.set([]);
    this.audit.set([]);
    this.apiKeys.set([]);
    this.newRawKey.set(null);
    this.mode.set('list');
  }

  async reloadAll(): Promise<void> {
    this.loadError.set(null);
    const [caps, list, audit, keys] = await Promise.all([
      firstValueFrom(this.api.capabilities()),
      firstValueFrom(this.api.list()),
      firstValueFrom(this.api.audit(50)),
      firstValueFrom(this.api.listKeys()),
    ]);
    const failed = [caps, list, audit, keys].find(isAdminError);
    if (failed && isAdminError(failed)) {
      this.loadError.set(failed.message);
      return;
    }
    if (!isAdminError(caps)) this.capabilities.set(caps);
    if (!isAdminError(list)) this.policies.set(list);
    if (!isAdminError(audit)) this.audit.set(audit);
    if (!isAdminError(keys)) this.apiKeys.set(keys);
  }

  onCreate(): void {
    this.mode.set('create');
    this.editingId.set(null);
    this.formError.set(null);
    this.formInfo.set(null);
    this.fId.set('');
    this.fName.set('');
    this.fMethod.set('GET');
    this.fPath.set('');
    this.fAlgorithm.set('FIXED_WINDOW');
    this.fScope.set('IP');
    this.fWindowSeconds.set(60);
    this.fLimit.set(100);
    this.fCapacity.set(100);
    this.fRefillSeconds.set(10);
    this.fCost.set(1);
    this.fDrainRate.set(10);
    this.fQueueCapacity.set(20);
    this.fMaxConcurrent.set(10);
    this.fLeaseSeconds.set(30);
    this.fEnabled.set(true);
    this.fFailureMode.set('');
    this.fVersion.set(1);
  }

  onEdit(policy: AdminPolicy): void {
    this.mode.set('edit');
    this.editingId.set(policy.id);
    this.formError.set(null);
    this.formInfo.set(null);
    this.fId.set(policy.id);
    this.fName.set(policy.name);
    this.fMethod.set(policy.method);
    this.fPath.set(policy.path ?? '');
    this.fAlgorithm.set(policy.algorithm);
    this.fScope.set(policy.scope);
    this.fWindowSeconds.set(durationToSeconds(policy.window) ?? 60);
    this.fLimit.set(policy.limit ?? 100);
    this.fCapacity.set(policy.capacity ?? 100);
    this.fRefillSeconds.set(durationToSeconds(policy.refillInterval) ?? 10);
    this.fCost.set(policy.cost ?? 1);
    this.fDrainRate.set(policy.drainRate ?? 10);
    this.fQueueCapacity.set(policy.queueCapacity ?? 20);
    this.fMaxConcurrent.set(policy.maxConcurrent ?? 10);
    this.fLeaseSeconds.set(durationToSeconds(policy.leaseDuration) ?? 30);
    this.fEnabled.set(policy.enabled);
    this.fFailureMode.set(policy.onRedisError ?? '');
    this.fVersion.set(policy.version);
  }

  onCancelEdit(): void {
    this.mode.set('list');
    this.editingId.set(null);
    this.formError.set(null);
    this.formInfo.set(null);
  }

  async onSave(): Promise<void> {
    this.formError.set(null);
    this.formInfo.set(null);
    const problems = this.validateForm();
    if (problems.length > 0) {
      this.formError.set(problems.join(' '));
      return;
    }
    const failureMode = this.fFailureMode();
    const edit = {
      id: this.fId().trim(),
      name: this.fName().trim() || this.fId().trim(),
      method: this.fMethod(),
      path: this.fScope() === 'GLOBAL' ? null : this.fPath().trim(),
      algorithm: this.fAlgorithm(),
      scope: this.fScope(),
      window: secondsToDuration(this.fWindowSeconds()),
      limit: this.fLimit(),
      capacity: this.fCapacity(),
      refillInterval: secondsToDuration(this.fRefillSeconds()),
      cost: this.fCost(),
      drainRate: this.fDrainRate(),
      queueCapacity: this.fQueueCapacity(),
      maxConcurrent: this.fMaxConcurrent(),
      leaseDuration: secondsToDuration(this.fLeaseSeconds()),
      enabled: this.fEnabled(),
      onRedisError: (failureMode === '' ? null : failureMode) as 'FAIL_OPEN' | 'FAIL_CLOSED' | null,
      version: this.mode() === 'edit' ? this.fVersion() : undefined,
    };
    this.busy.set(true);
    try {
      const saved =
        this.mode() === 'create'
          ? await firstValueFrom(this.api.create(edit))
          : await firstValueFrom(this.api.update(this.fId(), { ...edit, version: this.fVersion() }));
      if (isAdminError(saved)) {
        this.formError.set(this.describeSaveFailure(saved.status, saved.code, saved.message, saved.problems));
        return;
      }
      const policy = saved;
      this.formInfo.set(
        `Saved ${policy.id} at version ${policy.version}. Enforcement reads it from shared Redis, no restart needed.`,
      );
      await this.reloadAll();
      this.onEdit(policy);
    } finally {
      this.busy.set(false);
    }
  }

  async onToggleEnabled(policy: AdminPolicy): Promise<void> {
    this.formError.set(null);
    this.busy.set(true);
    try {
      const result = await firstValueFrom(this.api.setEnabled(policy.id, !policy.enabled, policy.version));
      if (isAdminError(result)) {
        this.formError.set(this.describeSaveFailure(result.status, result.code, result.message, result.problems));
        return;
      }
      await this.reloadAll();
    } finally {
      this.busy.set(false);
    }
  }

  async onDelete(policy: AdminPolicy): Promise<void> {
    if (this.confirmDeleteId() !== policy.id) {
      this.confirmDeleteId.set(policy.id);
      return;
    }
    this.confirmDeleteId.set(null);
    this.busy.set(true);
    try {
      const result = await firstValueFrom(this.api.remove(policy.id));
      if (isAdminError(result)) {
        this.formError.set(this.describeSaveFailure(result.status, result.code, result.message, result.problems));
        return;
      }
      this.onCancelEdit();
      await this.reloadAll();
    } finally {
      this.busy.set(false);
    }
  }

  async onProbe(policy: AdminPolicy): Promise<void> {
    if (this.probeRunning()) return;
    this.probePolicyId.set(policy.id);
    this.probeSummary.set(null);
    const route: DemoRoute = {
      id: `probe:${policy.id}`,
      label: `${policy.method} ${policy.path ?? '(global)'}`,
      method: policy.method === 'GET' ? 'GET' : 'POST',
      path: policy.path ?? '/',
      expectedLimit: policy.limit ?? 0,
      identity: policy.scope === 'USER' ? 'USER' : 'IP',
      needsAuth: false,
      note: 'Admin probe: bounded, sequential, cancellable traffic.',
    };
    const total = Math.max(1, Math.min(this.probeCount(), PROBE_MAX));
    const summary = await this.probeRunner.run(route, total, null, () => undefined);
    this.probeSummary.set(summary);
  }

  onProbeCancel(): void {
    this.probeRunner.cancel();
  }

  probeWindowNote(): string | null {
    const summary = this.probeSummary();
    const policy = this.policies().find((p) => p.id === this.probePolicyId());
    if (!summary || !policy) return null;
    const windowSeconds = durationToSeconds(policy.window);
    if (windowSeconds === null) return null;
    if (summary.elapsedMs >= windowSeconds * 1000) {
      return `Ran ${summary.elapsedMs} ms across a ${windowSeconds}s window edge, so the totals span two windows.`;
    }
    return `Ran ${summary.elapsedMs} ms inside one ${windowSeconds}s window.`;
  }

  private validateForm(): string[] {
    const problems: string[] = [];
    if (this.mode() === 'create' && !/^[a-z0-9][a-z0-9-]{0,62}$/.test(this.fId().trim())) {
      problems.push('Id must match [a-z0-9][a-z0-9-]{0,62}.');
    }
    if (this.fScope() !== 'GLOBAL' && !this.fPath().trim().startsWith('/')) {
      problems.push('Path must start with / (only a GLOBAL policy omits it).');
    }
    const positiveInt = (value: number, label: string) => {
      if (!Number.isInteger(value) || value < 1) problems.push(`${label} must be a whole number of at least 1.`);
    };
    switch (this.fAlgorithm()) {
      case 'TOKEN_BUCKET':
        positiveInt(this.fCapacity(), 'Capacity');
        positiveInt(this.fRefillSeconds(), 'Refill interval');
        positiveInt(this.fCost(), 'Cost');
        break;
      case 'LEAKY_BUCKET':
        positiveInt(this.fDrainRate(), 'Drain rate');
        positiveInt(this.fQueueCapacity(), 'Queue capacity');
        break;
      case 'CONCURRENCY_LIMIT':
        positiveInt(this.fMaxConcurrent(), 'Max concurrent');
        positiveInt(this.fLeaseSeconds(), 'Lease duration');
        break;
      default:
        positiveInt(this.fLimit(), 'Limit');
        positiveInt(this.fWindowSeconds(), 'Window');
        break;
    }
    if (!Number.isInteger(this.fWindowSeconds()) || this.fWindowSeconds() < 1) {
      problems.push('Window must be at least 1 second.');
    }
    const algo = this.capabilities()?.algorithms.find((a) => a.name === this.fAlgorithm());
    if (algo && !algo.implemented) {
      problems.push(`${this.fAlgorithm()} is not enforced by this build and cannot be saved as working.`);
    }
    return problems;
  }

  readonly selectedAlgoNote = computed(() => {
    const algo = this.capabilities()?.algorithms.find((a) => a.name === this.fAlgorithm());
    return algo?.note ?? null;
  });

  async onCreateKey(): Promise<void> {
    this.keysError.set(null);
    this.newRawKey.set(null);
    if (!this.keyOwner().trim()) {
      this.keysError.set('Owner is required.');
      return;
    }
    this.busy.set(true);
    try {
      const result = await firstValueFrom(this.api.createKey(this.keyOwner().trim(), this.keyTier().trim() || 'standard'));
      if (isAdminError(result)) {
        this.keysError.set(result.message);
        return;
      }
      this.newRawKey.set(result.key);
      this.keyOwner.set('');
      await this.reloadAll();
    } finally {
      this.busy.set(false);
    }
  }

  async onRevokeKey(keyId: string): Promise<void> {
    if (this.confirmRevokeId() !== keyId) {
      this.confirmRevokeId.set(keyId);
      return;
    }
    this.confirmRevokeId.set(null);
    this.keysError.set(null);
    this.busy.set(true);
    try {
      const result = await firstValueFrom(this.api.revokeKey(keyId));
      if (isAdminError(result)) {
        this.keysError.set(result.message);
        return;
      }
      await this.reloadAll();
    } finally {
      this.busy.set(false);
    }
  }

  private describeLoginFailure(status: number, message: string): string {
    if (status === 0) return 'Backend unreachable. Is the API running?';
    if (status === 401) return 'Wrong administrator username or password.';
    if (status === 403) return 'That account is not an administrator.';
    return message;
  }

  private describeSaveFailure(status: number, code: string, message: string, problems: string[]): string {
    if (status === 409 || code === 'version_conflict') {
      return 'Someone else changed this policy first. Reload it and re-apply your edit.';
    }
    if (status === 404) return 'That policy no longer exists. Reload the list.';
    if (status === 0) return 'Backend unreachable. Nothing was saved.';
    if (problems.length > 0) return problems.join(' ');
    return message;
  }
}
