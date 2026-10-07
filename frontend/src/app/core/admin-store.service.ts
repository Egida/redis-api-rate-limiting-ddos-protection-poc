import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { AdminApiService, isAdminError } from './admin-api.service';
import {
  AdminApiError,
  AdminPolicy,
  AuditRecord,
  Capabilities,
  PolicyEdit,
} from './admin-models';

/** Audit history is always loaded in bounded chunks; the backend has no offset parameter. */
export const AUDIT_LIMITS = [25, 50, 100, 200] as const;

/**
 * Shared administration state for the Policies and Audit workspaces.
 *
 * Admin credentials live only in AdminApiService memory and are never stored here.
 */
@Injectable({ providedIn: 'root' })
export class AdminStore {
  private readonly api = inject(AdminApiService);
  private readonly router = inject(Router);

  readonly busy = signal(false);
  readonly loaded = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly actionError = signal<string | null>(null);
  readonly actionInfo = signal<string | null>(null);

  readonly capabilities = signal<Capabilities | null>(null);
  readonly policies = signal<AdminPolicy[]>([]);
  readonly audit = signal<AuditRecord[]>([]);
  readonly auditLimit = signal<(typeof AUDIT_LIMITS)[number]>(50);

  readonly implementedAlgorithms = computed(
    () => this.capabilities()?.algorithms.filter((a) => a.implemented) ?? [],
  );
  readonly unimplementedAlgorithms = computed(
    () => this.capabilities()?.algorithms.filter((a) => !a.implemented) ?? [],
  );
  readonly implementedScopes = computed(
    () => this.capabilities()?.scopes.filter((s) => s.implemented) ?? [],
  );
  readonly enabledPolicyCount = computed(() => this.policies().filter((p) => p.enabled).length);

  readonly loggedIn = this.api.loggedIn;
  readonly loginName = this.api.loginName;

  async login(username: string, password: string): Promise<AdminApiError | null> {
    const result = await firstValueFrom(this.api.login(username, password));
    return result.ok ? null : result.error;
  }

  /** Clears cached management state on logout so a later login never renders the previous session. */
  logout(): void {
    this.api.logout();
    this.reset();
    void this.router.navigate(['/admin/login']);
  }

  reset(): void {
    this.capabilities.set(null);
    this.policies.set([]);
    this.audit.set([]);
    this.auditLimit.set(50);
    this.loaded.set(false);
    this.loadError.set(null);
    this.actionError.set(null);
    this.actionInfo.set(null);
  }

  clearMessages(): void {
    this.actionError.set(null);
    this.actionInfo.set(null);
  }

  /** Capabilities and policies in one round trip. Audit is loaded on its own page. */
  async load(): Promise<void> {
    if (this.loaded()) return;
    this.loadError.set(null);
    const [caps, policies] = await Promise.all([
      firstValueFrom(this.api.capabilities()),
      firstValueFrom(this.api.list()),
    ]);
    const failed = [caps, policies].find(isAdminError);
    if (failed && isAdminError(failed)) {
      this.loadError.set(this.describeLoadFailure(failed));
      return;
    }
    if (!isAdminError(caps)) this.capabilities.set(caps);
    if (!isAdminError(policies)) this.policies.set(policies);
    this.loaded.set(true);
  }

  /** Fetches a bounded audit window. Larger limits replace the window rather than appending. */
  async loadAudit(limit: (typeof AUDIT_LIMITS)[number]): Promise<void> {
    this.loadError.set(null);
    this.auditLimit.set(limit);
    const result = await firstValueFrom(this.api.audit(limit));
    if (isAdminError(result)) {
      this.loadError.set(this.describeLoadFailure(result));
      return;
    }
    this.audit.set(result);
  }

  async save(edit: PolicyEdit, editingId: string | null): Promise<AdminPolicy | AdminApiError> {
    this.clearMessages();
    const saved =
      editingId === null
        ? await firstValueFrom(this.api.create(edit))
        : await firstValueFrom(this.api.update(editingId, edit));
    if (isAdminError(saved)) {
      this.actionError.set(this.describeSaveFailure(saved));
      return saved;
    }
    await this.refreshPolicies();
    this.actionInfo.set(
      `Saved ${saved.id} at version ${saved.version}. Every instance reads it from shared Redis, so no restart is needed.`,
    );
    return saved;
  }

  async toggleEnabled(policy: AdminPolicy): Promise<void> {
    this.clearMessages();
    const result = await firstValueFrom(this.api.setEnabled(policy.id, !policy.enabled, policy.version));
    if (isAdminError(result)) {
      this.actionError.set(this.describeSaveFailure(result));
      return;
    }
    await this.refreshPolicies();
    this.actionInfo.set(`${policy.id} is now ${result.enabled ? 'enabled' : 'disabled'}.`);
  }

  async remove(id: string): Promise<void> {
    this.clearMessages();
    const result = await firstValueFrom(this.api.remove(id));
    if (isAdminError(result)) {
      this.actionError.set(this.describeSaveFailure(result));
      return;
    }
    await this.refreshPolicies();
    this.actionInfo.set(`Deleted ${id}.`);
  }

  private async refreshPolicies(): Promise<void> {
    const list = await firstValueFrom(this.api.list());
    if (!isAdminError(list)) this.policies.set(list);
  }

  private describeLoadFailure(error: AdminApiError): string {
    if (error.status === 0) return 'Backend unreachable. Is the API running on the configured port?';
    if (error.status === 401) return 'Session expired. Sign in again.';
    if (error.status === 403) return 'That account is not an administrator.';
    return error.message;
  }

  private describeSaveFailure(error: AdminApiError): string {
    if (error.status === 409 || error.code === 'version_conflict') {
      return 'Someone else changed this policy first. Close the editor, reload, and re-apply your change.';
    }
    if (error.status === 404) return 'That policy no longer exists. Reload the list.';
    if (error.status === 0) return 'Backend unreachable. Nothing was saved.';
    if (error.problems.length > 0) return error.problems.join(' ');
    return error.message;
  }
}
