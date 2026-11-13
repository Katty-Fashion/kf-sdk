package ro.kf.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PeersClient} against a platform stub (protocol 2.0, sdk-protocol.md §15.9): the token, the
 * context and its proof go back unchanged; a command carries {@code Idempotency-Key}, refused locally when missing or
 * invalid; the owner's answer passes unchanged; the platform's refusals (also 409, 422, 502, 503) are
 * {@link PlatformRefusal}s with their reason and class; an after delivery's context serves a background job within
 * ±300 s.
 */
class PeersClientTest {

    private static final String SECRET = "peers-secret";
    private static final String TOKEN = "kfm_peers-token";
    private static final String PROOF = "b".repeat(64);
    private static final String TENANT = "00000000-0000-0000-0000-00000000000a";

    private HttpServer platform;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{}";
    private KfModule kf;

    @BeforeEach
    void start() throws Exception {
        platform = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        platform.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                method.set(exchange.getRequestMethod());
                uri.set(exchange.getRequestURI().toString());
                headers.set(Map.copyOf(exchange.getRequestHeaders()));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type",
                        status >= 400 ? "application/problem+json" : "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        platform.start();
        kf = KfModule.of("http://127.0.0.1:" + platform.getAddress().getPort(), "caller", TOKEN, SECRET, null);
    }

    @AfterEach
    void stop() {
        platform.stop(0);
    }

    private RequestContext verified(String requestId) {
        long ts = Instant.now().getEpochSecond();
        String context = encode(ts, requestId, "\"user\":\"sub-1\",\"actor\":{\"type\":\"user\",\"via\":null,\"hookId\":null}");
        String signature = Signatures.sign(SECRET, String.valueOf(ts), (context + ".").getBytes(StandardCharsets.US_ASCII));
        return kf.verifyContext(context, String.valueOf(ts), signature, PROOF, new byte[0]);
    }

    /** The context the platform puts on an after delivery: system via hook, {@code ts} = the attempt. */
    static String deliveryHeader(long ts, String requestId) {
        return encode(ts, requestId, "\"user\":null,\"actor\":{\"type\":\"system\",\"via\":\"hook\",\"hookId\":\""
                + requestId + "\"}");
    }

