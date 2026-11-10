package ro.kf.modules.democard.hooks;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import ro.kf.modules.democard.pins.PinStore;
import ro.kf.modules.democard.pins.TenantScope;
import ro.kf.sdk.HookEvents;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.NotificationOptions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code model.after.delete} (an after hook: asynchronous, at least once, after the commit). The SDK has verified the
 * signature and the envelope before this runs. The module deletes the pins of the model in the tenant of the envelope
 * ({@code kftenant}), then tells each user who had pinned it, with the declared notification
 * {@code pinned-model-deleted}. Idempotent: a second delivery finds no pin and sends nothing; the idempotency key of the
 * notification is derived from the event id ({@code kfeventid}), so a retry never notifies twice.
 */
@Component
public class ModelDeletedHandler implements HookEvents.AfterHandler {

    static final String NOTIFICATION = "pinned-model-deleted";

    private final TenantScope scope;
    private final PinStore pins;
    /** The module, resolved at the first event: it is built with this handler (KfModule is final, no lazy proxy). */
    private final ObjectProvider<KfModule> module;

    public ModelDeletedHandler(TenantScope scope, PinStore pins, ObjectProvider<KfModule> module) {
        this.scope = scope;
        this.pins = pins;
        this.module = module;
    }

    @Override
    public void handle(HookEvents.AfterEvent event) {
        String tenant = event.envelope().tenant();
        long model;
        try {
            model = Long.parseLong(event.id());
        } catch (NumberFormatException | NullPointerException notAModel) {
            return; // nothing a retry can fix: acknowledged
        }
        List<PinStore.Pin> removed = scope.inTenant(tenant, () -> pins.removeModel(model));
        if (removed.isEmpty()) return;
        String name = event.before() != null && event.before().name() != null ? event.before().name()
                : removed.getFirst().pinnedName() != null ? removed.getFirst().pinnedName() : "#" + model;
        List<String> recipients = removed.stream().map(PinStore.Pin::pinnedBy).distinct().toList();
        KfModule kf = module.getObject();
        UUID key = UUID.nameUUIDFromBytes((NOTIFICATION + ":" + event.envelope().eventId()).getBytes());
        kf.notifications().send(TenantScope.tenant(tenant), NOTIFICATION, recipients, Map.of("modelName", name),
                NotificationOptions.builder().deepLink("/m/" + kf.slug() + "/cards").idempotencyKey(key).build());
    }
}
