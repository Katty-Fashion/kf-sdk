package ro.kf.sdk;

import ro.kf.sdk.HookEvents.TenantDeletedHandler;
import ro.kf.sdk.HookEvents.TenantRestoredHandler;
import ro.kf.sdk.HookEvents.TenantSoftDeletedHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hooks on the platform's tenants (protocol 1.2 §6.1; the
 * soft-delete and the restore: protocol 1.3). Obtained from
 * {@link KfModule#tenants()}.
 *
 * <pre>{@code
 * kf.tenants().onDeleted(deleted -> repository.deleteAllOf(deleted.tenantId()));   // idempotent
 * kf.tenants().onSoftDelete(e -> jobs.pause(e.tenantId())).onRestore(e -> jobs.resume(e.tenantId()));   // 1.3
 * }</pre>
 *
 * <p>{@link #onDeleted} subscribes the module to {@link HookEvents#TENANT_AFTER_DELETE}: the registration sends
 * {@code hooks[{event: "tenant.after.delete"}]} (the manifest also declares {@code capabilities.hooks[].point}). The
 * platform delivers it after the tenant's deletion commits, <strong>at least once</strong>, to every installed module
 * subscribed. The handler is called only after the signature and the envelope are verified; an envelope whose
 * {@code data.entity} is not {@code tenant} or without {@code data.id} is refused {@code 400} {@code permanent}. The
 * SDK does not deduplicate: a second delivery of the same {@code kfeventid} calls the handler again, so the handler
 * deletes idempotently. The platform purges the module's files of the tenant itself; the rows are the module's.
 *
 * <p>{@link #onSoftDelete} and {@link #onRestore} (protocol 1.3) subscribe to {@link HookEvents#TENANT_AFTER_SOFT_DELETE}
 * and {@link HookEvents#TENANT_AFTER_RESTORE}, delivered the same way but only to the modules LIVE for the tenant. On a
 * soft-delete the module stops its work for the tenant and deletes nothing (access is off and recoverable); on a
 * restore it resumes. There is no {@code tenant.before.delete}: the soft-delete is the "before" of the purge.
 */
public final class TenantHooks {

    final List<TenantDeletedHandler> deleted = new ArrayList<>();
    final List<TenantSoftDeletedHandler> softDeleted = new ArrayList<>();
    final List<TenantRestoredHandler> restored = new ArrayList<>();

    TenantHooks() {
    }

    /** Called once per delivery of {@code tenant.after.delete}; several handlers run in declaration order. */
    public TenantHooks onDeleted(TenantDeletedHandler handler) {
        deleted.add(Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** Called once per delivery of {@code tenant.after.soft-delete} (protocol 1.3); in declaration order. */
    public TenantHooks onSoftDelete(TenantSoftDeletedHandler handler) {
        softDeleted.add(Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** Called once per delivery of {@code tenant.after.restore} (protocol 1.3); in declaration order. */
    public TenantHooks onRestore(TenantRestoredHandler handler) {
        restored.add(Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /**
     * The {@code hooks} part of the registration body this class adds (an after point carries only its event): each
     * point with a handler, in the order delete, soft-delete, restore.
     */
    List<Map<String, Object>> registration() {
        List<Map<String, Object>> hooks = new ArrayList<>();
        if (!deleted.isEmpty()) hooks.add(subscription(HookEvents.TENANT_AFTER_DELETE));
        if (!softDeleted.isEmpty()) hooks.add(subscription(HookEvents.TENANT_AFTER_SOFT_DELETE));
        if (!restored.isEmpty()) hooks.add(subscription(HookEvents.TENANT_AFTER_RESTORE));
        return List.copyOf(hooks);
    }

    private static Map<String, Object> subscription(String event) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("event", event);
        return h;
    }
}
