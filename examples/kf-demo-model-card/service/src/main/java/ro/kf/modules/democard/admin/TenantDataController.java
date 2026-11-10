package ro.kf.modules.democard.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.kf.modules.democard.ModuleIdentity;
import ro.kf.modules.democard.pins.PinStore;
import ro.kf.modules.democard.pins.TenantScope;
import ro.kf.modules.democard.web.Problem;
import ro.kf.modules.democard.web.RouteRights;
import ro.kf.sdk.ErrorClass;
import ro.kf.sdk.RequestContext;
import ro.kf.sdk.TenantDataExport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The tenant data of the module (protocol 1.3 §15.8): {@code GET /admin/export} and {@code POST /admin/import}, both
 * {@code admin: platform}.
 *
 * <p>The export is what the platform asks, with its own context ({@code system} via {@code platform}, the tenant
 * exported in {@code tenant}), at the module's uninstall and before a tenant's hard delete. The platform administrator
 * may ask it too, for the active tenant. The bundle {@value #SCHEMA} carries the tenant's pins; its
 * {@code contentSha256} is the SDK's ({@link TenantDataExport#bundle}). The pins are few: one part.
 *
 * <p>The import takes a bundle back, unchanged: {@code mode=dry-run} counts, {@code mode=apply} writes what is missing
 * (a second apply writes 0). A bundle of another tenant ({@code 422 tenant-mismatch}), of another schema
 * ({@code 422 bundle-schema}) or altered ({@code 422 bundle-altered}) is refused before anything is written.
 */
@RestController
public class TenantDataController {

    /** The format of the bundles: the manifest's {@code tenantData.format}, a contract of the code (not of the slug). */
    public static final String SCHEMA = ModuleIdentity.SOURCE_SLUG + "/tenant-data/v1";

    private final ModuleIdentity id;
    private final TenantScope scope;
    private final PinStore pins;

    public TenantDataController(ModuleIdentity id, TenantScope scope, PinStore pins) {
        this.id = id;
        this.scope = scope;
        this.pins = pins;
    }

    @GetMapping("/admin/export")
    public Map<String, Object> export(RequestContext ctx, @RequestParam(value = "tenantId", required = false) String tenantId,
                                      @RequestParam(value = "part", defaultValue = "1") int part) {
        if (ctx == null || !RequestContext.Actor.PLATFORM.equals(ctx.actor())) {
            RouteRights.require(ctx, id, RouteRights.ADMIN_EXPORT_GET);
        }
        sameTenant(ctx, tenantId);
        if (part != 1) throw Problem.invalid("no such part", Map.of("part", "must be 1"));
        List<PinStore.Pin> all = scope.inTenant(ctx.tenant(), pins::all);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PinStore.Pin p : all) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("modelId", p.modelId());
            row.put("pinnedBy", p.pinnedBy());
            row.put("pinnedName", p.pinnedName());
            row.put("pinnedAt", p.pinnedAt().toString());
            rows.add(row);
        }
        Map<String, Object> counts = Map.of("pins", rows.size(),
                "users", all.stream().map(PinStore.Pin::pinnedBy).distinct().count());
        return TenantDataExport.bundle(SCHEMA, TenantScope.tenant(ctx.tenant()), 1, 1, counts, Map.of("pins", rows));
    }

    @PostMapping("/admin/import")
    public Map<String, Object> importBundle(RequestContext ctx, @RequestParam("mode") String mode,
                                            @RequestBody Map<String, Object> bundle) {
        RouteRights.require(ctx, id, RouteRights.ADMIN_IMPORT_POST);
        boolean apply = switch (mode) {
            case "apply" -> true;
            case "dry-run" -> false;
            default -> throw Problem.invalid("mode is dry-run or apply", Map.of("mode", "dry-run or apply"));
        };
        if (!SCHEMA.equals(bundle.get("schema"))) {
            throw new Problem(422, "bundle-schema", "Unknown bundle", "the bundle is not " + SCHEMA, ErrorClass.PERMANENT,
                    Map.of("schema", "must be " + SCHEMA));
        }
        sameTenant(ctx, String.valueOf(bundle.get("tenantId")));
        if (!TenantDataExport.contentSha256(bundle).equals(bundle.get("contentSha256"))) {
            throw new Problem(422, "bundle-altered", "Bundle altered", "contentSha256 does not match the bundle",
                    ErrorClass.PERMANENT, Map.of("contentSha256", "does not match"));
        }
        List<PinStore.Pin> incoming = new ArrayList<>();
        Object rows = bundle.get("pins");
        if (!(rows instanceof List<?> list)) throw Problem.invalid("pins is a list", Map.of("pins", "must be a list"));
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row) || !(row.get("modelId") instanceof Number model)
                    || !(row.get("pinnedBy") instanceof String by) || by.isBlank()) {
                throw Problem.invalid("a pin has modelId and pinnedBy", Map.of("pins", "modelId and pinnedBy required"));
            }
            Object at = row.get("pinnedAt");
            incoming.add(new PinStore.Pin(model.longValue(), by, row.get("pinnedName") == null ? null
                    : String.valueOf(row.get("pinnedName")), at == null ? Instant.EPOCH : Instant.parse(String.valueOf(at))));
        }
        UUID tenant = TenantScope.tenant(ctx.tenant());
        int written = scope.inTenant(ctx.tenant(), () -> {
            int n = 0;
            for (PinStore.Pin p : incoming) {
                if (!apply) continue;
                if (pins.importPin(tenant, p)) n++;
            }
            return n;
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode);
        body.put("counts", Map.of("pins", incoming.size()));
        body.put("written", written);
        return body;
    }

    private static void sameTenant(RequestContext ctx, String tenantId) {
        if (tenantId != null && !tenantId.isBlank() && !"null".equals(tenantId)
                && !tenantId.trim().equalsIgnoreCase(ctx.tenant())) {
            throw new Problem(422, "tenant-mismatch", "Tenant mismatch", "the bundle is of the context's tenant only",
                    ErrorClass.PERMANENT, Map.of("tenantId", "must be the context's tenant"));
        }
    }
}
