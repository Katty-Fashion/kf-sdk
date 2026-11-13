package ro.kf.sdk.testing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.sdk.ErrorClass;
import ro.kf.sdk.HookEvents;
import ro.kf.sdk.KfException;
import ro.kf.sdk.KfHttpServer;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.PlatformRefusal;
import ro.kf.sdk.RequestContext;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FakePlatform} 0.8.1: the caller's {@code person} in a signed context (protocol 1.2 §9.2, the
 * last key, only on the routes {@code audience: member}); the delivery of {@code tenant.after.delete} as the platform
 * makes it (a CloudEvent signed under the hook secret on {@code /_kf/hooks}, at least once, the tenant's objects purged);
 * the reads of a port answering {@code 503} (or a given refusal) on demand; {@code tenant.v1} among the decided ports.
 */
class FakePlatformPersonTenantDeleteTest {

    private static final String SLUG = "qc-mod";
    private static final String TENANT_A = "00000000-0000-0000-0000-00000000000a";
    private static final String TENANT_B = "00000000-0000-0000-0000-00000000000b";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MANIFEST = """
            {"schemaVersion":2,"slug":"qc-mod","name":"n","version":"1.0.0",
             "i18n":{"en":{"n":"QC"}},
             "capabilities":{
               "hooks":[{"point":"tenant.after.delete"}],
               "reads":[{"port":"component.v1"},{"port":"tenant.v1"}],
               "storage":[{"contentTypes":["image/jpeg"],"maxBytes":1024}],
               "routes":[{"method":"POST","path":"/m/qc-mod/feedback","right":"api.v1.m.qc-mod.feedback.post","audience":"member"},
                         {"method":"GET","path":"/m/qc-mod/reports","right":"api.v1.m.qc-mod.reports.get","roles":["QC"]}]}}""";
    private static final String NO_HOOK = """
            {"schemaVersion":2,"slug":"qc-mod","name":"n","version":"1.0.0",
             "i18n":{"en":{"n":"QC"}},
             "capabilities":{"routes":[{"method":"GET","path":"/m/qc-mod/reports","right":"api.v1.m.qc-mod.reports.get","roles":["QC"]}]}}""";

    private FakePlatform fake;
    private KfModule kf;
    private HttpServer module;
    /** The module's rows, per tenant: what its tenant.after.delete handler deletes. */
    private final Map<String, Set<String>> rows = new ConcurrentHashMap<>();
    private final List<HookEvents.TenantDeleted> handled = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        fake = FakePlatform.start(SLUG).manifest(MANIFEST);
        kf = fake.module();
        kf.tenants().onDeleted(deleted -> {
            handled.add(deleted);
            rows.remove(deleted.tenantId());       // idempotent: a second delivery finds nothing to delete
        });
        module = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        KfHttpServer.mount(module, kf);
        module.start();
        fake.callbackBase("http://127.0.0.1:" + module.getAddress().getPort());
        rows.put(TENANT_A, ConcurrentHashMap.newKeySet());
        rows.get(TENANT_A).addAll(List.of("r1", "r2"));
        rows.put(TENANT_B, ConcurrentHashMap.newKeySet());
        rows.get(TENANT_B).add("r3");
    }

    @AfterEach
    void stop() {
        module.stop(0);
        fake.close();
    }

    private static RequestContext verified(KfModule module, FakePlatform.SignedContext signed) {
        return module.verifyContext(signed.context(), signed.timestamp(), signed.signature(), signed.proof(), new byte[0]);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decoded(FakePlatform.SignedContext signed) {
        return JSON.readValue(new String(Base64.getUrlDecoder().decode(signed.context()), StandardCharsets.UTF_8), Map.class);
    }

    // ---- person ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a context signed with person → RequestContext.person() present; the key is the last, after requestId")
    void signedContextWithPerson() {
        FakePlatform.SignedContext signed = fake.signedContext(TENANT_A, "sub-1", List.of(), "POST", "/feedback",
                new RequestContext.Person("ana.pop@kf.ro", "Ana Pop"));

        RequestContext ctx = verified(kf, signed);

        assertEquals(new RequestContext.Person("ana.pop@kf.ro", "Ana Pop"), ctx.person());
        List<String> keys = new ArrayList<>(decoded(signed).keySet());
        assertEquals(List.of("requestId", "person"), keys.subList(keys.size() - 2, keys.size()));
    }

    @Test
    @DisplayName("person with null values is signed as JSON null; the old signedContext carries no person (unchanged)")
    void personNullValues_andOldSignedContextUnchanged() {
        RequestContext withNulls = verified(kf, fake.signedContext(TENANT_A, "sub-1", List.of(), "POST", "/feedback",
                null, new byte[0], new RequestContext.Person(null, null)));
        FakePlatform.SignedContext old = fake.signedContext(TENANT_A, "sub-1", List.of(), "POST", "/feedback");

        assertEquals(new RequestContext.Person(null, null), withNulls.person());
        assertNull(verified(kf, old).person());
        assertFalse(decoded(old).containsKey("person"));
        assertEquals("requestId", new ArrayList<>(decoded(old).keySet()).getLast());
    }

    @Test
    @DisplayName("person on a route the manifest does not declare audience: member → refused by the fake (the platform never sends it)")
    void personOnlyOnMemberRoutes() {
        assertThrows(IllegalArgumentException.class, () -> fake.signedContext(TENANT_A, "sub-1", List.of(), "GET",
                "/reports", new RequestContext.Person("a@kf.ro", "A")));
    }

    // ---- tenant.after.delete --------------------------------------------------------------------------------

    @Test
    @DisplayName("deliverTenantDeleted → the module's handler runs once, 200; the tenant's rows and objects are gone, the other tenant's stay")
    void deliverTenantDeleted_reachesTheHandler() {
        String objectOfA = fake.storage().put(TENANT_A, "image/jpeg", new byte[]{1, 2, 3});
        String objectOfB = fake.storage().put(TENANT_B, "image/jpeg", new byte[]{4});

        FakePlatform.Delivery delivery = fake.deliverTenantDeleted(TENANT_A);

        assertEquals(200, delivery.status(), delivery.body());
        assertEquals(1, handled.size());
        assertEquals(TENANT_A, handled.get(0).tenantId());
        assertEquals(delivery.eventId(), handled.get(0).eventId());
        assertEquals(delivery.deliveryId(), handled.get(0).deliveryId());
        assertFalse(rows.containsKey(TENANT_A));
        assertEquals(Set.of("r3"), rows.get(TENANT_B));
        assertTrue(fake.storage().object(objectOfA).isEmpty(), "the platform purges kf-modules/{slug}/{tenant}/");
        assertTrue(fake.storage().object(objectOfB).isPresent());
    }

    @Test
    @DisplayName("a second delivery of the same event (at least once) → 200 again, a new delivery id, nothing more deleted")
    void secondDelivery_idempotent() {
        FakePlatform.Delivery first = fake.deliverTenantDeleted(TENANT_A);

        FakePlatform.Delivery again = fake.deliverTenantDeleted(TENANT_A, first.eventId());

        assertEquals(200, again.status(), again.body());
        assertEquals(first.eventId(), again.eventId());
        assertNotEquals(first.deliveryId(), again.deliveryId());
        assertEquals(2, handled.size(), "the SDK does not deduplicate: the module is idempotent");
        assertEquals(Set.of("r3"), rows.get(TENANT_B));
    }

    @Test
    @DisplayName("a delivery signed under another secret → 401 from the module, no handler, nothing purged")
    void forgedSignature_401() {
        String objectOfA = fake.storage().put(TENANT_A, "image/jpeg", new byte[]{1});

        FakePlatform.Delivery forged = fake.deliverTenantDeletedSignedWith(TENANT_A, "not-the-hook-secret");

        assertEquals(401, forged.status());
        assertTrue(handled.isEmpty());
        assertTrue(rows.containsKey(TENANT_A));
        assertTrue(fake.storage().object(objectOfA).isPresent());
    }

    @Test
    @DisplayName("a manifest that does not subscribe to tenant.after.delete → the fake refuses to deliver (the platform would not)")
    void notSubscribed_notDelivered() {
        fake.manifest(NO_HOOK);

        assertThrows(IllegalStateException.class, () -> fake.deliverTenantDeleted(TENANT_A));
        assertTrue(handled.isEmpty());
    }

    // ---- reads unavailable, tenant.v1 ----------------------------------------------------------------------

    @Test
    @DisplayName("failReads(component.v1, UNAVAILABLE) → the reads client throws the SDK's transient error (503); tenant.v1 still answers")
    void failReads_503_onlyThatPort() {
        fake.reads("component.v1").put(TENANT_A, "1", Map.of("id", "1", "name", "Bumbac"));
        fake.reads("tenant.v1").put(TENANT_A, TENANT_A, Map.of("id", TENANT_A, "name", "KF Dev"));
        fake.failReads("component.v1", FakePlatform.Refusal.UNAVAILABLE);
        RequestContext ctx = verified(kf, fake.signedContext(TENANT_A, "sub-1", List.of(), "GET", "/reports"));

        KfException failure = assertThrows(KfException.class, () -> kf.reads().get(ctx, "component.v1", "1"));
        Optional<Map<String, Object>> tenant = kf.reads().get(ctx, "tenant.v1", TENANT_A);

        assertFalse(failure instanceof PlatformRefusal, "a 503 is not a refusal");
        assertEquals(503, failure.problem().status());
        assertEquals(ErrorClass.TRANSIENT, failure.errorClass());
        assertEquals("KF Dev", tenant.orElseThrow().get("name"));
    }

    @Test
    @DisplayName("failReads with another refusal answers it; failReads(port, null) → the port answers again")
    void failReads_givenRefusal_thenCleared() {
        fake.reads("component.v1").put(TENANT_A, "1", Map.of("id", "1", "name", "Bumbac"));
        RequestContext ctx = verified(kf, fake.signedContext(TENANT_A, "sub-1", List.of(), "GET", "/reports"));
        fake.failReads("component.v1", new FakePlatform.Refusal(403, "insufficient-rights", "permanent", "no right"));

        PlatformRefusal refused = assertThrows(PlatformRefusal.class, () -> kf.reads().get(ctx, "component.v1", "1"));
        fake.failReads("component.v1", null);

        assertEquals(PlatformRefusal.Kind.FORBIDDEN, refused.kind());
        assertEquals("insufficient-rights", refused.reason());
        assertEquals("Bumbac", kf.reads().get(ctx, "component.v1", "1").orElseThrow().get("name"));
    }

    @Test
    @DisplayName("DECIDED_PORTS has tenant.v1: right api.v1.tenants.get, no system context")
    void decidedPorts_tenantV1() {
        FakePlatform.PortRule rule = FakePlatform.DECIDED_PORTS.get("tenant.v1");

        assertInstanceOf(FakePlatform.PortRule.class, rule);
        assertEquals(new FakePlatform.PortRule("api.v1.tenants.get", false), rule);
    }
}