    private static String encode(long ts, String requestId, String userAndActor) {
        String json = "{\"v\":1,\"tenant\":\"" + TENANT + "\"," + userAndActor + ",\"locale\":\"en\",\"rights\":[],"
                + "\"method\":\"POST\",\"path\":\"/_kf/hooks\",\"query\":null,\"ts\":" + ts + ",\"requestId\":\""
                + requestId + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private String header(String name) {
        return headers.get().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                .map(e -> e.getValue().getFirst()).orElse(null);
    }

    @Test
    @DisplayName("read: GET …/modules/caller/peers/kf-stock/reads/{port}?query with the token, the context and the proof → the owner's answer")
    void read_sendsTokenContextProof_returnsTheOwnersAnswer() {
        RequestContext ctx = verified("req-1");
        answer = "{\"sku\":\"KF-42\",\"level\":7.50}";
        Map<String, String> query = new LinkedHashMap<>();
        query.put("sku", "KF 42");

        PeersClient.Answer a = kf.peers().read(ctx, "kf-stock", "m.kf-stock.level.v1", query);

        assertEquals(1, requests.get());
        assertEquals("GET", method.get());
        assertEquals("/api/v1/platform/modules/caller/peers/kf-stock/reads/m.kf-stock.level.v1?sku=KF+42", uri.get());
        assertEquals("Bearer " + TOKEN, header("Authorization"));
        assertEquals(PROOF, header("X-KF-Context-Proof"));
        assertTrue(header("X-KF-Context") != null);
        assertEquals(200, a.status());
        assertEquals("KF-42", a.object().get("sku"));
        assertEquals(new java.math.BigDecimal("7.50"), a.object().get("level"));
    }

    @Test
    @DisplayName("read: 503 module-unavailable transient → PlatformRefusal UNAVAILABLE, class transient")
    void read_ownerUnavailable_isTransientRefusal() {
        RequestContext ctx = verified("req-2");
        status = 503;
        answer = "{\"type\":\"https://kf.ro/problems/module-unavailable\",\"title\":\"Module unavailable\",\"status\":503,"
                + "\"detail\":\"The owner is not available now.\",\"class\":\"transient\"}";

        PlatformRefusal e = assertThrows(PlatformRefusal.class,
                () -> kf.peers().read(ctx, "kf-stock", "m.kf-stock.level.v1", Map.of()));

        assertEquals(PlatformRefusal.Kind.UNAVAILABLE, e.kind());
        assertEquals(PeersClient.MODULE_UNAVAILABLE, e.reason());
        assertEquals(ErrorClass.TRANSIENT, e.errorClass());
        assertFalse(e.getMessage().contains(PROOF));
    }

    @Test
    @DisplayName("read: 403 peer-not-required, 404 not-configured, 422 module-refused → refusals with the reason")
    void read_refusals_carryTheReason() {
        RequestContext ctx = verified("req-3");
        for (Object[] c : new Object[][]{{403, "peer-not-required", PlatformRefusal.Kind.FORBIDDEN},
                {404, PeersClient.NOT_CONFIGURED, PlatformRefusal.Kind.NOT_FOUND},
                {422, PeersClient.MODULE_REFUSED, PlatformRefusal.Kind.REFUSED},
                {502, PeersClient.INVALID_ANSWER, PlatformRefusal.Kind.INVALID_ANSWER}}) {
            status = (Integer) c[0];
            answer = "{\"type\":\"https://kf.ro/problems/" + c[1] + "\",\"status\":" + c[0] + ",\"detail\":\"x\",\"class\":\"permanent\"}";
            PlatformRefusal e = assertThrows(PlatformRefusal.class,
                    () -> kf.peers().read(ctx, "kf-stock", "m.kf-stock.level.v1", Map.of()));
            assertEquals(c[1], e.reason());
            assertEquals(c[2], e.kind());
            assertEquals(ErrorClass.PERMANENT, e.errorClass());
        }
    }

    @Test
    @DisplayName("read: an owner or a port outside the grammar is refused locally, nothing sent")
    void read_badOwnerOrPort_refusedLocally() {
        RequestContext ctx = verified("req-4");
        KfException a = assertThrows(KfException.class, () -> kf.peers().read(ctx, "Kf_Stock", "m.kf-stock.level.v1", null));
        KfException b = assertThrows(KfException.class, () -> kf.peers().read(ctx, "kf-stock", "m.other.level.v1", null));
        assertEquals(0, a.problem().status());
        assertTrue(b.getMessage().contains("m.kf-stock.{name}.v{N}"), b.getMessage());
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("command: POST …/commands/{name} with Idempotency-Key and the JSON body; 201 passes unchanged")
    void command_sendsKeyAndBody() {
        RequestContext ctx = verified("req-5");
        status = 201;
        answer = "{\"reservationId\":\"r-1\"}";

        PeersClient.Answer a = kf.peers().command(ctx, "kf-stock", "reserve", "key-0001-abcdef", Map.of("qty", 3));

        assertEquals("POST", method.get());
        assertEquals("/api/v1/platform/modules/caller/peers/kf-stock/commands/reserve", uri.get());
        assertEquals("key-0001-abcdef", header("Idempotency-Key"));
        assertEquals("application/json", header("Content-Type"));
        assertEquals("{\"qty\":3}", body.get());
        assertEquals(201, a.status());
        assertEquals("r-1", a.object().get("reservationId"));
    }

    @Test
    @DisplayName("command: a missing or invalid Idempotency-Key is refused locally, before the network")
    void command_keyMissingOrInvalid_refusedLocally() {
        RequestContext ctx = verified("req-6");
        KfException missing = assertThrows(KfException.class, () -> kf.peers().command(ctx, "kf-stock", "reserve", null, Map.of()));
        KfException shortKey = assertThrows(KfException.class, () -> kf.peers().command(ctx, "kf-stock", "reserve", "short", Map.of()));
        KfException badChars = assertThrows(KfException.class,
                () -> kf.peers().command(ctx, "kf-stock", "reserve", "key with spaces", Map.of()));
        for (KfException e : List.of(missing, shortKey, badChars)) {
            assertEquals(0, e.problem().status());
            assertEquals(ErrorClass.PERMANENT, e.errorClass());
            assertTrue(e.getMessage().contains("Idempotency-Key"), e.getMessage());
        }
        assertEquals(0, requests.get());
        assertTrue(PeersClient.IDEMPOTENCY_KEY.matcher(PeersClient.newIdempotencyKey()).matches());
    }

    @Test
    @DisplayName("command: 409 idempotency-conflict permanent, idempotency-in-progress transient")
    void command_conflicts() {
        RequestContext ctx = verified("req-7");
        status = 409;
        answer = "{\"type\":\"https://kf.ro/problems/idempotency-conflict\",\"status\":409,\"detail\":\"x\",\"class\":\"permanent\"}";
        PlatformRefusal conflict = assertThrows(PlatformRefusal.class,
                () -> kf.peers().command(ctx, "kf-stock", "reserve", "key-0001-abcdef", Map.of("qty", 4)));
        assertEquals(PlatformRefusal.Kind.CONFLICT, conflict.kind());
        assertEquals(PeersClient.IDEMPOTENCY_CONFLICT, conflict.reason());
        assertEquals(ErrorClass.PERMANENT, conflict.errorClass());

        answer = "{\"type\":\"https://kf.ro/problems/idempotency-in-progress\",\"status\":409,\"detail\":\"x\",\"class\":\"transient\"}";
        PlatformRefusal running = assertThrows(PlatformRefusal.class,
                () -> kf.peers().command(ctx, "kf-stock", "reserve", "key-0001-abcdef", Map.of("qty", 4)));
        assertEquals(ErrorClass.TRANSIENT, running.errorClass());
    }

    @Test
    @DisplayName("the manifest's capabilities.peers must name the owner and the port or command (when known)")
    void undeclared_refusedLocally() {
        kf.withManifest("{\"schemaVersion\":3,\"slug\":\"caller\",\"requires\":[{\"slug\":\"kf-stock\",\"versionRange\":\">=1.0.0\"}],"
                + "\"capabilities\":{\"peers\":[{\"owner\":\"kf-stock\",\"reads\":[\"m.kf-stock.level.v1\"],\"commands\":[\"reserve\"]}]}}");
        RequestContext ctx = verified("req-8");
        KfException read = assertThrows(KfException.class, () -> kf.peers().read(ctx, "kf-stock", "m.kf-stock.other.v1", null));
        KfException command = assertThrows(KfException.class,
                () -> kf.peers().command(ctx, "kf-stock", "release", "key-0001-abcdef", Map.of()));
        assertTrue(read.getMessage().contains("capabilities.peers"), read.getMessage());
        assertTrue(command.getMessage().contains("capabilities.peers"), command.getMessage());
        assertEquals(0, requests.get());
        kf.peers().read(ctx, "kf-stock", "m.kf-stock.level.v1", null);
        assertEquals(1, requests.get());
    }

    @Test
    @DisplayName(": a delivery's context serves a background job within ±300 s, refused locally after")
    void deliveryContext_window() {
        long now = Instant.now().getEpochSecond();
        String header = deliveryHeader(now, "11111111-2222-3333-4444-555555555555");
        HookEvents.DeliveryContext fresh = HookEvents.DeliveryContext.of(header, PROOF);

        kf.peers().read(fresh, "kf-stock", "m.kf-stock.level.v1", Map.of());

        assertEquals(header, header("X-KF-Context"));
        assertEquals(PROOF, header("X-KF-Context-Proof"));
        assertEquals(TENANT, fresh.tenant());
        assertEquals(RequestContext.Actor.Type.SYSTEM, fresh.context().actor().type());
        assertFalse(fresh.toString().contains(PROOF));

        HookEvents.DeliveryContext old = HookEvents.DeliveryContext.of(deliveryHeader(now - 301, "x-old"), PROOF);
        KfException e = assertThrows(KfException.class,
                () -> kf.peers().command(old, "kf-stock", "reserve", "key-0001-abcdef", Map.of()));
        assertTrue(e.getMessage().contains("300 s"), e.getMessage());
        assertEquals(1, requests.get());
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(RuntimeException.class, () -> HookEvents.DeliveryContext.of(header, null)));
    }

    @Test
    @DisplayName("command: an empty 2xx is an answer without a body")
    void anEmpty2xxIsAnAnswerWithoutABody() {
        RequestContext ctx = verified("req-8");
        status = 204;
        answer = "";
        PeersClient.Answer a = kf.peers().command(ctx, "kf-stock", "reserve", "key-0001-abcdef", Map.of("qty", 3));
        assertEquals(204, a.status());
        assertEquals(null, a.body());
        KfException notAnObject = assertThrows(KfException.class, a::object);
        assertEquals(ErrorClass.PERMANENT, notAnObject.errorClass());
    }

    @Test
    @DisplayName("command: 409 idempotency-answer-not-stored is a permanent conflict refusal")
    void aReplayWhoseAnswerWasNotStoredIsAConflictRefusal() {
        RequestContext ctx = verified("req-9");
        status = 409;
        answer = "{\"type\":\"https://kf.ro/problems/idempotency-answer-not-stored\",\"status\":409,\"detail\":\"x\",\"class\":\"permanent\"}";
        PlatformRefusal refusal = assertThrows(PlatformRefusal.class,
                () -> kf.peers().command(ctx, "kf-stock", "reserve", "key-0001-abcdef", Map.of("qty", 3)));
        assertEquals(PlatformRefusal.Kind.CONFLICT, refusal.kind());
        assertEquals(PeersClient.IDEMPOTENCY_ANSWER_NOT_STORED, refusal.reason());
        assertEquals(ErrorClass.PERMANENT, refusal.errorClass());
    }

    @Test
    @DisplayName("command: 502 invalid-answer permanent is an INVALID_ANSWER refusal")
    void anInvalidAnswerIsAPermanentRefusal() {
        RequestContext ctx = verified("req-10");
        status = 502;
        answer = "{\"type\":\"https://kf.ro/problems/invalid-answer\",\"status\":502,\"detail\":\"x\",\"class\":\"permanent\"}";
        PlatformRefusal refusal = assertThrows(PlatformRefusal.class,
                () -> kf.peers().command(ctx, "kf-stock", "reserve", "key-0001-abcdef", Map.of("qty", 3)));
        assertEquals(PlatformRefusal.Kind.INVALID_ANSWER, refusal.kind());
        assertEquals(PeersClient.INVALID_ANSWER, refusal.reason());
        assertEquals(ErrorClass.PERMANENT, refusal.errorClass());
    }
}
