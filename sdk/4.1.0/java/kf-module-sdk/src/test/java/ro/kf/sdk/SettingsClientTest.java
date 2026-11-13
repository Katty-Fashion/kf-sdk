package ro.kf.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SettingsClient} against a platform stub (protocol 1.1 §15.2): {@code GET …/settings} with the
 * token, the context and the proof; the values typed by the manifest; an undeclared key ignored with a WARN; a missing
 * or mismatching value takes the default; the same local refusals as {@link ReadsClient}.
 */
class SettingsClientTest {

    private static final String SECRET = "settings-secret";
    private static final String TOKEN = "kfm_settings-token";
    private static final String PROOF = "b".repeat(64);
    private static final String MANIFEST = """
            {"schemaVersion":2,"slug":"set-mod","name":"n","version":"1.0.0",
             "i18n":{"en":{"n":"Settings","l":"Label"}},
             "capabilities":{"settings":[
               {"key":"live","type":"BOOLEAN","default":false,"label":"l"},
               {"key":"batch-size","type":"NUMBER","default":50,"label":"l","min":1,"max":500},
               {"key":"push-mode","type":"ENUM","default":"manual","label":"l","values":["manual","auto"]}]}}""";

    private HttpServer platform;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{}";
    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record.getLevel() + " " + java.text.MessageFormat.format(record.getMessage(),
                    record.getParameters() == null ? new Object[0] : record.getParameters()));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private KfModule kf;

    @BeforeEach
    void start() throws Exception {
        platform = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        platform.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                uri.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                headers.set(Map.copyOf(exchange.getRequestHeaders()));
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        platform.start();
        kf = KfModule.of("http://127.0.0.1:" + platform.getAddress().getPort(), "set-mod", TOKEN, SECRET, null)
                .withManifest(MANIFEST);
        Logger.getLogger("ro.kf.sdk").addHandler(capture);
    }

    @AfterEach
    void stop() {
        Logger.getLogger("ro.kf.sdk").removeHandler(capture);
        platform.stop(0);
    }

    private RequestContext verified(String requestId, String proof) {
        long ts = Instant.now().getEpochSecond();
        String json = "{\"v\":1,\"tenant\":\"00000000-0000-0000-0000-00000000000a\",\"user\":\"sub-1\",\"locale\":\"en\","
                + "\"rights\":[],\"method\":\"GET\",\"path\":\"/x\",\"query\":null,\"ts\":" + ts
                + ",\"requestId\":\"" + requestId + "\"}";
        String context = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signature = Signatures.sign(SECRET, String.valueOf(ts), (context + ".").getBytes(StandardCharsets.US_ASCII));
        return kf.verifyContext(context, String.valueOf(ts), signature, proof, new byte[0]);
    }

    private String header(String name) {
        return headers.get().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                .map(e -> e.getValue().getFirst()).orElse(null);
    }

    @Test
    @DisplayName("effective: GET …/settings with the token, the context and the proof → Boolean, BigDecimal, String")
    void effective_typesTheValuesByTheManifest() {
        RequestContext ctx = verified("req-1", PROOF);
        answer = "{\"live\":true,\"batch-size\":120.50,\"push-mode\":\"auto\"}";

        Map<String, Object> settings = kf.settings().effective(ctx);

        assertEquals("GET /api/v1/platform/modules/set-mod/settings", uri.get());
        assertEquals("Bearer " + TOKEN, header("Authorization"));
        assertEquals(PROOF, header("X-KF-Context-Proof"));
        assertEquals(List.of("live", "batch-size", "push-mode"), List.copyOf(settings.keySet()), "the manifest order");
        assertEquals(Boolean.TRUE, settings.get("live"));
        assertEquals(new BigDecimal("120.50"), settings.get("batch-size"));
        assertEquals("auto", settings.get("push-mode"));
        assertTrue(logged.isEmpty(), logged.toString());
    }

    @Test
    @DisplayName("an undeclared key is ignored with a WARN; a missing, mistyped or out-of-range value takes the default")
    void undeclaredIgnored_mismatchTakesTheDefault() {
        RequestContext ctx = verified("req-2", PROOF);
        answer = "{\"live\":\"yes\",\"batch-size\":900,\"retired\":1}";

        Map<String, Object> settings = kf.settings().effective(ctx);

        assertEquals(Boolean.FALSE, settings.get("live"));
        assertEquals(new BigDecimal("50"), settings.get("batch-size"), "900 is above max 500");
        assertEquals("manual", settings.get("push-mode"), "missing from the answer");
        assertTrue(logged.stream().anyMatch(l -> l.startsWith("WARNING") && l.contains("setting retired is not declared")), logged.toString());
        assertTrue(logged.stream().anyMatch(l -> l.contains("setting live does not match its declaration (BOOLEAN)")), logged.toString());
        assertTrue(logged.stream().anyMatch(l -> l.contains("setting push-mode is missing")), logged.toString());
    }

    @Test
    @DisplayName("without the manifest, an unknown context or a context without proof: local refusal, no request")
    void localRefusals() {
        RequestContext ctx = verified("req-3", PROOF);
        KfModule bare = KfModule.of("http://127.0.0.1:" + platform.getAddress().getPort(), "set-mod", TOKEN, SECRET, null);
        KfException noManifest = assertThrows(KfException.class, () -> bare.settings().effective(ctx));
        assertTrue(noManifest.getMessage().contains("settings need the module manifest"), noManifest.getMessage());

        RequestContext withoutProof = verified("req-4", null);
        KfException noProof = assertThrows(KfException.class, () -> kf.settings().effective(withoutProof));
        assertTrue(noProof.getMessage().contains("X-KF-Context-Proof"), noProof.getMessage());

        RequestContext built = new RequestContext(1, "t", "u", RequestContext.Actor.USER, "en", List.of(), "GET", "/x",
                null, Instant.now().getEpochSecond(), "never-verified");
        KfException unknown = assertThrows(KfException.class, () -> kf.settings().effective(built));
        assertTrue(unknown.getMessage().contains("was not verified by this module"), unknown.getMessage());
        for (KfException e : List.of(noManifest, noProof, unknown)) assertEquals(ErrorClass.PERMANENT, e.errorClass());
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("403 module-not-live → PlatformRefusal FORBIDDEN with the reason")
    void forbidden() {
        RequestContext ctx = verified("req-5", PROOF);
        status = 403;
        answer = "{\"type\":\"https://kf.ro/problems/module-not-live\",\"status\":403,\"detail\":\"not live\",\"class\":\"permanent\"}";

        PlatformRefusal refusal = assertInstanceOf(PlatformRefusal.class,
                assertThrows(KfException.class, () -> kf.settings().effective(ctx)));
        assertEquals(PlatformRefusal.Kind.FORBIDDEN, refusal.kind());
        assertEquals("module-not-live", refusal.reason());
    }
}
