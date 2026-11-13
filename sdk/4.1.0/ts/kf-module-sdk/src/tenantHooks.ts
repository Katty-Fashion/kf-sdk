// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/TenantHooks.java. Do not edit by hand: fix Java, then re-derive.
import {
  HookEvents,
  type TenantDeletedHandler,
  type TenantRestoredHandler,
  type TenantSoftDeletedHandler,
} from './hookEvents.js';
import type { HookRegistration } from './modelHooks.js';

/**
 * Hooks on the platform's tenants (protocol 1.2 §6.1; the
 * soft-delete and the restore: protocol 1.3). Obtained from `KfModule.tenants()`.
 *
 * ```ts
 * kf.tenants().onDeleted(async (deleted) => repository.deleteAllOf(deleted.tenantId));   // idempotent
 * kf.tenants().onSoftDelete((e) => jobs.pause(e.tenantId)).onRestore((e) => jobs.resume(e.tenantId));   // 1.3
 * ```
 *
 * `onDeleted` subscribes the module to `HookEvents.TENANT_AFTER_DELETE`: the registration sends
 * `hooks[{event: "tenant.after.delete"}]` (the manifest also declares `capabilities.hooks[].point`). The platform
 * delivers it after the tenant's deletion commits, **at least once**, to every installed module subscribed. The handler
 * is called only after the signature and the envelope are verified; an envelope whose `data.entity` is not `tenant`
 * or without `data.id` is refused `400` `permanent`. The SDK does not deduplicate: a second delivery of the same
 * `kfeventid` calls the handler again, so the handler deletes idempotently. The platform purges the module's files of
 * the tenant itself; the rows are the module's.
 *
 * `onSoftDelete` and `onRestore` (protocol 1.3) subscribe to `HookEvents.TENANT_AFTER_SOFT_DELETE` and
 * `HookEvents.TENANT_AFTER_RESTORE`, delivered the same way but only to the modules LIVE for the tenant. On a
 * soft-delete the module stops its work for the tenant and deletes nothing (access is off and recoverable); on a
 * restore it resumes. There is no `tenant.before.delete`: the soft-delete is the "before" of the purge.
 */
export class TenantHooks {
  /** @internal */
  readonly deleted: TenantDeletedHandler[] = [];
  /** @internal */
  readonly softDeleted: TenantSoftDeletedHandler[] = [];
  /** @internal */
  readonly restored: TenantRestoredHandler[] = [];

  /** @internal */
  constructor() {}

  /** Called once per delivery of `tenant.after.delete`; several handlers run in declaration order. */
  onDeleted(handler: TenantDeletedHandler): this {
    if (handler == null) throw new TypeError('handler');
    this.deleted.push(handler);
    return this;
  }

  /** Called once per delivery of `tenant.after.soft-delete` (protocol 1.3); in declaration order. */
  onSoftDelete(handler: TenantSoftDeletedHandler): this {
    if (handler == null) throw new TypeError('handler');
    this.softDeleted.push(handler);
    return this;
  }

  /** Called once per delivery of `tenant.after.restore` (protocol 1.3); in declaration order. */
  onRestore(handler: TenantRestoredHandler): this {
    if (handler == null) throw new TypeError('handler');
    this.restored.push(handler);
    return this;
  }

  /**
   * @internal The `hooks` part of the registration body this class adds (an after point carries only its event): each
   * point with a handler, in the order delete, soft-delete, restore.
   */
  registration(): HookRegistration[] {
    const hooks: HookRegistration[] = [];
    if (this.deleted.length > 0) hooks.push({ event: HookEvents.TENANT_AFTER_DELETE });
    if (this.softDeleted.length > 0) hooks.push({ event: HookEvents.TENANT_AFTER_SOFT_DELETE });
    if (this.restored.length > 0) hooks.push({ event: HookEvents.TENANT_AFTER_RESTORE });
    return hooks;
  }
}
