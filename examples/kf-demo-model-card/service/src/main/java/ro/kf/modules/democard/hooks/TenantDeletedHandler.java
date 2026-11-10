package ro.kf.modules.democard.hooks;

import org.springframework.stereotype.Component;
import ro.kf.modules.democard.pins.PinStore;
import ro.kf.modules.democard.pins.TenantScope;
import ro.kf.sdk.HookEvents;

import java.util.UUID;

/**
 * {@code tenant.after.delete}: the platform delivers it after the tenant's hard delete, signed, at least once, to every
 * installed module subscribed (liveness is not evaluated: the tenant no longer exists). The module deletes every pin of
 * that tenant, idempotently, in a transaction scoped to it (never another tenant's row).
 */
@Component
public class TenantDeletedHandler implements HookEvents.TenantDeletedHandler {

    private static final System.Logger LOG = System.getLogger(TenantDeletedHandler.class.getName());

    private final TenantScope scope;
    private final PinStore pins;

    public TenantDeletedHandler(TenantScope scope, PinStore pins) {
        this.scope = scope;
        this.pins = pins;
    }

    @Override
    public void handle(HookEvents.TenantDeleted deleted) {
        String tenant = deleted.tenantId();
        try {
            UUID.fromString(tenant);
        } catch (IllegalArgumentException | NullPointerException notAUuid) {
            LOG.log(System.Logger.Level.WARNING, "tenant.after.delete with a tenant id that is not a UUID; nothing deleted");
            return;
        }
        int rows = scope.inTenant(tenant, pins::deleteAll);
        LOG.log(System.Logger.Level.INFO, "tenant.after.delete: {0} pin(s) of tenant {1} deleted", rows, tenant);
    }
}
