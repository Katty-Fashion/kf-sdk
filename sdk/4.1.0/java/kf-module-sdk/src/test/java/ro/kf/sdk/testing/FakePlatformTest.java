package ro.kf.sdk.testing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.sdk.AuditRecord;
import ro.kf.sdk.HookResult;
import ro.kf.sdk.ErrorClass;
import ro.kf.sdk.KfException;
import ro.kf.sdk.KfHttpServer;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.PlatformRefusal;
import ro.kf.sdk.RequestContext;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FakePlatform}: a module's route, called with a context the fake signed, reads and writes through the SDK
 * clients against the fake, which checks the token and the proof as the platform does and records the requests.
 */
class FakePlatformTest {

    private static final String TENANT_A = "00000000-0000-0000-0000-00000000000a";
    private static final String TENANT_B = "00000000-0000-0000-0000-00000000000b";
    private static final String MANIFEST = """
            {"schemaVersion":2,"slug":"fake-mod","name":"n","version":"1.0.0",
             "i18n":{"en":{"n":"Fake","l":"Label"}},
             "capabilities":{
               "reads":[{"port":"model.v1"},{"port":"document.v1"}],
               "fields":[{"entity":"model","name":"note","type":"STRING","table":"notes","label":"l"}],
               "settings":[{"key":"live","type":"BOOLEAN","default":false,"label":"l"},
                           {"key":"batch-size","type":"NUMBER","default":50,"label":"l"}],
               "secrets":[{"name":"r3-client-secret","scope":"tenant","writeRight":"api.v1.m.fake-mod.admin.post"}],
               "audit":[{"access":"write"},{"access":"read-own"}],
               "routes":[{"method":"GET","path":"/x","right":"api.v1.m.fake-mod.x.get","roles":["TENANT_ADMIN"]},
                         {"method":"POST","path":"/admin","right":"api.v1.m.fake-mod.admin.post","roles":["TENANT_ADMIN"]}]}}""";

