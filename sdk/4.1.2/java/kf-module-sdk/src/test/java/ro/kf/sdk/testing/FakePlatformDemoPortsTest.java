package ro.kf.sdk.testing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.sdk.KfException;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.PlatformRefusal;
import ro.kf.sdk.ReadPage;
import ro.kf.sdk.RequestContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FakePlatform} for protocol 1.3.1 (delivered): the list of {@code model.v1}
 * without {@code ids} for a user holding the port's right, and the new port {@code model-version.garment-image.v1}
 * (object, list with a mandatory {@code ids}; right {@code api.v1.models.get}; not a system port) -- §15.1.
 */
class FakePlatformDemoPortsTest {

    private static final String SLUG = "demo-mod";
    private static final String TENANT_A = "00000000-0000-0000-0000-00000000000a";
    private static final String TENANT_B = "00000000-0000-0000-0000-00000000000b";
    private static final String GARMENT_IMAGE = "model-version.garment-image.v1";
    private static final String MANIFEST = """
            {"schemaVersion":2,"slug":"demo-mod","name":"n","version":"1.0.0",
             "i18n":{"en":{"n":"Demo"}},
             "capabilities":{
               "reads":[{"port":"model.v1"},{"port":"model-version.garment-image.v1"}],
               "routes":[{"method":"GET","path":"/m/demo-mod/cards","right":"api.v1.m.demo-mod.cards.get","roles":["DESIGNER"]}]}}""";

    private FakePlatform fake;
    private KfModule kf;

    @BeforeEach
    void start() throws Exception {
        fake = FakePlatform.start(SLUG).manifest(MANIFEST);
        kf = fake.module();
        FakePlatform.Port models = fake.reads("model.v1");
        models.put(TENANT_A, "3", model(3, "Fusta", "Ana"));
        models.put(TENANT_A, "1", model(1, "Rochie", null));
        models.put(TENANT_A, "2", model(2, "Bluza", "Ioana"));
        models.put(TENANT_B, "9", model(9, "Of B", "B"));
        FakePlatform.Port images = fake.reads(GARMENT_IMAGE);
        images.put(TENANT_A, "17", image(17, "https://s3.local/models/1/versions/17/garment-image/a.png"));
        images.put(TENANT_A, "18", image(18, null));
        images.put(TENANT_B, "27", image(27, "https://s3.local/models/9/versions/27/garment-image/b.png"));
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private static Map<String, Object> model(long id, String name, String owner) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("modelOwner", owner);
        return out;
    }

    private static Map<String, Object> image(long versionId, String url) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", versionId);
        out.put("url", url);
        out.put("expiresAt", url == null ? null : "2026-11-02T10:10:00Z");
        return out;
    }

    private RequestContext ctx(String tenant, String user) {
        return verified(fake.signedContext(tenant, user, List.of(), "GET", "/cards"));
    }

    private RequestContext system(String tenant) {
        return verified(fake.systemContext(tenant, "GET", "/cards"));
    }

    private RequestContext verified(FakePlatform.SignedContext signed) {
        return kf.verifyContext(signed.context(), signed.timestamp(), signed.signature(), signed.proof(), new byte[0]);
    }

    private static List<Long> ids(ReadPage page) {
        return page.items().stream().map(item -> ((Number) item.get("id")).longValue()).toList();
    }

    @Test
    @DisplayName("1. DECIDED_PORTS: model-version.garment-image.v1 -> api.v1.models.get, not a system port")
    void garmentImagePortIsDecided() {
        assertEquals(new FakePlatform.PortRule("api.v1.models.get", false), FakePlatform.DECIDED_PORTS.get(GARMENT_IMAGE));
    }

    @Test
    @DisplayName("2. model.v1 listed without ids by a user holding the right: every model of the tenant, by id, paged")
    void modelListWithoutIdsForAUserWithTheRight() {
        fake.userRights(TENANT_A, "sub-designer", "api.v1.models.get");
        RequestContext ctx = ctx(TENANT_A, "sub-designer");

        ReadPage first = kf.reads().list(ctx, "model.v1", Map.of(), 0, 2);
        assertEquals(List.of(1L, 2L), ids(first));
        assertEquals(3, first.total());
        ReadPage second = kf.reads().list(ctx, "model.v1", Map.of(), 1, 2);
        assertEquals(List.of(3L), ids(second));
        assertEquals("Ana", second.items().get(0).get("modelOwner"));

        assertEquals(List.of(2L, 3L), ids(kf.reads().list(ctx, "model.v1", Map.of("ids", "3,2,9"))), "ids: unchanged");

        PlatformRefusal unknown = assertThrows(PlatformRefusal.class,
                () -> kf.reads().list(ctx, "model.v1", Map.of("name", "Rochie")));
        assertEquals(400, unknown.problem().status());
        assertEquals(Map.of("name", "unknown filter"), unknown.errors());

        fake.userRights(TENANT_A, "sub-none");
        PlatformRefusal withoutRight = assertThrows(PlatformRefusal.class,
                () -> kf.reads().list(ctx(TENANT_A, "sub-none"), "model.v1", Map.of()));
        assertEquals(403, withoutRight.problem().status());
    }

    @Test
    @DisplayName("3. model.v1 listed by the system context: refused, as today")
    void modelListBySystemIsRefused() {
        assertThrows(KfException.class, () -> kf.reads().list(system(TENANT_A), "model.v1", Map.of()));
    }

    @Test
    @DisplayName("4. model-version.garment-image.v1 listed without ids: 400 ids: required; with ids: among them, in the tenant")
    void garmentImageListRequiresIds() {
        RequestContext ctx = ctx(TENANT_A, "sub-designer");

        PlatformRefusal refused = assertThrows(PlatformRefusal.class, () -> kf.reads().list(ctx, GARMENT_IMAGE, Map.of()));
        assertEquals(PlatformRefusal.Kind.INVALID, refused.kind());
        assertEquals(400, refused.problem().status());
        assertEquals(Map.of("ids", "required"), refused.errors());

        ReadPage page = kf.reads().list(ctx, GARMENT_IMAGE, Map.of("ids", "18,17,27"));
        assertEquals(List.of(17L, 18L), ids(page), "the version of tenant B is absent");
        assertEquals(null, page.items().get(1).get("url"));

        PlatformRefusal unknown = assertThrows(PlatformRefusal.class,
                () -> kf.reads().list(ctx, GARMENT_IMAGE, Map.of("ids", "17", "modelId", "1")));
        assertEquals(Map.of("modelId", "unknown filter"), unknown.errors());
    }

    @Test
    @DisplayName("5. model-version.garment-image.v1 object by the system context: 403; by a user: the object")
    void garmentImageObjectRefusesTheSystem() {
        PlatformRefusal refused = assertThrows(PlatformRefusal.class,
                () -> kf.reads().get(system(TENANT_A), GARMENT_IMAGE, "17"));
        assertEquals(403, refused.problem().status());

        Map<String, Object> object = kf.reads().get(ctx(TENANT_A, "sub-designer"), GARMENT_IMAGE, "17").orElseThrow();
        assertEquals("2026-11-02T10:10:00Z", object.get("expiresAt"));
        assertEquals(true, kf.reads().get(ctx(TENANT_A, "sub-designer"), GARMENT_IMAGE, "27").isEmpty(), "404 in another tenant");
    }
}
