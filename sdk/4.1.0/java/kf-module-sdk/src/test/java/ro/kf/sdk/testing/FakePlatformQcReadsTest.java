package ro.kf.sdk.testing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.PlatformRefusal;
import ro.kf.sdk.ReadPage;
import ro.kf.sdk.RequestContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FakePlatform} 0.8.1: the read ports {@code component.v1} and {@code inventory-roll.v1} of
 * protocol 1.2.1 (§15.1; {@code 10-citirile.md} §3.16, §3.17; the platform's providers), served with the
 * platform's filter rules. The fixtures follow the platform's {@code ModuleReadsQcIT}: two tenants, two denims and a
 * button in A (one roll available, one reserved, one consumed), a denim and its roll in B.
 */
class FakePlatformQcReadsTest {

    private static final String SLUG = "qc-mod";
    private static final String TENANT_A = "00000000-0000-0000-0000-00000000000a";
    private static final String TENANT_B = "00000000-0000-0000-0000-00000000000b";
    private static final String MANIFEST = """
            {"schemaVersion":2,"slug":"qc-mod","name":"n","version":"1.0.0",
             "i18n":{"en":{"n":"QC"}},
             "capabilities":{
               "reads":[{"port":"component.v1"},{"port":"inventory-roll.v1"}],
               "routes":[{"method":"GET","path":"/m/qc-mod/materials","right":"api.v1.m.qc-mod.reports.post","roles":["QC"]}]}}""";

    private FakePlatform fake;
    private KfModule kf;

    @BeforeEach
    void start() throws Exception {
        fake = FakePlatform.start(SLUG).manifest(MANIFEST);
        kf = fake.module();
        FakePlatform.Port components = fake.reads("component.v1");
        components.put(TENANT_A, "11", component(11, "QC219 Denim Blue", "MATERIAL", "#1F2A44"));
        components.put(TENANT_A, "12", component(12, "QC219 Denim Black", "MATERIAL", null));
        components.put(TENANT_A, "13", component(13, "QC219 Button Denim-look", "ACCESSORY", null));
        components.put(TENANT_B, "21", component(21, "QC219 Denim of B", "MATERIAL", null));
        FakePlatform.Port rolls = fake.reads("inventory-roll.v1");
        rolls.put(TENANT_A, "101", roll(101, 11, 120.5, "QC219-R1", "AVAILABLE"));
        rolls.put(TENANT_A, "102", roll(102, 11, 80, "QC219-R2", "RESERVED"));
        rolls.put(TENANT_A, "103", roll(103, 12, 0, "QC219-R3", "CONSUMED"));
        rolls.put(TENANT_B, "201", roll(201, 21, 50, "QC219-RB", "AVAILABLE"));
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private static Map<String, Object> component(long id, String name, String type, String hex) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("componentType", type);
        out.put("materialType", "MATERIAL".equals(type) ? "WOVEN" : null);
        out.put("unit", "m");
        if (hex == null) {
            out.put("colorSpec", null);
        } else {
            Map<String, Object> color = new LinkedHashMap<>();
            color.put("colorType", "SOLID");
            color.put("name", "Indigo");
            color.put("hexValue", hex);
            color.put("pantoneCode", null);
            color.put("patternName", null);
            out.put("colorSpec", color);
        }
        out.put("desiredCuttableWidth", 1480.0);
        out.put("status", "APPROVED");
        return out;
    }

    private static Map<String, Object> roll(long id, long componentId, double quantity, String sku, String state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("componentId", componentId);
        out.put("quantity", quantity);
        out.put("um", "m");
        out.put("rolWidth", 1500.0);
        out.put("sku", sku);
        out.put("source", "MANUAL");
        out.put("sourceRefId", null);
        out.put("state", state);
        return out;
    }

    private RequestContext ctx(String tenant, String user) {
        return verified(fake.signedContext(tenant, user, List.of(), "GET", "/materials"));
    }

    private RequestContext verified(FakePlatform.SignedContext signed) {
        return kf.verifyContext(signed.context(), signed.timestamp(), signed.signature(), signed.proof(), new byte[0]);
    }

    /** The ids of a page as longs (a JSON number decodes to the smallest fitting type). */
    private static List<Long> ids(ReadPage page) {
        return page.items().stream().map(item -> ((Number) item.get("id")).longValue()).toList();
    }

    private PlatformRefusal invalid(String port, Map<String, String> filters) {
        PlatformRefusal refused = assertThrows(PlatformRefusal.class,
                () -> kf.reads().list(ctx(TENANT_A, "sub-qc"), port, filters));
        assertEquals(PlatformRefusal.Kind.INVALID, refused.kind());
        assertEquals(400, refused.problem().status());
        return refused;
    }

    // ---- component.v1 ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("component.v1 ?q=den → the context tenant's materials whose name contains the text, any case, by id")
    void component_q_searchesTheNameInTheTenant() {
        ReadPage page = kf.reads().list(ctx(TENANT_A, "sub-qc"), "component.v1", Map.of("q", "den"));

        assertEquals(List.of(11L, 12L, 13L), ids(page));
        assertEquals(3, page.total());
        assertEquals("QC219 Denim Blue", page.items().get(0).get("name"));
        assertEquals("#1F2A44", ((Map<?, ?>) page.items().get(0).get("colorSpec")).get("hexValue"));
    }

    @Test
    @DisplayName("component.v1 ?q= with ?componentType=MATERIAL → only the materials; ?ids= → the visible ones, by id")
    void component_componentTypeAndIds() {
        RequestContext ctx = ctx(TENANT_A, "sub-qc");

        ReadPage materials = kf.reads().list(ctx, "component.v1", Map.of("q", "  QC219 DENIM ", "componentType", "MATERIAL"));
        ReadPage byIds = kf.reads().list(ctx, "component.v1", Map.of("ids", "12,21,11"));
        ReadPage paged = kf.reads().list(ctx, "component.v1", Map.of("q", "qc219"), 1, 2);

        assertEquals(List.of(11L, 12L), ids(materials));
        assertEquals(List.of(11L, 12L), ids(byIds), "21 is tenant B's");
        assertEquals(List.of(13L), ids(paged));
        assertEquals(3, paged.total());
    }

    @Test
    @DisplayName("component.v1: tenant B does not see A's materials (list and object)")
    void component_tenantIsolation() {
        RequestContext ofB = ctx(TENANT_B, "sub-b");

        assertEquals(List.of(21L), ids(kf.reads().list(ofB, "component.v1", Map.of("q", "denim"))));
        assertTrue(kf.reads().get(ofB, "component.v1", "11").isEmpty());
        assertTrue(kf.reads().get(ctx(TENANT_A, "sub-qc"), "component.v1", "21").isEmpty());
        assertEquals("QC219 Denim Black", kf.reads().get(ctx(TENANT_A, "sub-qc"), "component.v1", "12").orElseThrow().get("name"));
    }

    @Test
    @DisplayName("component.v1 without q and ids → 400 errors.q = \"required (or ids)\", as the platform")
    void component_neitherQNorIds_400() {
        PlatformRefusal refused = invalid("component.v1", Map.of());

        assertEquals(Map.of("q", "required (or ids)"), refused.errors());
        assertTrue(refused.problem().detail().startsWith("The read request is not valid (invalid filters): q: required (or ids)."),
                "the platform's detail (the SDK appends the errors): " + refused.problem().detail());
    }

    @Test
    @DisplayName("component.v1: q with ids, q under 2 or over 100 characters, two types, an unknown type or filter → 400 on the field")
    void component_invalidFilters_400() {
        assertEquals(Map.of("q", "not with ids: either the ids or the search"),
                invalid("component.v1", Map.of("q", "denim", "ids", "11")).errors());
        assertEquals(Map.of("q", "between 2 and 100 characters"), invalid("component.v1", Map.of("q", " d ")).errors());
        assertEquals(Map.of("q", "between 2 and 100 characters"), invalid("component.v1", Map.of("q", "x".repeat(101))).errors());
        assertEquals(Map.of("componentType", "one value"),
                invalid("component.v1", Map.of("q", "denim", "componentType", "MATERIAL,ACCESSORY")).errors());
        assertEquals(Map.of("componentType", "comma-separated values among [ACCESSORY, MATERIAL, PACKAGING]"),
                invalid("component.v1", Map.of("q", "denim", "componentType", "FABRIC")).errors());
        assertEquals(Map.of("name", "unknown filter"), invalid("component.v1", Map.of("q", "denim", "name", "x")).errors());
        assertEquals(Map.of("ids", "a comma-separated list of positive integers"),
                invalid("component.v1", Map.of("ids", "11,abc")).errors());
    }

    // ---- inventory-roll.v1 ----------------------------------------------------------------------------------

    @Test
    @DisplayName("inventory-roll.v1 ?componentId= → the material's rolls in the tenant, with their state, by id")
    void roll_componentId() {
        RequestContext ctx = ctx(TENANT_A, "sub-qc");

        ReadPage rolls = kf.reads().list(ctx, "inventory-roll.v1", Map.of("componentId", "11"));
        ReadPage among = kf.reads().list(ctx, "inventory-roll.v1", Map.of("componentId", "11", "ids", "102,103"));
        ReadPage byIds = kf.reads().list(ctx, "inventory-roll.v1", Map.of("ids", "103,201,101"));

        assertEquals(List.of(101L, 102L), ids(rolls));
        assertEquals(List.of("AVAILABLE", "RESERVED"), rolls.items().stream().map(r -> r.get("state")).toList());
        assertEquals(List.of(102L), ids(among), "with both: the material's rolls among the ids");
        assertEquals(List.of(101L, 103L), ids(byIds), "201 is tenant B's");
        assertEquals("CONSUMED", kf.reads().get(ctx, "inventory-roll.v1", "103").orElseThrow().get("state"));
    }

    @Test
    @DisplayName("inventory-roll.v1 without componentId (and without ids) → 400 errors.componentId = \"required (or ids)\"")
    void roll_withoutComponentId_400() {
        PlatformRefusal refused = invalid("inventory-roll.v1", Map.of());

        assertEquals(Map.of("componentId", "required (or ids)"), refused.errors());
        assertTrue(refused.problem().detail()
                        .startsWith("The read request is not valid (invalid filters): componentId: required (or ids)."),
                "the platform's detail (the SDK appends the errors): " + refused.problem().detail());
        assertEquals(Map.of("componentId", "a positive integer"),
                invalid("inventory-roll.v1", Map.of("componentId", "x")).errors());
        assertEquals(Map.of("q", "unknown filter"),
                invalid("inventory-roll.v1", Map.of("componentId", "11", "q", "r1")).errors());
    }

    @Test
    @DisplayName("inventory-roll.v1: tenant B does not see A's rolls (componentId of A, object of A)")
    void roll_tenantIsolation() {
        RequestContext ofB = ctx(TENANT_B, "sub-b");

        assertEquals(List.<Long>of(), ids(kf.reads().list(ofB, "inventory-roll.v1", Map.of("componentId", "11"))));
        assertEquals(List.of(201L), ids(kf.reads().list(ofB, "inventory-roll.v1", Map.of("componentId", "21"))));
        assertTrue(kf.reads().get(ofB, "inventory-roll.v1", "101").isEmpty());
    }

    // ---- the rights -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("DECIDED_PORTS: component.v1 → api.v1.components.get, inventory-roll.v1 → api.inventory.stock.get, no system")
    void decidedPorts() {
        assertEquals(new FakePlatform.PortRule("api.v1.components.get", false), FakePlatform.DECIDED_PORTS.get("component.v1"));
        assertEquals(new FakePlatform.PortRule("api.inventory.stock.get", false),
                FakePlatform.DECIDED_PORTS.get("inventory-roll.v1"));
    }

    @Test
    @DisplayName("a user without the port's right → 403 insufficient-rights naming the right; the system context → 403")
    void rights_403() {
        fake.userRights(TENANT_A, "sub-norights", "api.v1.components.get");
        RequestContext ctx = ctx(TENANT_A, "sub-norights");

        PlatformRefusal roll = assertThrows(PlatformRefusal.class,
                () -> kf.reads().list(ctx, "inventory-roll.v1", Map.of("componentId", "11")));
        RequestContext system = verified(fake.systemContext(TENANT_A, "GET", "/materials"));

        assertEquals("insufficient-rights", roll.reason());
        assertTrue(roll.problem().detail().contains("api.inventory.stock.get"));
        assertEquals(List.of(11L, 12L, 13L), ids(kf.reads().list(ctx, "component.v1", Map.of("q", "den"))));
        PlatformRefusal bySystem = assertThrows(PlatformRefusal.class, () -> kf.reads().get(system, "component.v1", "11"));
        assertEquals("insufficient-rights", bySystem.reason());
    }
}
