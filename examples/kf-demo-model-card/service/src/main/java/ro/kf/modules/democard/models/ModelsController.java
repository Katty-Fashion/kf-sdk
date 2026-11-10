package ro.kf.modules.democard.models;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.kf.modules.democard.pins.PinStore;
import ro.kf.modules.democard.pins.TenantScope;
import ro.kf.modules.democard.web.Problem;
import ro.kf.modules.democard.web.RouteRights;
import ro.kf.sdk.ReadPage;
import ro.kf.sdk.RequestContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The models (routes {@code audience: member}): the paged list of the tenant's models on the module page, and the card
 * of one model in its Overview. The data comes only from the platform's reads ({@link ModelReads}); the pins are the
 * module's own ({@link PinStore}, in the context's tenant).
 */
@RestController
public class ModelsController {

    /** The page size the module serves: 12 by default, at most 48 (the platform's port takes up to 200). */
    static final int DEFAULT_SIZE = 12;
    static final int MAX_SIZE = 48;

    private final ModelReads reads;
    private final TenantScope scope;
    private final PinStore pins;

    public ModelsController(ModelReads reads, TenantScope scope, PinStore pins) {
        this.reads = reads;
        this.scope = scope;
        this.pins = pins;
    }

    /**
     * {@code GET /models?page=&size=}: a page of the tenant's models by id ({@code model.v1} without {@code ids},
     * protocol 1.3.1), each with {@code modelOwner}, the image of its current version (one read of
     * {@code model-version.garment-image.v1} for the whole page) and whether the caller pinned it. A member without
     * {@code api.v1.models.get} gets the platform's 403.
     */
    @GetMapping("/models")
    public Map<String, Object> list(RequestContext ctx, @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        String user = RouteRights.requireMember(ctx);
        if (page < 0) throw Problem.invalid("page starts at 0", Map.of("page", "must be 0 or more"));
        if (size < 1 || size > MAX_SIZE) {
            throw Problem.invalid("size is 1 to " + MAX_SIZE, Map.of("size", "must be between 1 and " + MAX_SIZE));
        }
        ReadPage models = reads.page(ctx, page, size);
        List<Long> versionIds = models.items().stream().map(ModelCards::currentVersion).filter(Objects::nonNull).toList();
        Map<Long, ModelReads.Image> images = versionIds.isEmpty() ? Map.of() : reads.images(ctx, versionIds);
        Set<Long> pinned = new HashSet<>();
        scope.inTenant(ctx.tenant(), () -> pins.ofUser(user)).forEach(p -> pinned.add(p.modelId()));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> object : models.items()) {
            Long version = ModelCards.currentVersion(object);
            items.add(ModelCards.card(object, version == null ? null : images.get(version),
                    pinned.contains(ModelReads.id(object)), null));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", models.page());
        body.put("size", models.size());
        body.put("total", models.total());
        return body;
    }

    /**
     * {@code GET /models/{modelId}[?versionId=]}: the card of one model, with the image of the version the page has
     * selected ({@code versionId}, when it is one of the model's versions; otherwise the current one) and whether the
     * caller pinned it.
     */
    @GetMapping("/models/{modelId}")
    public Map<String, Object> card(RequestContext ctx, @PathVariable String modelId,
                                    @RequestParam(required = false) Long versionId) {
        String user = RouteRights.requireMember(ctx);
        long model = modelId(modelId);
        Map<String, Object> object = reads.model(ctx, model);
        Long version = versionId != null && ModelCards.hasVersion(object, versionId) ? versionId
                : ModelCards.currentVersion(object);
        ModelReads.Image image = version == null ? null : reads.image(ctx, version).orElse(null);
        boolean pinned = scope.inTenant(ctx.tenant(), () -> pins.isPinned(model, user));
        return ModelCards.card(object, image, pinned, null);
    }

    /** A positive model id, else 400. */
    public static long modelId(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
            // the message below
        }
        throw Problem.invalid("the model id is a positive number", Map.of("modelId", "must be a positive number"));
    }
}
