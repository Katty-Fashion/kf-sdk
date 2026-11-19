package ro.kf.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.sdk.testing.FakePlatform;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol 2.3 (docs/modules/kf-3d/CONTRACT.md §2, §4, §5, §6): the two version points of a model
 * ({@code model.after.version-copied}, {@code model.after.version-delete}) delivered by {@link FakePlatform} to the
 * handlers of {@link ModelHooks}; the service port {@code collaboration.asset-annotations.v1} ({@link AssetAnnotations}),
 * refused locally when not declared; the two new reads; the {@code storage} ceiling for a lone GLB.
 */
class ModelHooksVersionPointsTest {

    private static final String SLUG = "kf-3d";
    private static final String TENANT = "00000000-0000-0000-0000-00000000000a";
    private static final String MANIFEST = """
            {"schemaVersion":3,"slug":"kf-3d","name":"n","version":"1.0.0","platform":">=4.1",
             "i18n":{"en":{"n":"3D"}},
             "capabilities":{
               "hooks":[{"point":"model.after.version-copied"},{"point":"model.after.version-delete"}],
               "reads":[{"port":"model-version.pattern.v1"},{"port":"model-version.visibility.v1"}],
               "services":[{"port":"collaboration.asset-annotations.v1"}],
               "settings":[{"key":"3d-live","type":"BOOLEAN","default":false,"label":"n"}],
               "storage":[{"contentTypes":["model/gltf-binary"],"maxBytes":536870912}]}}""";

    private FakePlatform fake;
    private KfModule kf;
    private HttpServer module;
    private final List<HookEvents.AfterEvent> copied = new CopyOnWriteArrayList<>();
    private final List<HookEvents.AfterEvent> deleted = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        fake = FakePlatform.start(SLUG).manifest(MANIFEST);
        kf = fake.module();
        kf.models().afterVersionCopied(copied::add).afterVersionDelete(deleted::add);
        module = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        KfHttpServer.mount(module, kf);
        module.start();
        fake.callbackBase("http://127.0.0.1:" + module.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        module.stop(0);
        fake.close();
    }

    private RequestContext user() {
        FakePlatform.SignedContext s = fake.signedContext(TENANT, "sub-1", List.of(), "DELETE", "/m/kf-3d/assets/a1");
        return kf.verifyContext(s.context(), s.timestamp(), s.signature(), s.proof(), new byte[0]);
    }

