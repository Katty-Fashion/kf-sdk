package ro.kf.modules.apipeercallertest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.ManifestCheck;
import ro.kf.sdk.testing.FakePlatform;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiPeerCallerTestModule} on the SDK's {@link FakePlatform} (plan 230-32): owner present → the read and the
 * command with a key succeed, the command without a key is the platform's 400; owner stopped → {@code /probe} still 200,
 * {@code ownerUnavailable: true} ({@code MODULE_UNAVAILABLE}); the owner's event reaches the handler with the delivery's
 * context, which serves a read as {@code system via module:api-peer-caller-test}.
 */
class ApiPeerCallerTestModuleTest {

    private static final String TENANT = "00000000-0000-0000-0000-0000000000a2";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FakePlatform fake;
    private KfModule kf;
    private HttpServer module;
    private ApiPeerCallerTestModule.State state;
    private FakePlatform.Peer owner;
    private String base;

    @BeforeEach
    void start() throws Exception {
        String manifest = Files.readString(Path.of("..", "kf-module-conf.json"), StandardCharsets.UTF_8);
        fake = FakePlatform.start(ApiPeerCallerTestModule.SLUG).manifest(manifest);
        state = new ApiPeerCallerTestModule.State();
        kf = ApiPeerCallerTestModule.configure(fake.module(), state);
        owner = fake.peer(ApiPeerCallerTestModule.OWNER)
                .read(ApiPeerCallerTestModule.PORT, call -> FakePlatform.PeerReply.ok(Map.of("items",
                        List.of(Map.of("id", "item-1", "name", "Button A")))))
                .command(ApiPeerCallerTestModule.COMMAND, call -> new FakePlatform.PeerReply(201, Map.of("item",
                        Map.of("id", "item-2"), "eventId", "e-1")));
        module = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ApiPeerCallerTestModule.install(module, kf, state, new ApiPeerCallerTestModule.RawPlatform(fake.url(), fake.token()));
        module.start();
        base = "http://127.0.0.1:" + module.getAddress().getPort();
        fake.callbackBase(base);
    }

    @AfterEach
    void stop() {
        module.stop(0);
        fake.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) throws Exception {
        FakePlatform.SignedContext ctx = fake.signedContext(TENANT, "sub-1", List.of(), "GET", path);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
        ctx.applyTo(request);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return JSON.readValue(response.body(), Map.class);
        }
    }

    @Test
    void theManifestPassesTheLocalCheck() throws Exception {
        String manifest = Files.readString(Path.of("..", "kf-module-conf.json"), StandardCharsets.UTF_8);
        var result = ManifestCheck.check(manifest, kf.registrationBody(), java.util.Set.of());
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertTrue(kf.manifest().requires().get(0).optional(), "the owner is an optional dependency");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hooks = (List<Map<String, Object>>) kf.registrationBody().get("hooks");
        assertTrue(hooks.contains(Map.of("event", "m.api-peer-owner-test.after.item-registered")), hooks.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ownerPresent_readAndCommandWithKeySucceed_withoutKeyIs400() throws Exception {
        Map<String, Object> probe = get("/probe");
        Map<String, Object> read = (Map<String, Object>) probe.get("read");
        assertEquals(200, read.get("status"), probe.toString());
        assertEquals(201, ((Map<String, Object>) probe.get("commandWithKey")).get("status"), probe.toString());
        Map<String, Object> withoutKey = (Map<String, Object>) probe.get("commandWithoutKey");
        assertEquals(400, withoutKey.get("status"), probe.toString());
        assertEquals("idempotency-key-required", withoutKey.get("reason"), probe.toString());
        assertEquals(false, probe.get("ownerUnavailable"));
        assertEquals("module:api-peer-caller-test", owner.calls().get(0).via());
        assertTrue(owner.calls().stream().anyMatch(c -> c.kind().equals("command") && c.idempotencyKey() != null));
        assertEquals("Button A", ((Map<String, Object>) get("/latest").get("item")).get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ownerStopped_probeStill200_ownerUnavailable() throws Exception {
        owner.stopped(true);
        Map<String, Object> probe = get("/probe");
        assertEquals(true, probe.get("ownerUnavailable"), probe.toString());
        Map<String, Object> read = (Map<String, Object>) probe.get("read");
        assertEquals("module-unavailable", read.get("reason"));
        assertEquals("unavailable", read.get("kind"));
        assertEquals(503, read.get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theOwnersEventReachesTheHandler_theDeliveryContextServesARead() throws Exception {
        FakePlatform.Delivery delivery = fake.deliverModuleEvent(ApiPeerCallerTestModule.OWNER, ApiPeerCallerTestModule.EVENT,
                TENANT, Map.of("id", "item-9", "name", "Button Z"));
        assertEquals(200, delivery.status(), delivery.body());
        Map<String, Object> last = state.lastEvent.get();
        assertNotNull(last);
        assertEquals(delivery.eventId(), last.get("eventId"));
        assertEquals(true, last.get("withDeliveryContext"));
        assertEquals(200, state.lastJobRead.get().get("status"), String.valueOf(state.lastJobRead.get()));
        FakePlatform.PeerCall job = owner.calls().get(owner.calls().size() - 1);
        assertEquals("system", job.actorType());
        assertEquals("module:api-peer-caller-test", job.via());
        Map<String, Object> probe = get("/probe");
        assertEquals(delivery.eventId(), ((Map<String, Object>) probe.get("lastEvent")).get("eventId"));
    }
}
