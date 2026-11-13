package ro.kf.sdk.testing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.sdk.ErrorClass;
import ro.kf.sdk.EventsClient;
import ro.kf.sdk.HookEvents;
import ro.kf.sdk.KfHttpServer;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.PeersClient;
import ro.kf.sdk.PlatformRefusal;
import ro.kf.sdk.RequestContext;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FakePlatform} 0.10.0, protocol 2.0: the calls between modules of §15.9 (the checks in the
 * platform's order, the owner's answer mapped, the idempotency key replayed or in conflict) and the module events of
 * §6.3 (the emission checked and kept, an owner's event delivered with the context, which then serves a
 * background job's call to the owner as {@code system via module:{caller}}).
 */
class FakePlatformPeersTest {

    private static final String TENANT = "00000000-0000-0000-0000-00000000000a";
    private static final String MANIFEST = """
            {"schemaVersion":3,"slug":"kf-orders","name":"n","version":"1.0.0",
             "i18n":{"en":{"n":"Orders"}},
             "requires":[{"slug":"kf-stock","versionRange":">=1.0.0"}],
             "capabilities":{
               "peers":[{"owner":"kf-stock","reads":["m.kf-stock.level.v1","m.kf-stock.secret.v1"],"commands":["reserve"]},
                        {"owner":"kf-other","reads":["m.kf-other.x.v1"]}],
               "events":[{"name":"order-placed","visibility":"public"}],
               "hooks":[{"point":"m.kf-stock.after.level-changed"}]}}""";

    private FakePlatform fake;
    private KfModule kf;
    private FakePlatform.Peer stock;

    @BeforeEach
    void start() throws Exception {
        fake = FakePlatform.start("kf-orders").manifest(MANIFEST);
        kf = fake.module();
        stock = fake.peer("kf-stock")
                .read("m.kf-stock.level.v1", call -> FakePlatform.PeerReply.ok(Map.of("sku", call.query().get("sku"), "level", 7)))
                .read("m.kf-stock.secret.v1", "internal", null, call -> FakePlatform.PeerReply.ok(Map.of("x", 1)))
                .command("reserve", call -> new FakePlatform.PeerReply(201, Map.of("reserved", call.body())));
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private RequestContext user(String subject) {
        FakePlatform.SignedContext s = fake.signedContext(TENANT, subject, List.of(), "GET", "/x");
        return kf.verifyContext(s.context(), s.timestamp(), s.signature(), s.proof(), new byte[0]);
    }

    @Test
    @DisplayName("a read reaches the owner as the user via module:{caller}; the answer passes unchanged")
    void read_ok() {
        PeersClient.Answer a = kf.peers().read(user("sub-1"), "kf-stock", "m.kf-stock.level.v1", Map.of("sku", "KF-42"));

        assertEquals(200, a.status());
        assertEquals(Map.of("sku", "KF-42", "level", 7), a.object());
        FakePlatform.PeerCall call = stock.calls().getFirst();
        assertEquals("user", call.actorType());
        assertEquals("sub-1", call.user());
        assertEquals("module:kf-orders", call.via());
        assertEquals(TENANT, call.tenant());
    }

    @Test
    @DisplayName("the platform's order: not declared 403, not required 403, stopped 503 transient, absent 503 permanent, internal 404")
    void refusals_inOrder() {
        KfModule raw = KfModule.of(fake.url(), fake.slug(), fake.token(), fake.hookSecret(), null);   // no local check
        FakePlatform.SignedContext s = fake.signedContext(TENANT, "sub-1", List.of(), "GET", "/x");
        RequestContext ctx = raw.verifyContext(s.context(), s.timestamp(), s.signature(), s.proof(), new byte[0]);

        assertEquals("capability-not-declared", refusal(() -> raw.peers().read(ctx, "kf-stock", "m.kf-stock.other.v1", null)).reason());
        assertEquals("peer-not-required", refusal(() -> raw.peers().read(ctx, "kf-other", "m.kf-other.x.v1", null)).reason());

        stock.stopped(true);
        PlatformRefusal stopped = refusal(() -> raw.peers().read(ctx, "kf-stock", "m.kf-stock.level.v1", null));
        assertEquals("module-unavailable", stopped.reason());
        assertEquals(ErrorClass.TRANSIENT, stopped.errorClass());
        stock.stopped(false).absent(true);
        PlatformRefusal absent = refusal(() -> raw.peers().read(ctx, "kf-stock", "m.kf-stock.level.v1", null));
        assertEquals(ErrorClass.PERMANENT, absent.errorClass());
        stock.absent(false);

        PlatformRefusal internal = refusal(() -> raw.peers().read(ctx, "kf-stock", "m.kf-stock.secret.v1", null));
        assertEquals(404, internal.problem().status());
        assertEquals("peer-port-unknown", internal.reason());
        fake.internalClass(true);
        assertEquals(200, raw.peers().read(ctx, "kf-stock", "m.kf-stock.secret.v1", null).status());
    }

    @Test
    @DisplayName("the owner's 404 → not-configured, 4xx → 422 module-refused, a thrown handler → 503 transient")
    void ownerAnswersMapped() {
        RequestContext ctx = user("sub-1");
        stock.read("m.kf-stock.level.v1", call -> new FakePlatform.PeerReply(404, Map.of()));
        assertEquals("not-configured", refusal(() -> kf.peers().read(ctx, "kf-stock", "m.kf-stock.level.v1", null)).reason());
        stock.read("m.kf-stock.level.v1", call -> new FakePlatform.PeerReply(409, Map.of("owner", "detail")));
        PlatformRefusal refused = refusal(() -> kf.peers().read(ctx, "kf-stock", "m.kf-stock.level.v1", null));
        assertEquals(422, refused.problem().status());
        assertEquals(PeersClient.MODULE_REFUSED, refused.reason());
        assertTrue(!refused.getMessage().contains("detail"), "never the owner's body");
        stock.read("m.kf-stock.level.v1", call -> {
            throw new IllegalStateException("down");
        });
        assertEquals(ErrorClass.TRANSIENT, refusal(() -> kf.peers().read(ctx, "kf-stock", "m.kf-stock.level.v1", null)).errorClass());
    }

    @Test
    @DisplayName("a command: the same key and body replays (the owner called once), another body is 409 idempotency-conflict")
    void command_replayAndConflict() {
        RequestContext ctx = user("sub-1");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", "KF-42");
        body.put("qty", 3);
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("qty", 3);
        reordered.put("sku", "KF-42");

        PeersClient.Answer first = kf.peers().command(ctx, "kf-stock", "reserve", "key-0001-abcdef", body);
        PeersClient.Answer replay = kf.peers().command(ctx, "kf-stock", "reserve", "key-0001-abcdef", reordered);

        assertEquals(201, first.status());
        assertEquals(first, replay);
        assertEquals(1, stock.calls().size());
        assertEquals("key-0001-abcdef", stock.calls().getFirst().idempotencyKey());

        PlatformRefusal conflict = refusal(() -> kf.peers().command(ctx, "kf-stock", "reserve", "key-0001-abcdef",
                Map.of("sku", "KF-42", "qty", 4)));
        assertEquals(409, conflict.problem().status());
        assertEquals(PeersClient.IDEMPOTENCY_CONFLICT, conflict.reason());
        assertEquals(1, stock.calls().size());

        KfModule raw = KfModule.of(fake.url(), fake.slug(), fake.token(), fake.hookSecret(), null);
        FakePlatform.SignedContext s = fake.signedContext(TENANT, "sub-1", List.of(), "GET", "/x");
        RequestContext rawCtx = raw.verifyContext(s.context(), s.timestamp(), s.signature(), s.proof(), new byte[0]);
        // the SDK refuses a missing key locally; the fake refuses it too (a module without the SDK)
        assertThrows(ro.kf.sdk.KfException.class, () -> raw.peers().command(rawCtx, "kf-stock", "reserve", null, Map.of()));
    }

    @Test
    @DisplayName("a transient failure frees the key: the retry with the same key executes")
    void command_transientFreesTheKey() {
        RequestContext ctx = user("sub-1");
        stock.stopped(true);
        refusal(() -> kf.peers().command(ctx, "kf-stock", "reserve", "key-0002-abcdef", Map.of("qty", 1)));
        stock.stopped(false);
        assertEquals(201, kf.peers().command(ctx, "kf-stock", "reserve", "key-0002-abcdef", Map.of("qty", 1)).status());
        assertEquals(1, stock.calls().size());
    }

    @Test
    @DisplayName("an emission is checked and kept; an undeclared event is 422 event-not-declared")
    void events_emitted() {
        RequestContext ctx = user("sub-1");
        EventsClient.Emitted emitted = kf.events().emit(ctx, "order-placed", Map.of("orderId", "17"));

        assertEquals("m.kf-orders.after.order-placed", emitted.type());
        Map<String, Object> kept = fake.emittedEvents().getFirst();
        assertEquals(TENANT, kept.get("tenant"));
        assertEquals(Map.of("orderId", "17"), kept.get("data"));
        assertEquals(emitted.eventId(), kept.get("eventId"));

        KfModule raw = KfModule.of(fake.url(), fake.slug(), fake.token(), fake.hookSecret(), null);
        FakePlatform.SignedContext s = fake.signedContext(TENANT, "sub-1", List.of(), "GET", "/x");
        RequestContext rawCtx = raw.verifyContext(s.context(), s.timestamp(), s.signature(), s.proof(), new byte[0]);
        PlatformRefusal undeclared = refusal(() -> raw.events().emit(rawCtx, "other", Map.of()));
        assertEquals(422, undeclared.problem().status());
        assertEquals("event-not-declared", undeclared.reason());
    }

    @Test
    @DisplayName(": an owner's event reaches the subscriber with the delivery's context; a job calls the owner as system via module")
    void delivery_carriesContext_forABackgroundJob() throws Exception {
        List<HookEvents.ModuleEvent> seen = new CopyOnWriteArrayList<>();
        kf.events().on("kf-stock", "level-changed", seen::add);
        HttpServer module = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        KfHttpServer.mount(module, kf);
        module.start();
        try {
            fake.callbackBase("http://127.0.0.1:" + module.getAddress().getPort());

            FakePlatform.Delivery delivery = fake.deliverModuleEvent("kf-stock", "level-changed", TENANT, Map.of("sku", "KF-42"));

            assertEquals(200, delivery.status(), delivery.body());
            HookEvents.ModuleEvent ev = seen.getFirst();
            assertEquals(delivery.eventId(), ev.eventId());
            assertEquals(Map.of("sku", "KF-42"), ev.data());
            HookEvents.DeliveryContext context = ev.deliveryContext().orElseThrow();
            assertEquals(delivery.context(), context.contextHeader());
            assertEquals(delivery.proof(), context.proof());
            assertEquals(TENANT, context.tenant());

            // the background job, after the handler returned
            PeersClient.Answer a = kf.peers().read(context, "kf-stock", "m.kf-stock.level.v1", Map.of("sku", "KF-42"));
            assertEquals(200, a.status());
            FakePlatform.PeerCall call = stock.calls().getFirst();
            assertEquals("system", call.actorType());
            assertEquals("module:kf-orders", call.via());
            assertEquals(null, call.user());
            assertEquals(TENANT, call.tenant());
        } finally {
            module.stop(0);
        }
        assertThrows(IllegalStateException.class,
                () -> fake.deliverModuleEvent("kf-stock", "unsubscribed", TENANT, Map.of()));
    }

    private static PlatformRefusal refusal(Runnable call) {
        return assertThrows(PlatformRefusal.class, call::run);
    }
}
