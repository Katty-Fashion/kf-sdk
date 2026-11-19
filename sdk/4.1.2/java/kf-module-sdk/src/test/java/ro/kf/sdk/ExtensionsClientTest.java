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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExtensionsClient} against a platform stub (protocol 1.1 §15.4): {@code PATCH
 * …/extensions/{entity}/{id}} with exactly the given keys ({@code null} kept: it clears); {@code 400} with
 * {@code errors["extensions.<slug>.<field>"]} names the field; {@code 404} is NOT_FOUND; the local rules refuse first.
 */
class ExtensionsClientTest {

    private static final String SECRET = "ext-secret";
    private static final String TOKEN = "kfm_ext-token";
    private static final String PROOF = "d".repeat(64);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer platform;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> proof = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{}";
    private KfModule kf;

    @BeforeEach
    void start() throws Exception {
        platform = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        platform.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                uri.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                proof.set(exchange.getRequestHeaders().getFirst("X-KF-Context-Proof"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        platform.start();
        kf = KfModule.of("http://127.0.0.1:" + platform.getAddress().getPort(), "ext-mod", TOKEN, SECRET, null);
    }

    @AfterEach
    void stop() {
        platform.stop(0);
    }

    private RequestContext verified(String requestId) {
        long ts = Instant.now().getEpochSecond();
        String json = "{\"v\":1,\"tenant\":\"00000000-0000-0000-0000-00000000000a\",\"user\":\"sub-1\",\"locale\":\"en\","
                + "\"rights\":[],\"method\":\"POST\",\"path\":\"/push\",\"query\":null,\"ts\":" + ts
                + ",\"requestId\":\"" + requestId + "\"}";
        String context = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signature = Signatures.sign(SECRET, String.valueOf(ts), (context + ".").getBytes(StandardCharsets.US_ASCII));
        return kf.verifyContext(context, String.valueOf(ts), signature, PROOF, new byte[0]);
    }

    @Test
    @DisplayName("write: PATCH …/extensions/model_version/17 with exactly the two given keys, null kept → the module's values")
    void write_sendsExactlyTheGivenKeys() throws Exception {
        RequestContext ctx = verified("req-1");
        answer = "{\"aas_shell\":\"X\",\"aas_last_push\":null}";
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("aas_shell", "X");
        values.put("aas_last_push", null);

        Map<String, Object> written = kf.extensions().write(ctx, "model_version", "17", values);

        assertEquals("PATCH /api/v1/platform/modules/ext-mod/extensions/model_version/17", uri.get());
        assertEquals(PROOF, proof.get());
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = JSON.readValue(body.get(), Map.class);
        assertEquals(List.of("aas_shell", "aas_last_push"), List.copyOf(sent.keySet()), "exactly the given keys, in order");
        assertEquals("X", sent.get("aas_shell"));
        assertTrue(sent.containsKey("aas_last_push"));
        assertNull(sent.get("aas_last_push"), "null is sent: it clears");
        assertEquals("X", written.get("aas_shell"));
    }

    @Test
    @DisplayName("400 with errors[\"extensions.<slug>.<field>\"] → INVALID naming the field; 404 → NOT_FOUND")
    void platformRefusals() {
        RequestContext ctx = verified("req-2");
        status = 400;
        answer = "{\"type\":\"https://kf.ro/problems/validation\",\"status\":400,\"detail\":\"invalid extension values\","
                + "\"class\":\"permanent\",\"errors\":{\"extensions.ext-mod.aas_shell\":\"must be text\"}}";
        PlatformRefusal invalid = assertInstanceOf(PlatformRefusal.class,
                assertThrows(KfException.class, () -> kf.extensions().write(ctx, "model_version", "17", Map.of("aas_shell", 12))));
        assertEquals(PlatformRefusal.Kind.INVALID, invalid.kind());
        assertEquals(Map.of("extensions.ext-mod.aas_shell", "must be text"), invalid.errors());
        assertTrue(invalid.getMessage().contains("extensions.ext-mod.aas_shell: must be text"), invalid.getMessage());

        status = 404;
        answer = "{\"type\":\"https://kf.ro/problems/not-found\",\"status\":404,\"class\":\"permanent\"}";
        PlatformRefusal notFound = assertInstanceOf(PlatformRefusal.class,
                assertThrows(KfException.class, () -> kf.extensions().write(ctx, "order", "o 9", Map.of("p", "A"))));
        assertEquals(PlatformRefusal.Kind.NOT_FOUND, notFound.kind());
        assertEquals("PATCH /api/v1/platform/modules/ext-mod/extensions/order/o%209", uri.get());
    }

    @Test
    @DisplayName("the local rules: the entity, the id, at least one value, the field names, the declared fields")
    void localRules() {
        RequestContext ctx = verified("req-3");
        assertTrue(assertThrows(KfException.class, () -> kf.extensions().write(ctx, "supplier", "1", Map.of("a", "b")))
                .getMessage().contains("entity must be one of model, order, customer, model_version"));
        assertTrue(assertThrows(KfException.class, () -> kf.extensions().write(ctx, "model", " ", Map.of("a", "b")))
                .getMessage().contains("id is required"));
        assertTrue(assertThrows(KfException.class, () -> kf.extensions().write(ctx, "model", "1", Map.of()))
                .getMessage().contains("at least one value is required"));
        assertTrue(assertThrows(KfException.class, () -> kf.extensions().write(ctx, "model", "1", Map.of("Bad-Name", "x")))
                .getMessage().contains("field Bad-Name must match"));

        kf.withManifest("""
                {"schemaVersion":2,"slug":"ext-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"E","f":"F"}},
                 "capabilities":{"fields":[{"entity":"model_version","name":"aas_shell","type":"STRING","table":"t","label":"f"}]}}""");
        assertTrue(assertThrows(KfException.class, () -> kf.extensions().write(ctx, "model", "1", Map.of("aas_shell", "x")))
                .getMessage().contains("aas_shell is not declared in capabilities.fields for model"));
        assertEquals(0, requests.get());

        answer = "{\"aas_shell\":\"x\"}";
        assertEquals("x", kf.extensions().write(ctx, "model_version", "1", Map.of("aas_shell", "x")).get("aas_shell"));
    }
}