    private FakePlatform fake;
    private HttpServer module;
    private KfModule kf;
    private final AtomicReference<RequestContext> seen = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        fake = FakePlatform.start("fake-mod").manifest(MANIFEST);
        fake.reads("model.v1").put(TENANT_A, "1", Map.of("id", "1", "name", "Rochie"))
                .put(TENANT_B, "2", Map.of("id", "2", "name", "Fusta"));
        kf = fake.module();
        module = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // the module's route: reads model.v1/1 and writes its own extension value on it
        KfHttpServer.route(module, kf, "/x", (ctx, exchange, body) -> {
            seen.set(ctx);
            Map<String, Object> model = kf.reads().get(ctx, "model.v1", "1").orElseThrow();
            kf.extensions().write(ctx, "model", "1", Map.of("note", "seen " + model.get("name")));
            return new HookResult(200, "{\"name\":\"" + model.get("name") + "\"}");
        });
        module.start();
    }

    @AfterEach
    void stop() {
        module.stop(0);
        fake.close();
    }

    private HttpResponse<String> callX(FakePlatform.SignedContext ctx) throws Exception {
        HttpRequest request = ctx.applyTo(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + module.getAddress().getPort()
                + "/x"))).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a route called with fake.signedContext reads and writes through the SDK; the fake gets the context and its proof")
    void moduleRoute_readsAndWrites_throughTheFake() throws Exception {
        FakePlatform.SignedContext ctx = fake.signedContext(TENANT_A, "sub-1", List.of(), "GET", "/x");

        HttpResponse<String> response = callX(ctx);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("{\"name\":\"Rochie\"}", response.body());
        assertEquals(TENANT_A, seen.get().tenant());
        assertEquals(Map.of("note", "seen Rochie"), fake.extensions(TENANT_A, "model", "1"));
        List<FakePlatform.Recorded> requests = fake.requests();
        assertEquals(2, requests.size());
        assertEquals("/api/v1/platform/modules/fake-mod/reads/model.v1/1", requests.get(0).path());
        assertEquals("PATCH", requests.get(1).method());
        for (FakePlatform.Recorded r : requests) {
            assertEquals(ctx.context(), r.context(), "the context goes back unchanged");
            assertEquals(ctx.proof(), r.proof(), "the proof goes back unchanged");
        }
    }

    @Test
    @DisplayName("the tenant of the context decides what is visible: model 1 of tenant A is not found from tenant B")
    void readsAreTenantScoped() throws Exception {
        HttpResponse<String> response = callX(fake.signedContext(TENANT_B, "sub-2", List.of(), "GET", "/x"));

        assertEquals(500, response.statusCode(), "the route's orElseThrow: model 1 is not visible in B");
        assertTrue(fake.extensions(TENANT_B, "model", "1").isEmpty());
    }

    @Test
    @DisplayName("a wrong module token → 401 from the fake (PlatformRefusal UNAUTHORIZED); a context the fake did not issue → 401")
    void wrongTokenOrForeignContext_is401() {
        KfModule wrong = KfModule.of(fake.url(), "fake-mod", "kfm_wrong-token", fake.hookSecret(), null);
        FakePlatform.SignedContext signed = fake.signedContext(TENANT_A, "sub-1", List.of(), "GET", "/x");
        RequestContext ctx = wrong.verifyContext(signed.context(), signed.timestamp(), signed.signature(), signed.proof(),
                new byte[0]);
        PlatformRefusal refusal = assertInstanceOf(PlatformRefusal.class,
                assertThrows(KfException.class, () -> wrong.reads().get(ctx, "model.v1", "1")));
        assertEquals(PlatformRefusal.Kind.UNAUTHORIZED, refusal.kind());

        RequestContext forged = kf.verifyContext(signed.context(), signed.timestamp(), signed.signature(), "0".repeat(64),
                new byte[0]);
        PlatformRefusal invalid = assertInstanceOf(PlatformRefusal.class,
                assertThrows(KfException.class, () -> kf.reads().get(forged, "model.v1", "1")));
        assertEquals("invalid-context", invalid.reason());
    }

    @Test
    @DisplayName("the platform right of the port, the system context, an undeclared port, a tenant where the module is not live")
    void theReadRules() {
        RequestContext withoutRight = verify(fake.userRights(TENANT_A, "sub-3", "api.v1.orders.get")
                .signedContext(TENANT_A, "sub-3", List.of(), "GET", "/x"));
        assertEquals("insufficient-rights", refusal(() -> kf.reads().get(withoutRight, "model.v1", "1")).reason());

        RequestContext system = verify(fake.hookContext(TENANT_A, UUID.randomUUID().toString(), "POST", "/hooked", new byte[0]));
        assertEquals("1", kf.reads().get(system, "model.v1", "1").orElseThrow().get("id"), "model.v1 is a system port");
        assertEquals("insufficient-rights", refusal(() -> kf.reads().get(system, "document.v1", "5")).reason());

        KfModule bare = KfModule.of(fake.url(), "fake-mod", fake.token(), fake.hookSecret(), null);
        FakePlatform.SignedContext signed = fake.signedContext(TENANT_A, "sub-1", List.of(), "GET", "/x");
        RequestContext bareCtx = bare.verifyContext(signed.context(), signed.timestamp(), signed.signature(), signed.proof(),
                new byte[0]);
        assertEquals("capability-not-declared", refusal(() -> bare.reads().get(bareCtx, "order.v1", "1")).reason(),
                "the fake checks the capability against the manifest it was given");

        fake.notLive(TENANT_B);
        RequestContext inB = verify(fake.signedContext(TENANT_B, "sub-2", List.of(), "GET", "/x"));
        assertEquals("module-not-live", refusal(() -> kf.reads().list(inB, "model.v1", Map.of())).reason());
    }

    @Test
    @DisplayName("list: paging, the ids filter, the platform's order by id")
    void listPagingAndFilters() {
        for (int i = 10; i >= 3; i--) fake.reads("model.v1").put(TENANT_A, String.valueOf(i), Map.of("id", String.valueOf(i), "kind", i % 2 == 0 ? "even" : "odd"));
        RequestContext ctx = verify(fake.signedContext(TENANT_A, "sub-1", List.of(), "GET", "/x"));

        var first = kf.reads().list(ctx, "model.v1", Map.of(), 0, 4);
        assertEquals(9, first.total());
        assertEquals(List.of("1", "3", "4", "5"), first.items().stream().map(o -> o.get("id")).toList());
        assertTrue(first.hasNext());
        assertEquals(List.of("4", "8"), kf.reads().list(ctx, "model.v1", Map.of("ids", "8,4,99")).items().stream()
                .map(o -> o.get("id")).toList());
        // equality filters on a port without its own rule (model.v1 takes only ids from 0.9.1, protocol 1.3.1)
        for (int i = 10; i >= 3; i--) fake.reads("document.v1").put(TENANT_A, String.valueOf(i), Map.of("id", String.valueOf(i), "kind", i % 2 == 0 ? "even" : "odd"));
        assertEquals(List.of("4", "6", "8", "10"), kf.reads().list(ctx, "document.v1", Map.of("kind", "even")).items().stream()
                .map(o -> o.get("id")).toList());
    }

    @Test
    @DisplayName("settings with the manifest's defaults; a secret written then resolved; audit and notifications recorded")
    void settingsSecretsAuditNotifications() {
        fake.settings(TENANT_A, "live", true);
        RequestContext admin = verify(fake.signedContext(TENANT_A, "sub-1", List.of("api.v1.m.fake-mod.admin.post"), "POST", "/admin"));

        Map<String, Object> settings = kf.settings().effective(admin);
        assertEquals(Boolean.TRUE, settings.get("live"));
        assertEquals(new BigDecimal("50"), settings.get("batch-size"));

        String ref = kf.secrets().write(admin, "r3-client-secret", "s3cr3t");
        assertEquals(fake.secretRef(TENANT_A, "r3-client-secret"), ref);
        assertEquals("s3cr3t", fake.secretValue(ref));
        assertEquals("s3cr3t", kf.secrets().resolve(admin, ref).value());

        RequestContext noRight = verify(fake.signedContext(TENANT_A, "sub-2", List.of(), "POST", "/admin"));
        assertEquals("insufficient-rights", refusal(() -> kf.secrets().write(noRight, "r3-client-secret", "x")).reason());

        kf.audit().record(admin, new AuditRecord("NOTE_CREATED", "Note", "42"));
        assertEquals("NOTE_CREATED", fake.auditRows().getFirst().get("action"));
        assertEquals(1, kf.audit().list(admin, "Note", "42").size());

        kf.notifications().send(UUID.fromString(TENANT_A), "author-changed", List.of("sub-9"), Map.of("modelName", "X"));
        assertEquals("author-changed", fake.notifications().getFirst().get("key"));
    }

    @Test
    @DisplayName("an undeclared extension field → 400 naming extensions.<slug>.<field>, from the fake (no manifest on the module)")
    void undeclaredExtensionField_isRefusedByTheFake() {
        KfModule bare = KfModule.of(fake.url(), "fake-mod", fake.token(), fake.hookSecret(), null);
        FakePlatform.SignedContext signed = fake.signedContext(TENANT_A, "sub-1", List.of(), "POST", "/push");
        RequestContext ctx = bare.verifyContext(signed.context(), signed.timestamp(), signed.signature(), signed.proof(),
                new byte[0]);
        PlatformRefusal invalid = refusal(() -> bare.extensions().write(ctx, "model", "1", Map.of("colour", "red")));
        assertEquals(PlatformRefusal.Kind.INVALID, invalid.kind());
        assertNotNull(invalid.errors().get("extensions.fake-mod.colour"));
    }

    @Test
    @DisplayName("an existing reference under a declared secrets[].external prefix resolves; the secret store down is "
            + "503 secret-store-unreachable (transient), refusing is 503 secret-store-rejected (permanent)")
    void externalReferencesAndTheSecretStore() throws Exception {
        String ref = "vault://platform/aas/kf-r3/client-secret";
        try (FakePlatform ext = FakePlatform.start("ext-mod").manifest("""
                {"schemaVersion":2,"slug":"ext-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"Ext"}},
                 "capabilities":{
                   "secrets":[{"name":"r3-client-secret","scope":"platform","writeRight":"api.v1.m.ext-mod.keys.put",
                               "external":["platform/aas/"]}],
                   "routes":[{"method":"PUT","path":"/keys","right":"api.v1.m.ext-mod.keys.put","admin":"platform"}]}}""")) {
            ext.secret(ref, "monolith-s3cr3t");
            KfModule mod = ext.module();
            FakePlatform.SignedContext signed = ext.signedContext(TENANT_A, "sub-1", List.of("api.v1.m.ext-mod.keys.put"),
                    "PUT", "/keys");
            RequestContext ctx = mod.verifyContext(signed.context(), signed.timestamp(), signed.signature(),
                    signed.proof(), new byte[0]);

            assertEquals("monolith-s3cr3t", mod.secrets().resolve(ctx, ref).value());

            ext.secretStoreDown(true);
            KfException down = assertThrows(KfException.class, () -> mod.secrets().resolve(ctx, ref));
            assertEquals(503, down.problem().status());
            assertTrue(down.problem().type().endsWith("/secret-store-unreachable"), down.problem().type());
            assertEquals(ErrorClass.TRANSIENT, down.errorClass());
            KfException writeDown = assertThrows(KfException.class,
                    () -> mod.secrets().write(ctx, "r3-client-secret", "v4lue"));
            assertEquals(503, writeDown.problem().status());

            ext.secretStoreRejects(true);
            KfException rejected = assertThrows(KfException.class,
                    () -> mod.secrets().write(ctx, "r3-client-secret", "v4lue"));
            assertEquals(503, rejected.problem().status());
            assertTrue(rejected.problem().type().endsWith("/secret-store-rejected"), rejected.problem().type());
            assertEquals(ErrorClass.PERMANENT, rejected.errorClass());
            assertTrue(!rejected.getMessage().contains("v4lue"), rejected.getMessage());

            ext.secretStoreRejects(false);
            assertEquals("monolith-s3cr3t", mod.secrets().resolve(ctx, ref).value());
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private RequestContext verify(FakePlatform.SignedContext signed) {
        return kf.verifyContext(signed.context(), signed.timestamp(), signed.signature(), signed.proof(), new byte[0]);
    }

    private static PlatformRefusal refusal(org.junit.jupiter.api.function.Executable call) {
        return assertInstanceOf(PlatformRefusal.class, assertThrows(KfException.class, call));
    }
}
