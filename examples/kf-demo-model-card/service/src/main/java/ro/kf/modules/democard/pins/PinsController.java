package ro.kf.modules.democard.pins;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.kf.modules.democard.ModuleIdentity;
import ro.kf.modules.democard.models.ModelCards;
import ro.kf.modules.democard.models.ModelReads;
import ro.kf.modules.democard.models.ModelsController;
import ro.kf.modules.democard.web.RouteRights;
import ro.kf.sdk.RequestContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The pinned cards and the pins (routes {@code audience: member} of the manifest) and the tenant administrator's reset
 * ({@code admin: tenant}).
 *
 * <p>The model's data comes ONLY from the platform's reads ({@link ModelReads}: {@code model.v1} and the garment image of
 * {@code model-version.garment-image.v1}), with the context of the request: the platform answers with the visibility
 * and the right ({@code api.v1.models.get}) of the caller in the caller's tenant. A model the caller cannot see is a
 * {@code 404}; a caller without the right gets the platform's {@code 403}. The pins are the module's own data, in its
 * database, scoped to the context's tenant by {@link TenantScope} and to the context's user by the queries.
 */
@RestController
public class PinsController {

    private final ModuleIdentity id;
    private final ModelReads reads;
    private final TenantScope scope;
    private final PinStore pins;

    public PinsController(ModuleIdentity id, ModelReads reads, TenantScope scope, PinStore pins) {
        this.id = id;
        this.reads = reads;
        this.scope = scope;
        this.pins = pins;
    }

    /**
     * {@code GET /cards}: the caller's pinned models, the newest pin first, each with the image of its current version;
     * {@code missing} = pins whose model is no longer visible.
     */
    @GetMapping("/cards")
    public Map<String, Object> cards(RequestContext ctx) {
        String user = RouteRights.requireMember(ctx);
        List<PinStore.Pin> mine = scope.inTenant(ctx.tenant(), () -> pins.ofUser(user));
        Map<Long, Map<String, Object>> visible = reads.models(ctx, mine.stream().map(PinStore.Pin::modelId).toList());
        List<Long> versionIds = visible.values().stream().map(ModelCards::currentVersion).filter(Objects::nonNull).toList();
        Map<Long, ModelReads.Image> images = versionIds.isEmpty() ? Map.of() : reads.images(ctx, versionIds);
        List<Map<String, Object>> items = new ArrayList<>();
        List<Long> missing = new ArrayList<>();
        for (PinStore.Pin pin : mine) {
            Map<String, Object> object = visible.get(pin.modelId());
            if (object == null) {
                missing.add(pin.modelId());
                continue;
            }
            Long version = ModelCards.currentVersion(object);
            items.add(ModelCards.card(object, version == null ? null : images.get(version), true,
                    pin.pinnedAt().toString()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("missing", missing);
        return body;
    }

    /** {@code PUT /pins/{modelId}}: pins a model the caller can see (read first: no pin on an invisible model). */
    @PutMapping("/pins/{modelId}")
    public Map<String, Object> pin(RequestContext ctx, @PathVariable String modelId) {
        String user = RouteRights.requireMember(ctx);
        long model = ModelsController.modelId(modelId);
        Map<String, Object> object = reads.model(ctx, model);
        String name = object.get("name") == null ? null : String.valueOf(object.get("name"));
        scope.inTenant(ctx.tenant(), () -> {
            pins.pin(TenantScope.tenant(ctx.tenant()), model, user, name);
            return null;
        });
        return Map.of("modelId", model, "pinned", true);
    }

    /** {@code DELETE /pins/{modelId}}: idempotent. */
    @DeleteMapping("/pins/{modelId}")
    public Map<String, Object> unpin(RequestContext ctx, @PathVariable String modelId) {
        String user = RouteRights.requireMember(ctx);
        long model = ModelsController.modelId(modelId);
        scope.inTenant(ctx.tenant(), () -> pins.unpin(model, user));
        return Map.of("modelId", model, "pinned", false);
    }

    /** {@code DELETE /admin/pins} ({@code admin: tenant}): every pin of the tenant, every user. */
    @DeleteMapping("/admin/pins")
    public Map<String, Object> resetTenant(RequestContext ctx) {
        RouteRights.require(ctx, id, RouteRights.ADMIN_PINS_DELETE);
        int deleted = scope.inTenant(ctx.tenant(), pins::deleteAll);
        return Map.of("deleted", deleted);
    }
}
