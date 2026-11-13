package ro.kf.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecretsClient} against a platform stub (protocol 1.1 §15.3): {@code write} → the reference,
 * {@code resolve} → the value; a reference outside the module's path or of another tenant is refused locally; the
 * value is in no log (captured at every level), no {@code toString} and no error.
 */
class SecretsClientTest {

    private static final String SECRET = "secrets-secret";
    private static final String TOKEN = "kfm_secrets-token";
    private static final String PROOF = "c".repeat(64);
    private static final String TENANT = "00000000-0000-0000-0000-00000000000a";
    private static final String VALUE = "r3-Pa55-never-logged";
    private static final String REF = "vault://platform/kf-modules/sec-mod/" + TENANT + "/r3-client-secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer platform;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{}";
    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record.getMessage() + " " + java.util.Arrays.toString(record.getParameters())
                    + (record.getThrown() == null ? "" : " " + record.getThrown()));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private Level levelBefore;
    private KfModule kf;

    @BeforeEach
    void start() throws Exception {
        platform = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        platform.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                uri.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        platform.start();
        kf = KfModule.of("http://127.0.0.1:" + platform.getAddress().getPort(), "sec-mod", TOKEN, SECRET, null);
        Logger sdk = Logger.getLogger("ro.kf.sdk");
        levelBefore = sdk.getLevel();
        sdk.setLevel(Level.ALL);
        capture.setLevel(Level.ALL);
        sdk.addHandler(capture);
    }

    @AfterEach
    void stop() {
        Logger sdk = Logger.getLogger("ro.kf.sdk");
        sdk.removeHandler(capture);
        sdk.setLevel(levelBefore);
        platform.stop(0);
        assertTrue(logged.stream().noneMatch(l -> l.contains(VALUE)), "the value in a log line: " + logged);
    }

    private RequestContext verified(String requestId, String proof) {
        long ts = Instant.now().getEpochSecond();
        String json = "{\"v\":1,\"tenant\":\"" + TENANT + "\",\"user\":\"sub-1\",\"locale\":\"en\","
                + "\"rights\":[\"api.v1.m.sec-mod.admin-r3.post\"],\"method\":\"POST\",\"path\":\"/admin/r3\",\"query\":null,"
                + "\"ts\":" + ts + ",\"requestId\":\"" + requestId + "\"}";
        String context = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signature = Signatures.sign(SECRET, String.valueOf(ts), (context + ".").getBytes(StandardCharsets.US_ASCII));
        return kf.verifyContext(context, String.valueOf(ts), signature, proof, new byte[0]);
    }

    @Test
    @DisplayName("write: PUT …/secrets/{name} {value} → the reference; resolve: POST …/secrets/resolve {ref} → the value")
    void writeThenResolve() throws Exception {
        RequestContext ctx = verified("req-1", PROOF);
        answer = "{\"ref\":\"" + REF + "\"}";

        String ref = kf.secrets().write(ctx, "r3-client-secret", VALUE);

        assertEquals(REF, ref);
        assertEquals("PUT /api/v1/platform/modules/sec-mod/secrets/r3-client-secret", uri.get());
        assertEquals(Map.of("value", VALUE), JSON.readValue(body.get(), Map.class));

        answer = "{\"value\":\"" + VALUE + "\"}";
        SecretsClient.Secret secret = kf.secrets().resolve(ctx, ref);

        assertEquals("POST /api/v1/platform/modules/sec-mod/secrets/resolve", uri.get());
        assertEquals(Map.of("ref", REF), JSON.readValue(body.get(), Map.class));
        assertEquals(VALUE, secret.value());
        assertFalse(secret.toString().contains(VALUE), secret.toString());
        assertTrue(secret.toString().contains(REF), secret.toString());
        assertFalse(kf.secrets().toString().contains(VALUE));
    }

    @Test
    @DisplayName("a reference outside the module's path, of another tenant, or malformed: local refusal, no request")
    void foreignReferences_areRefusedLocally() {
        RequestContext ctx = verified("req-2", PROOF);
        for (String ref : List.of(
                "vault://platform/kf-modules/other-mod/" + TENANT + "/r3-client-secret",
                "vault://platform/kf-modules/sec-mod/00000000-0000-0000-0000-00000000000b/r3-client-secret",
                "vault://platform/kf-modules/sec-mod/" + TENANT + "/r3-client-secret/../x",
                "https://platform/kf-modules/sec-mod/platform/r3-client-secret",
                "vault:///kf-modules/sec-mod/platform/r3-client-secret")) {
            KfException e = assertThrows(KfException.class, () -> kf.secrets().resolve(ctx, ref), ref);
            assertEquals(ErrorClass.PERMANENT, e.errorClass(), ref);
            assertEquals(0, e.problem().status(), ref);
        }
        assertEquals(0, requests.get());

        answer = "{\"value\":\"" + VALUE + "\"}";
        assertEquals(VALUE, kf.secrets().resolve(ctx, "vault://kv/platform/kf-modules/sec-mod/platform/r3-client-secret").value());
    }

    @Test
    @DisplayName("the value is in no error: a refusal that echoes it is redacted; the local checks never name it")
    void theValueIsInNoError() {
        RequestContext ctx = verified("req-3", PROOF);
        status = 400;
        answer = "{\"type\":\"https://kf.ro/problems/validation\",\"title\":\"bad " + VALUE + "\",\"status\":400,"
                + "\"detail\":\"value " + VALUE + " is too long\",\"class\":\"permanent\",\"errors\":{\"value\":\"" + VALUE + "\"}}";

        PlatformRefusal refusal = assertInstanceOf(PlatformRefusal.class,
                assertThrows(KfException.class, () -> kf.secrets().write(ctx, "r3-client-secret", VALUE)));
        assertFalse(refusal.getMessage().contains(VALUE), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("value *** is too long"), refusal.getMessage());
        assertFalse(refusal.problem().title().contains(VALUE));
        assertFalse(refusal.errors().toString().contains(VALUE));

        KfException badName = assertThrows(KfException.class, () -> kf.secrets().write(ctx, "R3 secret", VALUE));
        assertFalse(badName.getMessage().contains(VALUE), badName.getMessage());
        KfException empty = assertThrows(KfException.class, () -> kf.secrets().write(ctx, "r3-client-secret", ""));
        assertTrue(empty.getMessage().contains("the value is required"), empty.getMessage());
    }

    @Test
    @DisplayName("403 without the writeRight → FORBIDDEN; with a manifest, an undeclared secret is refused locally")
    void forbiddenAndUndeclared() {
        RequestContext ctx = verified("req-4", PROOF);
        status = 403;
        answer = "{\"type\":\"https://kf.ro/problems/insufficient-rights\",\"status\":403,\"class\":\"permanent\"}";
        PlatformRefusal refusal = assertInstanceOf(PlatformRefusal.class,
                assertThrows(KfException.class, () -> kf.secrets().write(ctx, "r3-client-secret", VALUE)));
        assertEquals(PlatformRefusal.Kind.FORBIDDEN, refusal.kind());
        assertEquals("insufficient-rights", refusal.reason());

        kf.withManifest("""
                {"schemaVersion":2,"slug":"sec-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"S"}},
                 "capabilities":{"secrets":[{"name":"r3-client-secret","scope":"tenant","writeRight":"api.v1.m.sec-mod.admin-r3.post"}]}}""");
        int before = requests.get();
        KfException undeclared = assertThrows(KfException.class, () -> kf.secrets().write(ctx, "nc-token", VALUE));
        assertTrue(undeclared.getMessage().contains("secret nc-token is not declared in capabilities.secrets"), undeclared.getMessage());
        assertEquals(before, requests.get());
    }

    @Test
    @DisplayName("an answer without a reference of this module is an error, never a silent success")
    void answerWithoutReference() {
        RequestContext ctx = verified("req-5", PROOF);
        answer = "{\"ref\":\"vault://platform/kf-modules/other-mod/platform/x1\"}";
        KfException e = assertThrows(KfException.class, () -> kf.secrets().write(ctx, "r3-client-secret", VALUE));
        assertTrue(e.getMessage().contains("no reference of this module"), e.getMessage());
    }

    @Test
    @DisplayName("an existing reference outside the module's path: sent only when a secrets[].external prefix of the "
            + "manifest covers it; otherwise (no manifest, another prefix, a traversal) refused locally")
    void externalReferences() throws Exception {
        RequestContext ctx = verified("req-6", PROOF);
        String external = "vault://platform/aas/kf-r3/client-secret";

        KfException noManifest = assertThrows(KfException.class, () -> kf.secrets().resolve(ctx, external));
        assertEquals(0, noManifest.problem().status());
        assertEquals(0, requests.get());

        kf.withManifest("""
                {"schemaVersion":2,"slug":"sec-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"S"}},
                 "capabilities":{"secrets":[{"name":"r3-client-secret","scope":"platform",
                   "writeRight":"api.v1.m.sec-mod.admin-r3.post","external":["platform/aas/"]}]}}""");
        for (String ref : List.of("vault://platform/idp/keycloak/client-secret",
                "vault://platform/aas/../idp/keycloak/client-secret",
                "vault://platform/aas/",
                "vault://platform/aas//x")) {
            KfException e = assertThrows(KfException.class, () -> kf.secrets().resolve(ctx, ref), ref);
            assertEquals(0, e.problem().status(), ref);
            assertEquals(ErrorClass.PERMANENT, e.errorClass(), ref);
        }
        KfException undeclared = assertThrows(KfException.class,
                () -> kf.secrets().resolve(ctx, "vault://platform/idp/keycloak/client-secret"));
        assertTrue(undeclared.getMessage().contains("is under no prefix declared in capabilities.secrets[].external"),
                undeclared.getMessage());
        assertEquals(0, requests.get());

        answer = "{\"value\":\"" + VALUE + "\"}";
        SecretsClient.Secret secret = kf.secrets().resolve(ctx, external);
        assertEquals("POST /api/v1/platform/modules/sec-mod/secrets/resolve", uri.get());
        assertEquals(Map.of("ref", external), JSON.readValue(body.get(), Map.class));
        assertEquals(VALUE, secret.value());
    }
}