    /** The same delivery's context, 301 s old: outside its window. */
    private static HookEvents.DeliveryContext stale(HookEvents.DeliveryContext delivery) {
        var json = "{\"v\":1,\"tenant\":\"" + TENANT + "\",\"user\":null,\"actor\":{\"type\":\"system\",\"via\":\"hook\","
                + "\"hookId\":\"d-1\"},\"locale\":\"en\",\"rights\":[],\"method\":\"POST\",\"path\":\"/_kf/hooks\","
                + "\"query\":null,\"ts\":" + (java.time.Instant.now().getEpochSecond() - 301) + ",\"requestId\":\"d-1\"}";
        return HookEvents.DeliveryContext.of(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)), delivery.proof());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    @DisplayName("the constants and the registration carry both points")
    void constantsAndRegistration() {
        assertEquals("model.after.version-copied", HookEvents.AFTER_VERSION_COPIED);
        assertEquals("model.after.version-delete", HookEvents.AFTER_VERSION_DELETE);
        List<String> events = kf.models().registration().stream().map(h -> (String) h.get("event")).toList();
        assertTrue(events.contains("model.after.version-copied"), events.toString());
        assertTrue(events.contains("model.after.version-delete"), events.toString());
    }

    @Test
    @DisplayName("afterVersionCopied receives the model id, the source and the target version; a redelivery comes again")
    void versionCopied() {
        FakePlatform.Delivery first = fake.deliverVersionCopied(TENANT, "42", "17", "18");
        assertEquals(200, first.status(), first.body());
        HookEvents.AfterEvent ev = copied.getFirst();
        assertEquals("model.after.version-copied", ev.event());
        assertEquals("42", ev.id());
        assertEquals(first.eventId(), ev.eventId());
        assertEquals("17", map(ev.data().get("before")).get("versionId"));
        assertEquals("18", map(ev.data().get("after")).get("versionId"));
        assertEquals("17", map(ev.data().get("after")).get("sourceVersionId"));
        assertEquals(List.of("versions"), ev.changed());
        assertTrue(ev.deliveryContext().isPresent());

        fake.deliverVersionCopied(TENANT, "42", "17", "18", first.eventId());
        assertEquals(2, copied.size());          // at least once: the SDK does not deduplicate, the module is idempotent
        assertTrue(deleted.isEmpty());
    }

    @Test
    @DisplayName("afterVersionDelete receives before.versionId and a null after")
    void versionDelete() {
        FakePlatform.Delivery d = fake.deliverVersionDelete(TENANT, "42", "17");
        assertEquals(200, d.status(), d.body());
        HookEvents.AfterEvent ev = deleted.getFirst();
        assertEquals("model.after.version-delete", ev.event());
        assertEquals("42", ev.id());
        assertEquals("17", map(ev.data().get("before")).get("versionId"));
        assertNull(ev.data().get("after"));
        assertTrue(copied.isEmpty());
    }

    @Test
    @DisplayName("a manifest that does not subscribe is not delivered to (the platform would not)")
    void notSubscribed() {
        fake.manifest("""
                {"schemaVersion":3,"slug":"kf-3d","name":"n","version":"1.0.0","platform":">=4.1",
                 "i18n":{"en":{"n":"3D"}},"capabilities":{"hooks":[{"point":"model.after.version-copied"}]}}""");
        assertThrows(IllegalStateException.class, () -> fake.deliverVersionDelete(TENANT, "42", "17"));
    }

    @Test
    @DisplayName(": a delivery's context reads one object and the effective settings, as system via hook")
    void deliveryContextReadsAndSettings() {
        fake.reads("model-version.pattern.v1").put(TENANT, "17", Map.of("id", "17", "groups", List.of()));
        fake.settings(TENANT, "3d-live", true);
        assertEquals(200, fake.deliverVersionCopied(TENANT, "42", "17", "18").status());
        HookEvents.DeliveryContext delivery = copied.getFirst().deliveryContext().orElseThrow();

        assertEquals(Map.of("id", "17", "groups", List.of()),
                kf.reads().get(delivery, "model-version.pattern.v1", "17").orElseThrow());
        assertTrue(kf.reads().get(delivery, "model-version.pattern.v1", "99").isEmpty());
        assertEquals(Boolean.TRUE, kf.settings().effective(delivery).get("3d-live"));
    }

    @Test
    @DisplayName(": the delivery overloads refuse locally an undeclared port, a blank id and a stale context")
    void deliveryContextLocalRefusals() {
        fake.deliverVersionCopied(TENANT, "42", "17", "18");
        HookEvents.DeliveryContext delivery = copied.getFirst().deliveryContext().orElseThrow();

        KfException undeclared = assertThrows(KfException.class, () -> kf.reads().get(delivery, "model.v1", "17"));
        assertTrue(undeclared.getMessage().contains("capabilities.reads"), undeclared.getMessage());
        KfException blank = assertThrows(KfException.class,
                () -> kf.reads().get(delivery, "model-version.pattern.v1", " "));
        assertTrue(blank.getMessage().contains("id is required"), blank.getMessage());
        HookEvents.DeliveryContext stale = stale(delivery);
        KfException old = assertThrows(KfException.class, () -> kf.settings().effective(stale));
        assertTrue(old.getMessage().contains("300 s"), old.getMessage());
    }

    @Test
    @DisplayName("collaboration.asset-annotations.v1: a user's delete and a hook's delete are accepted and recorded")
    void assetAnnotationsDelete() {
        kf.assetAnnotations().delete(user(), "c0ffee00-0000-4000-8000-000000000001");

        FakePlatform.Delivery d = fake.deliverVersionDelete(TENANT, "42", "17");
        kf.assetAnnotations().delete(copiedOrDeleted().deliveryContext().orElseThrow(), "c0ffee00-0000-4000-8000-000000000002");
        assertEquals(200, d.status());

        List<FakePlatform.AssetAnnotationDelete> calls = fake.assetAnnotationDeletes();
        assertEquals(2, calls.size());
        assertEquals(new FakePlatform.AssetAnnotationDelete(TENANT, "c0ffee00-0000-4000-8000-000000000001", "sub-1", false), calls.get(0));
        assertTrue(calls.get(1).system());
        assertNull(calls.get(1).user());
        kf.assetAnnotations().delete(user(), "c0ffee00-0000-4000-8000-000000000001");   // idempotent: 204 again
        assertEquals(3, fake.assetAnnotationDeletes().size());
    }

    private HookEvents.AfterEvent copiedOrDeleted() {
        return deleted.getFirst();
    }

    @Test
    @DisplayName("asset-annotations: a bad assetId and an undeclared port are refused locally, nothing sent")
    void assetAnnotationsLocalRefusals() {
        KfException bad = assertThrows(KfException.class, () -> kf.assetAnnotations().delete(user(), "a/b"));
        assertTrue(bad.getMessage().contains("assetId must match"), bad.getMessage());

        KfModule undeclared = KfModule.of(fake.url(), SLUG, fake.token(), fake.hookSecret(), null).withManifest("""
                {"schemaVersion":3,"slug":"kf-3d","name":"n","version":"1.0.0","platform":">=4.1",
                 "i18n":{"en":{"n":"3D"}},"capabilities":{"services":[{"port":"order.services.v1"}]}}""");
        FakePlatform.SignedContext s = fake.signedContext(TENANT, "sub-1", List.of(), "DELETE", "/x");
        RequestContext ctx = undeclared.verifyContext(s.context(), s.timestamp(), s.signature(), s.proof(), new byte[0]);
        KfException refused = assertThrows(KfException.class, () -> undeclared.assetAnnotations().delete(ctx, "a1"));
        assertTrue(refused.getMessage().contains("not declared in capabilities.services"), refused.getMessage());
        assertTrue(fake.assetAnnotationDeletes().isEmpty());
    }

    @Test
    @DisplayName("the two reads: pattern allows the system context, visibility does not; DECIDED_PORTS carries both")
    void readsAreDecided() {
        assertEquals(new FakePlatform.PortRule("api.v1.models.get", true), FakePlatform.DECIDED_PORTS.get("model-version.pattern.v1"));
        assertEquals(new FakePlatform.PortRule("api.v1.models.get", false), FakePlatform.DECIDED_PORTS.get("model-version.visibility.v1"));

        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("pieces", List.of(Map.of("id", "p1", "sourcePieceRef", "ref-1", "name", "Front")));
        pattern.put("groups", List.of(Map.of("id", "g1", "name", "Body", "memberPieceIds", List.of("p1"))));
        fake.reads("model-version.pattern.v1").put(TENANT, "17", pattern);
        fake.reads("model-version.visibility.v1").put(TENANT, "17", Map.of("visible", true, "writable", false));

        FakePlatform.SignedContext sys = fake.systemContext(TENANT, "GET", "/x");
        RequestContext system = kf.verifyContext(sys.context(), sys.timestamp(), sys.signature(), sys.proof(), new byte[0]);
        assertEquals(pattern, kf.reads().get(system, "model-version.pattern.v1", "17").orElseThrow());
        assertThrows(PlatformRefusal.class, () -> kf.reads().get(system, "model-version.visibility.v1", "17"));
    }

    @Test
    @DisplayName("storage: 512 MiB for a lone GLB is accepted by the check and by the upload; above it is refused")
    void storageCeiling() {
        assertEquals(536_870_912, Manifest.storageCeiling(List.of("model/gltf-binary")));
        assertEquals(10_485_760, Manifest.storageCeiling(List.of("model/gltf-binary", "image/png")));
        assertEquals(10_485_760, Manifest.storageCeiling(List.of("image/png")));
        assertEquals(536_870_912L, new Manifest.StorageCapability(List.of("model/gltf-binary"), 536_870_913).effectiveMaxBytes());
        assertEquals(10_485_760L, new Manifest.StorageCapability(List.of("image/png"), 20_971_520).effectiveMaxBytes());
    }
}
