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
 * {@link StorageClient} against a platform stub (protocol 1.2 §3.5): {@code uploadUrl} →
 * {@code {key, url, method, headers, expiresAt}}, {@code downloadUrl} → {@code {url, expiresAt}}, with the token, the
 * context and the proof; a size ≤ 0 or a type the manifest does not declare is refused locally; the platform's refusals
 * are {@link PlatformRefusal}s with their class; neither the token nor the proof is logged.
 */
class StorageClientTest {

    private static final String SECRET = "storage-secret";
    private static final String TOKEN = "kfm_storage-token-never-logged";
    private static final String PROOF = "d".repeat(64);
    private static final String TENANT = "00000000-0000-0000-0000-00000000000a";
    private static final String KEY = "kf-modules/fb-mod/" + TENANT + "/5b1f0c1e-1111-4222-8333-444455556666.png";
    private static final String MANIFEST = """
            {"schemaVersion": 2, "slug": "fb-mod", "version": "0.1.0", "name": "app.name",
             "i18n": {"en": {"app.name": "Feedback"}},
             "capabilities": {"storage": [{"contentTypes": ["image/png", "image/jpeg"], "maxBytes": 5242880}]}}
            """;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer platform;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> headers = new AtomicReference<>();
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
                headers.set(Map.of(
                        "Authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                        "X-KF-Context", String.valueOf(exchange.getRequestHeaders().getFirst("X-KF-Context")),
                        "X-KF-Context-Proof", String.valueOf(exchange.getRequestHeaders().getFirst("X-KF-Context-Proof"))));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        platform.start();
        kf = KfModule.of("http://127.0.0.1:" + platform.getAddress().getPort(), "fb-mod", TOKEN, SECRET, null)
                .withManifest(MANIFEST);
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
        assertTrue(logged.stream().noneMatch(l -> l.contains(TOKEN) || l.contains(PROOF)),
                "the token or the proof in a log line: " + logged);
    }

    private String contextHeader;

    private RequestContext verified(String requestId) {
        long ts = Instant.now().getEpochSecond();
        String json = "{\"v\":1,\"tenant\":\"" + TENANT + "\",\"user\":\"sub-1\",\"locale\":\"en\","
                + "\"rights\":[],\"method\":\"POST\",\"path\":\"/feedback\",\"query\":null,"
                + "\"ts\":" + ts + ",\"requestId\":\"" + requestId + "\"}";
        contextHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signature = Signatures.sign(SECRET, String.valueOf(ts), (contextHeader + ".").getBytes(StandardCharsets.US_ASCII));
        return kf.verifyContext(contextHeader, String.valueOf(ts), signature, PROOF, new byte[0]);
    }

    @Test
    @DisplayName("uploadUrl: POST …/storage/upload-url {contentType, size} with the three headers → {key, url, method, headers, expiresAt}")
    void uploadUrl() throws Exception {
        RequestContext ctx = verified("req-1");
        answer = "{\"key\":\"" + KEY + "\",\"url\":\"https://s3.local/put?sig=1\",\"method\":\"PUT\","
                + "\"headers\":{\"Content-Type\":\"image/png\",\"Content-Length\":\"1234\"},\"expiresAt\":\"2026-10-29T10:10:00Z\"}";

        StorageClient.UploadUrl upload = kf.storage().uploadUrl(ctx, "image/png", 1234);

        assertEquals("POST /api/v1/platform/modules/fb-mod/storage/upload-url", uri.get());
        assertEquals(Map.of("contentType", "image/png", "size", 1234), JSON.readValue(body.get(), Map.class));
        assertEquals(Map.of("Authorization", "Bearer " + TOKEN, "X-KF-Context", contextHeader, "X-KF-Context-Proof", PROOF),
                headers.get());
        assertEquals(KEY, upload.key());
        assertEquals("https://s3.local/put?sig=1", upload.url());
        assertEquals("PUT", upload.method());
        assertEquals(Map.of("Content-Type", "image/png", "Content-Length", "1234"), upload.headers());
        assertEquals("2026-10-29T10:10:00Z", upload.expiresAt());
    }

    @Test
    @DisplayName("uploadUrl: a size of 0 (or less) is refused locally, permanent, nothing sent")
    void sizeZeroRefusedLocally() {
        RequestContext ctx = verified("req-2");

        KfException zero = assertThrows(KfException.class, () -> kf.storage().uploadUrl(ctx, "image/png", 0));
        KfException negative = assertThrows(KfException.class, () -> kf.storage().uploadUrl(ctx, "image/png", -5));

        assertEquals(0, requests.get());
        assertEquals(ErrorClass.PERMANENT, zero.errorClass());
        assertTrue(zero.getMessage().contains("size"), zero.getMessage());
        assertEquals(ErrorClass.PERMANENT, negative.errorClass());
    }

    @Test
    @DisplayName("uploadUrl: a type the manifest does not declare in capabilities.storage is refused locally")
    void undeclaredTypeRefusedLocally() {
        RequestContext ctx = verified("req-3");

        KfException e = assertThrows(KfException.class, () -> kf.storage().uploadUrl(ctx, "application/pdf", 10));

        assertEquals(0, requests.get());
        assertEquals(ErrorClass.PERMANENT, e.errorClass());
        assertTrue(e.getMessage().contains("application/pdf"), e.getMessage());
    }

    @Test
    @DisplayName("uploadUrl: 403 capability-not-declared → PlatformRefusal forbidden, permanent")
    void capabilityNotDeclared() {
        RequestContext ctx = verified("req-4");
        status = 403;
        answer = "{\"type\":\"https://kf.ro/problems/capability-not-declared\",\"title\":\"capability-not-declared\","
                + "\"status\":403,\"detail\":\"the module does not declare capabilities.storage\",\"class\":\"permanent\"}";

        KfException e = assertThrows(KfException.class, () -> kf.storage().uploadUrl(ctx, "image/png", 10));

        PlatformRefusal refusal = assertInstanceOf(PlatformRefusal.class, e);
        assertEquals(PlatformRefusal.Kind.FORBIDDEN, refusal.kind());
        assertEquals("capability-not-declared", refusal.reason());
        assertEquals(ErrorClass.PERMANENT, refusal.errorClass());
        assertFalse(e.getMessage().contains(TOKEN));
        assertFalse(e.getMessage().contains(PROOF));
    }

    @Test
    @DisplayName("uploadUrl: 503 → transient")
    void unavailableIsTransient() {
        RequestContext ctx = verified("req-5");
        status = 503;
        answer = "{\"type\":\"https://kf.ro/problems/storage-unavailable\",\"title\":\"storage-unavailable\",\"status\":503}";

        KfException e = assertThrows(KfException.class, () -> kf.storage().uploadUrl(ctx, "image/png", 10));

        assertEquals(ErrorClass.TRANSIENT, e.errorClass());
        assertEquals(503, e.problem().status());
    }

    @Test
    @DisplayName("downloadUrl: POST …/storage/download-url {key} → {url, expiresAt}; 404 → PlatformRefusal not-found")
    void downloadUrl() throws Exception {
        RequestContext ctx = verified("req-6");
        answer = "{\"url\":\"https://s3.local/get?sig=2\",\"expiresAt\":\"2026-10-29T11:00:00Z\"}";

        StorageClient.DownloadUrl download = kf.storage().downloadUrl(ctx, KEY);

        assertEquals("POST /api/v1/platform/modules/fb-mod/storage/download-url", uri.get());
        assertEquals(Map.of("key", KEY), JSON.readValue(body.get(), Map.class));
        assertEquals("https://s3.local/get?sig=2", download.url());
        assertEquals("2026-10-29T11:00:00Z", download.expiresAt());

        status = 404;
        answer = "{\"type\":\"https://kf.ro/problems/not-found\",\"title\":\"not-found\",\"status\":404,\"class\":\"permanent\"}";
        KfException e = assertThrows(KfException.class, () -> kf.storage().downloadUrl(ctx, KEY));

        PlatformRefusal refusal = assertInstanceOf(PlatformRefusal.class, e);
        assertEquals(404, refusal.problem().status());
        assertEquals(PlatformRefusal.Kind.NOT_FOUND, refusal.kind());
    }

    @Test
    @DisplayName("downloadUrl: an empty key is refused locally")
    void emptyKeyRefusedLocally() {
        RequestContext ctx = verified("req-7");

        KfException e = assertThrows(KfException.class, () -> kf.storage().downloadUrl(ctx, " "));

        assertEquals(0, requests.get());
        assertEquals(ErrorClass.PERMANENT, e.errorClass());
    }
}
