<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/TenantHooks.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * Hooks on the platform's tenants (protocol 1.2 §6.1; the
 * soft-delete and the restore: protocol 1.3). Obtained from `KfModule::tenants()`.
 *
 * ```php
 * $kf->tenants()->onDeleted(fn (HookEvents\TenantDeleted $d) => $repository->deleteAllOf($d->tenantId));   // idempotent
 * $kf->tenants()->onSoftDelete(fn ($e) => $jobs->pause($e->tenantId))->onRestore(fn ($e) => $jobs->resume($e->tenantId));   // 1.3
 * ```
 *
 * `onDeleted` subscribes the module to `HookEvents::TENANT_AFTER_DELETE`: the registration sends
 * `hooks[{event: "tenant.after.delete"}]` (the manifest also declares `capabilities.hooks[].point`). The platform
 * delivers it after the tenant's deletion commits, **at least once**, to every installed module subscribed. The handler
 * is called only after the signature and the envelope are verified; an envelope whose `data.entity` is not `tenant`
 * or without `data.id` is refused `400` `permanent`. The SDK does not deduplicate: a second delivery of the same
 * `kfeventid` calls the handler again, so the handler deletes idempotently. The platform purges the module's files of
 * the tenant itself; the rows are the module's.
 *
 * `onSoftDelete` and `onRestore` (protocol 1.3) subscribe to `HookEvents::TENANT_AFTER_SOFT_DELETE` and
 * `HookEvents::TENANT_AFTER_RESTORE`, delivered the same way but only to the modules LIVE for the tenant. On a
 * soft-delete the module stops its work for the tenant and deletes nothing (access is off and recoverable); on a
 * restore it resumes. There is no `tenant.before.delete`: the soft-delete is the "before" of the purge.
 */
final class TenantHooks
{
    /** @internal @var list<callable> */
    public array $deleted = [];
    /** @internal @var list<callable> */
    public array $softDeleted = [];
    /** @internal @var list<callable> */
    public array $restored = [];

    /** @internal */
    public function __construct()
    {
    }

    /**
     * Called once per delivery of `tenant.after.delete`; several handlers run in declaration order.
     *
     * @param callable(HookEvents\TenantDeleted): void $handler
     */
    public function onDeleted(callable $handler): self
    {
        $this->deleted[] = $handler;
        return $this;
    }

    /**
     * Called once per delivery of `tenant.after.soft-delete` (protocol 1.3); in declaration order.
     *
     * @param callable(HookEvents\TenantSoftDeleted): void $handler
     */
    public function onSoftDelete(callable $handler): self
    {
        $this->softDeleted[] = $handler;
        return $this;
    }

    /**
     * Called once per delivery of `tenant.after.restore` (protocol 1.3); in declaration order.
     *
     * @param callable(HookEvents\TenantRestored): void $handler
     */
    public function onRestore(callable $handler): self
    {
        $this->restored[] = $handler;
        return $this;
    }

    /**
     * @internal The `hooks` part of the registration body this class adds (an after point carries only its event):
     * each point with a handler, in the order delete, soft-delete, restore.
     *
     * @return list<array<string, mixed>>
     */
    public function registration(): array
    {
        $hooks = [];
        if ($this->deleted !== []) $hooks[] = ['event' => HookEvents::TENANT_AFTER_DELETE];
        if ($this->softDeleted !== []) $hooks[] = ['event' => HookEvents::TENANT_AFTER_SOFT_DELETE];
        if ($this->restored !== []) $hooks[] = ['event' => HookEvents::TENANT_AFTER_RESTORE];
        return $hooks;
    }
}
