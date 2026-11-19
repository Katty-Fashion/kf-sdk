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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventsClient} (protocol 2.0, sdk-protocol.md §6.3): the emission {@code POST …/events
 * {name, data}} → {@code 202 {eventId, type}}, the local refusals (an undeclared event, {@code data} over 64 KiB), the
 * platform's {@code 422 event-not-declared}; the subscription {@code m.{owner}.after.{name}} in the registration and its
 * delivery to the handler, with the delivery's context and proof.
 */
class EventsClientTest {

    private static final String SECRET = "events-secret";
    private static final String TOKEN = "kfm_events-token";
    private static final String PROOF = "c".repeat(64);
    private static final String TENANT = "00000000-0000-0000-0000-00000000000b";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer platform;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 202;
    private volatile String answer = "{\"eventId\":\"e-1\",\"type\":\"m.kf-stock.after.level-changed\"}";
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
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        platform.start();
        kf = KfModule.of("http://127.0.0.1:" + platform.getAddress().getPort(), "kf-stock", TOKEN, SECRET, null);
    }

    @AfterEach
    void stop() {
        platform.stop(0);
    }

    private RequestContext verified(String requestId) {
        long ts = Instant.now().getEpochSecond();
        String json = "{\"v\":1,\"tenant\":\"" + TENANT + "\",\"user\":\"sub-1\",\"actor\":{\"type\":\"user\",\"via\":null,"
                + "\"hookId\":null},\"locale\":\"en\",\"rights\":[],\"method\":\"POST\",\"path\":\"/x\",\"query\":null,"
                + "\"ts\":" + ts + ",\"requestId\":\"" + requestId + "\"}";
        String context = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signature = Signatures.sign(SECRET, String.valueOf(ts), (context + ".").getBytes(StandardCharsets.US_ASCII));
        return kf.verifyContext(context, String.valueOf(ts), signature, PROOF, new byte[0]);
    }

    @Test
    @DisplayName("emit: POST …/modules/kf-stock/events {name, data} → 202 {eventId, type}")
    void emit_postsNameAndData() {
        RequestContext ctx = verified("req-1");

        EventsClient.Emitted emitted = kf.events().emit(ctx, "level-changed", Map.of("sku", "KF-42"));

        assertEquals("POST /api/v1/platform/modules/kf-stock/events", uri.get());
        assertEquals("{\"name\":\"level-changed\",\"data\":{\"sku\":\"KF-42\"}}", body.get());
        assertEquals("e-1", emitted.eventId());
        assertEquals("m.kf-stock.after.level-changed", emitted.type());
    }

    @Test
    @DisplayName("emit: an undeclared event, a bad name, data null or over 64 KiB are refused locally")
    void emit_localRefusals() {
        RequestContext ctx = verified("req-2");
        kf.withManifest("{\"schemaVersion\":3,\"slug\":\"kf-stock\",\"capabilities\":{\"events\":"
                + "[{\"name\":\"level-changed\",\"visibility\":\"public\"}]}}");
        KfException undeclared = assertThrows(KfException.class, () -> kf.events().emit(ctx, "other", Map.of()));
        assertTrue(undeclared.getMessage().contains("capabilities.events"), undeclared.getMessage());
        assertThrows(KfException.class, () -> kf.events().emit(ctx, "Bad_Name", Map.of()));
        assertThrows(KfException.class, () -> kf.events().emit(ctx, "level-changed", null));
        KfException big = assertThrows(KfException.class,
                () -> kf.events().emit(ctx, "level-changed", Map.of("x", "y".repeat(EventsClient.MAX_DATA_BYTES))));
        assertTrue(big.getMessage().contains("65536"), big.getMessage());
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("emit: 422 event-not-declared → PlatformRefusal REFUSED, permanent")
    void emit_platformRefusal() {
        RequestContext ctx = verified("req-3");
        status = 422;
        answer = "{\"type\":\"https://kf.ro/problems/event-not-declared\",\"status\":422,\"detail\":\"x\",\"class\":\"permanent\"}";
        PlatformRefusal e = assertThrows(PlatformRefusal.class, () -> kf.events().emit(ctx, "level-changed", Map.of()));
        assertEquals(PlatformRefusal.Kind.REFUSED, e.kind());
        assertEquals("event-not-declared", e.reason());
    }

    @Test
    @DisplayName("on: the subscription is a hook m.{owner}.after.{name}; the delivery reaches the handler with its context")
    void on_deliveryReachesHandlerWithContext() {
        KfModule subscriber = KfModule.of("http://127.0.0.1:1", "kf-orders", TOKEN, SECRET, "http://127.0.0.1:9");
        AtomicReference<HookEvents.ModuleEvent> seen = new AtomicReference<>();
        subscriber.events().on("kf-stock", "level-changed", seen::set);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hooks = (List<Map<String, Object>>) subscriber.registrationBody().get("hooks");
        assertEquals(List.of(Map.of("event", "m.kf-stock.after.level-changed")), hooks);

        long now = Instant.now().getEpochSecond();
        String header = PeersClientTest.deliveryHeader(now, "d-1");
        Map<String, Object> ce = new LinkedHashMap<>();
        ce.put("specversion", "1.0");
        ce.put("id", "d-1");
        ce.put("kfeventid", "e-1");
        ce.put("type", "kf.m.kf-stock.after.level-changed");
        ce.put("source", "/module-event/kf-stock");
        ce.put("subject", "kf-stock");
        ce.put("kftenant", TENANT);
        ce.put("kflocale", "en");
        ce.put("kfaggregatetype", "module-event");
        ce.put("kfenvelopeversion", "1");
        ce.put("data", Map.of("sku", "KF-42"));
        byte[] raw = JSON.writeValueAsBytes(ce);
        String ts = String.valueOf(now);

        HookResult result = subscriber.handle(raw, ts, Signatures.sign(SECRET, ts, raw), header, PROOF);

        assertEquals(200, result.status(), result.body());
        HookEvents.ModuleEvent ev = seen.get();
        assertEquals("kf-stock", ev.owner());
        assertEquals("level-changed", ev.name());
        assertEquals("e-1", ev.eventId());
        assertEquals(Map.of("sku", "KF-42"), ev.data());
        HookEvents.DeliveryContext delivery = ev.deliveryContext().orElseThrow();
        assertEquals(header, delivery.contextHeader());
        assertEquals(PROOF, delivery.proof());
        assertEquals("00000000-0000-0000-0000-00000000000a", delivery.tenant());   // the platform's context, as delivered

        // a platform before 2.0: no headers, no delivery context; an unsubscribed event is ignored
        subscriber.handle(raw, ts, Signatures.sign(SECRET, ts, raw));
        assertTrue(seen.get().deliveryContext().isEmpty());
        ce.put("type", "kf.m.kf-stock.after.other");
        byte[] other = JSON.writeValueAsBytes(ce);
        HookResult ignored = subscriber.handle(other, ts, Signatures.sign(SECRET, ts, other));
        assertTrue(ignored.body().contains("\"ignored\""), ignored.body());
    }
}
