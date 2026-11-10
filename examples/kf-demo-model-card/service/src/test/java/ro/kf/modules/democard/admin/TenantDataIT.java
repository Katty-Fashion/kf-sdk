package ro.kf.modules.democard.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.modules.democard.testsupport.ModuleCalls;
import ro.kf.modules.democard.testsupport.ModuleDb;
import ro.kf.modules.democard.testsupport.ModuleIT;
import ro.kf.sdk.TenantDataExport;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static ro.kf.modules.democard.testsupport.ModuleCalls.json;

/** GET /admin/export (the platform's call) and POST /admin/import (the bundle back, unchanged). */
class TenantDataIT extends ModuleIT {

    static final List<String> IMPORT = List.of("api.v1.m.kf-demo-model-card.admin.import.post");

    @Test
    @DisplayName("the platform's export: the bundle kf-demo-model-card/tenant-data/v1 of the context's tenant, its hash the SDK's")
    void export() throws Exception {
        String t = tenant();
        model(t, 1, "A");
        model(t, 2, "B");
        calls().user(t, "sub-ana", List.of(), "PUT", "/pins/1", null);
        calls().user(t, "sub-ion", List.of(), "PUT", "/pins/2", null);
        var res = calls().platform(t, "GET", "/admin/export?tenantId=" + t + "&part=1");
        assertEquals(200, res.statusCode(), res.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> bundle = ModuleCalls.JSON.readValue(res.body(), Map.class);
        assertEquals("kf-demo-model-card/tenant-data/v1", bundle.get("schema"));
        assertEquals(t, bundle.get("tenantId"));
        assertEquals(Map.of("pins", 2, "users", 2), bundle.get("counts"));
        assertEquals(TenantDataExport.contentSha256(bundle), bundle.get("contentSha256"));
    }

    @Test
    @DisplayName("an export of another tenant is refused (422), a user without the right gets 403")
    void exportRefusals() throws Exception {
        String t = tenant();
        assertEquals(422, calls().platform(t, "GET", "/admin/export?tenantId=" + tenant()).statusCode());
        assertEquals(403, calls().user(t, "sub-ana", List.of(), "GET", "/admin/export", null).statusCode());
    }

    @Test
    @DisplayName("the import takes the export back: dry-run writes nothing, apply writes it, a second apply writes 0; an altered bundle is 422")
    void roundTrip() throws Exception {
        String t = tenant();
        model(t, 8, "C");
        calls().user(t, "sub-ana", List.of(), "PUT", "/pins/8", null);
        String bundle = calls().platform(t, "GET", "/admin/export").body();
        calls().user(t, "sub-admin", List.of("api.v1.m.kf-demo-model-card.admin.pins.delete"), "DELETE", "/admin/pins", null);

        var dry = calls().user(t, "sub-admin", IMPORT, "POST", "/admin/import?mode=dry-run", bundle);
        assertEquals(200, dry.statusCode(), dry.body());
        assertEquals(0, json(dry).get("written").asInt());
        assertEquals(0, ModuleDb.pinsOf(t));
        assertEquals(1, json(calls().user(t, "sub-admin", IMPORT, "POST", "/admin/import?mode=apply", bundle)).get("written").asInt());
        assertEquals(0, json(calls().user(t, "sub-admin", IMPORT, "POST", "/admin/import?mode=apply", bundle)).get("written").asInt());
        assertEquals(1, ModuleDb.pinsOf(t));

        ObjectNode altered = (ObjectNode) ModuleCalls.JSON.readTree(bundle);
        ((ObjectNode) altered.get("pins").get(0)).put("pinnedBy", "sub-mallory");
        var refused = calls().user(t, "sub-admin", IMPORT, "POST", "/admin/import?mode=apply", altered.toString());
        assertEquals(422, refused.statusCode());
        assertThat(refused.body()).contains("bundle-altered");
    }
}
