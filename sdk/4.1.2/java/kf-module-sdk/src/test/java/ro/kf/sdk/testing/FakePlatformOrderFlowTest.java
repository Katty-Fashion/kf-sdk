package ro.kf.sdk.testing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.sdk.ErrorClass;
import ro.kf.sdk.FlowEvents;
import ro.kf.sdk.FlowRules;
import ro.kf.sdk.HookEvents;
import ro.kf.sdk.HookResult;
import ro.kf.sdk.KfException;
import ro.kf.sdk.KfHttpServer;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.OrderServices;
import ro.kf.sdk.PlatformRefusal;
import ro.kf.sdk.RequestContext;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FakePlatform} 0.10.0, protocol 2.0 §16: the engine's evaluation of the flow rules (the projection
 * {@code order.flow-rule.v1} sent with {@code system via platform}, AND over the rules, every reason, fail-closed on a
 * timeout / non-2xx / non-JSON / unknown decision / stopped module), the service {@code order.services.v1} (upsert on the
 * natural key, another module's line untouched, {@code not-delivered} and {@code no-provider} switchable) and the
 * deliveries of {@code workflow.flow-*} and {@code order.field.changed}.
 */
class FakePlatformOrderFlowTest {

    private static final String TENANT = "00000000-0000-0000-0000-0000000000f2";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MANIFEST = """
            {"schemaVersion":3,"slug":"kf-step","name":"n","version":"1.0.0","platform":">=4.1",
             "i18n":{"en":{"n":"Step","rule.blocked":"Not ready"}},
             "capabilities":{
               "routes":[{"method":"GET","path":"/m/kf-step/rules/a","right":"api.v1.m.kf-step.rules.a.get"},
                         {"method":"GET","path":"/m/kf-step/rules/b","right":"api.v1.m.kf-step.rules.b.get"}],
               "flowRules":[{"key":"rule-a","path":"/m/kf-step/rules/a","projection":"order.flow-rule.v1"},
                            {"key":"rule-b","path":"/m/kf-step/rules/b","projection":"order.flow-rule.v1"}],
               "services":[{"port":"order.services.v1"}],
               "hooks":[{"point":"workflow.flow-step-entered"},{"point":"order.field.changed"}]}}""";

    private static final FlowRules.Projection PROJECTION = new FlowRules.Projection(null, "812", "CONFIRMED",
            "g3-production", "4", "31", "cutting", "sewing", "PO-7", false, "55", "IN_PRODUCTION",
            List.of("sampling", "production"), null, null, null, null);

    private FakePlatform fake;
    private KfModule kf;
    private HttpServer module;
    private final AtomicReference<HookResult> answerA = new AtomicReference<>();
    private final AtomicReference<HookResult> answerB = new AtomicReference<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final List<RequestContext> contexts = new CopyOnWriteArrayList<>();
    private volatile long delayMillis;

    @BeforeEach
    void start() throws Exception {
        fake = FakePlatform.start("kf-step").manifest(MANIFEST);
        kf = fake.module();
        module = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        KfHttpServer.mount(module, kf);
        KfHttpServer.route(module, kf, "/rules/a", (ctx, exchange, body) -> rule(ctx, exchange, answerA));
        KfHttpServer.route(module, kf, "/rules/b", (ctx, exchange, body) -> rule(ctx, exchange, answerB));
        module.start();
        fake.callbackBase("http://127.0.0.1:" + module.getAddress().getPort());
        answerA.set(json(FlowRules.Decision.pass().toJson()));
        answerB.set(json(FlowRules.Decision.pass().toJson()));
    }

    @AfterEach
    void stop() {
        module.stop(0);
        fake.close();
    }

    private HookResult rule(RequestContext ctx, com.sun.net.httpserver.HttpExchange exchange,
                            AtomicReference<HookResult> answer) throws Exception {
        contexts.add(ctx);
        queries.add(exchange.getRequestURI().getRawQuery());
        FlowRules.Projection.fromQuery(exchange.getRequestURI().getRawQuery());   // the module reads it
        if (delayMillis > 0) Thread.sleep(delayMillis);
        return answer.get();
    }

    private static HookResult json(Object body) {
        return new HookResult(200, JSON.writeValueAsString(body));
    }

    private RequestContext user(String subject) {
        FakePlatform.SignedContext s = fake.signedContext(TENANT, subject, List.of(), "POST", "/m/kf-step/x");
        return kf.verifyContext(s.context(), s.timestamp(), s.signature(), s.proof(), new byte[0]);
    }

    @Test
    @DisplayName("each rule receives exactly the projection order.flow-rule.v1, with its rule.key, as system via platform")
    void theRuleReceivesTheProjection() {
        FakePlatform.FlowAdvance advance = fake.evaluateFlowRules(TENANT, PROJECTION);
        assertTrue(advance.passed());
        assertEquals(2, queries.size());
        FlowRules.Projection a = FlowRules.Projection.fromQuery(queries.get(0));
        assertEquals("rule-a", a.ruleKey());
        assertEquals("rule-b", FlowRules.Projection.fromQuery(queries.get(1)).ruleKey());
        assertEquals("812", a.orderId());
        assertEquals("sewing", a.stepTo());
        assertEquals(List.of("production", "sampling"), a.orderStages());
        assertTrue(queries.get(0).startsWith("projection=order.flow-rule.v1&rule.key=rule-a&order.id=812&order.status="
                + "CONFIRMED&flow.key=g3-production&flow.version=4&instance.id=31&step.key=cutting&step.to=sewing"),
                queries.get(0));
        RequestContext ctx = contexts.get(0);
        assertNull(ctx.user());
        assertEquals("platform", ctx.actor().via());
        assertTrue(ctx.rights().isEmpty());
    }

    @Test
    @DisplayName("two rules: one pass and one block → blocked with the one reason; both pass → the advance passes")
    void andOverTheRules() {
        answerB.set(json(FlowRules.Decision.block("rule.blocked", Map.of("item", "A-1")).toJson()));
        FakePlatform.FlowAdvance blocked = fake.evaluateFlowRules(TENANT, PROJECTION);
        assertFalse(blocked.passed());
        assertEquals(1, blocked.reasons().size());
        FakePlatform.RuleOutcome reason = blocked.reasons().get(0);
        assertEquals("rule-b", reason.rule());
        assertEquals("rule.blocked", reason.key());
        assertEquals(Map.of("item", "A-1"), reason.params());
        assertNull(reason.failure());

        answerA.set(json(FlowRules.Decision.block("rule.blocked").toJson()));
        assertEquals(2, fake.evaluateFlowRules(TENANT, PROJECTION).reasons().size(), "every reason, not only the first");

        answerA.set(json(FlowRules.Decision.pass().toJson()));
        answerB.set(json(FlowRules.Decision.pass().toJson()));
        assertTrue(fake.evaluateFlowRules(TENANT, PROJECTION).passed());
    }

    @Test
    @DisplayName("fail-closed: a 500, an HTML body, decision maybe, a block without key, a stopped module, a timeout → blocked")
    void failClosed() {
        answerA.set(new HookResult(500, "{\"type\":\"https://kf.ro/problems/handler-failed\"}"));
        assertEquals("status-500", fake.evaluateFlowRules(TENANT, PROJECTION).reasons().get(0).failure());
        answerA.set(new HookResult(200, "<html>ok</html>", "text/html"));
        assertEquals("invalid-json", fake.evaluateFlowRules(TENANT, PROJECTION).reasons().get(0).failure());
        answerA.set(json(Map.of("decision", "maybe")));
        assertEquals("unknown-decision", fake.evaluateFlowRules(TENANT, PROJECTION).reasons().get(0).failure());
        answerA.set(json(Map.of("decision", "block")));
        assertEquals("block-without-key", fake.evaluateFlowRules(TENANT, PROJECTION).reasons().get(0).failure());

        answerA.set(json(FlowRules.Decision.pass().toJson()));
        fake.moduleStopped(true);
        FakePlatform.FlowAdvance stopped = fake.evaluateFlowRules(TENANT, PROJECTION);
        assertFalse(stopped.passed());
        assertEquals(2, stopped.reasons().size());
        assertEquals("module-unavailable", stopped.reasons().get(1).failure());
        fake.moduleStopped(false);

        delayMillis = 2_500;   // over the default 2 s
        FakePlatform.FlowAdvance slow = fake.evaluateFlowRules(TENANT, PROJECTION);
        assertFalse(slow.passed());
        assertEquals("timeout", slow.reasons().get(0).failure());
    }

    @Test
    @DisplayName("a projection the rule refuses (400) blocks the step")
    void aRefusedProjectionBlocks() {
        answerA.set(new HookResult(400, "{\"type\":\"https://kf.ro/problems/validation\"}"));
        FakePlatform.FlowAdvance advance = fake.evaluateFlowRules(TENANT, PROJECTION);
        assertFalse(advance.passed());
        assertEquals("status-400", advance.reasons().get(0).failure());
    }

    @Test
    @DisplayName("order.services.v1: two upserts on one key → one line updated; another module's line untouched")
    void services() {
        fake.serviceLine(new FakePlatform.ServiceLine(TENANT, "812", "4401", "Manual", BigDecimal.ONE, "EUR", "x:1", "kf-other"));
        RequestContext ctx = user("sub-1");
        kf.orderServices().upsert(ctx, new OrderServices.Line("812", "4401", "Embroidery", new BigDecimal("12.50"), "EUR", "x:1"));
        kf.orderServices().upsert(ctx, new OrderServices.Line("812", "4401", "Embroidery+", new BigDecimal("14.00"), "EUR", "x:1"));
        List<FakePlatform.ServiceLine> lines = fake.serviceLines(TENANT, "812");
        assertEquals(2, lines.size());
        FakePlatform.ServiceLine own = lines.stream().filter(l -> l.source().equals("kf-step")).findFirst().orElseThrow();
        assertEquals("Embroidery+", own.name());
        assertEquals(0, own.amount().compareTo(new BigDecimal("14.00")));

        kf.orderServices().delete(ctx, "812", "x:1");
        List<FakePlatform.ServiceLine> after = fake.serviceLines(TENANT, "812");
        assertEquals(1, after.size(), "only this module's line is removed");
        assertEquals("kf-other", after.get(0).source());

        // (10 Nov 2026): the other module's line is now the only one of that ref -- a delete is refused, 409 permanent
        KfException refused = assertThrows(KfException.class, () -> kf.orderServices().delete(ctx, "812", "x:1"));
        assertEquals(409, refused.problem().status());
        assertEquals(ErrorClass.PERMANENT, refused.problem().errorClass());
        assertTrue(refused.problem().type().endsWith("/order-service-line-owned-by-module"));
        assertEquals(1, fake.serviceLines(TENANT, "812").size(), "the other module's line is untouched");
        kf.orderServices().delete(ctx, "812", "x:absent"); // absent everywhere: still 204
    }

    @Test
    @DisplayName("order.services.v1: not-delivered 422 and no-provider 501 switchable; a system context and a missing right refused")
    void servicesRefusals() {
        RequestContext ctx = user("sub-2");
        OrderServices.Line line = new OrderServices.Line("812", "4401", "E", BigDecimal.ONE, "EUR", "x:2");
        fake.orderServices(FakePlatform.ServicesState.NOT_DELIVERED);
        PlatformRefusal notDelivered = assertThrows(PlatformRefusal.class, () -> kf.orderServices().upsert(ctx, line));
        assertEquals(422, notDelivered.problem().status());
        assertEquals("not-delivered", notDelivered.reason());
        assertEquals(PlatformRefusal.Kind.REFUSED, notDelivered.kind());
        assertEquals(ErrorClass.PERMANENT, notDelivered.errorClass());

        fake.orderServices(FakePlatform.ServicesState.NO_PROVIDER);
        PlatformRefusal noProvider = assertThrows(PlatformRefusal.class, () -> kf.orderServices().upsert(ctx, line));
        assertEquals(501, noProvider.problem().status());
        assertEquals(PlatformRefusal.Kind.UNAVAILABLE, noProvider.kind());

        fake.orderServices(FakePlatform.ServicesState.DELIVERED).userRights(TENANT, "sub-3", "api.v1.orders.get");
        PlatformRefusal rights = assertThrows(PlatformRefusal.class, () -> kf.orderServices().upsert(user("sub-3"), line));
        assertEquals("insufficient-rights", rights.reason());

        FakePlatform.SignedContext system = fake.systemContext(TENANT, "GET", "/m/kf-step/rules/a");
        RequestContext sys = kf.verifyContext(system.context(), system.timestamp(), system.signature(), system.proof(),
                new byte[0]);
        assertEquals("insufficient-rights",
                assertThrows(PlatformRefusal.class, () -> kf.orderServices().upsert(sys, line)).reason());
        assertTrue(fake.serviceLines(TENANT, "812").isEmpty());
    }

    @Test
    @DisplayName("workflow.flow-step-entered and order.field.changed are delivered signed, with a kfeventid and the context")
    void deliveries() {
        List<FlowEvents.FlowEvent> flowEvents = new CopyOnWriteArrayList<>();
        List<HookEvents.FieldChange> changes = new CopyOnWriteArrayList<>();
        kf.flows().on(FlowEvents.STEP_ENTERED, flowEvents::add);
        kf.orders().onFieldChange(HookEvents.ORDER_PATH_STATUS, changes::add);

        FakePlatform.Delivery d = fake.deliverFlowEvent(FlowEvents.STEP_ENTERED, TENANT,
                Map.of("orderId", 812, "instanceId", 31, "flowKey", "g3", "flowVersion", 4, "stepKey", "cutting"));
        assertEquals(200, d.status(), d.body());
        assertEquals(1, flowEvents.size());
        assertEquals(d.eventId(), flowEvents.get(0).eventId());
        assertEquals("cutting", flowEvents.get(0).stepKey());
        assertTrue(flowEvents.get(0).deliveryContext().isPresent(), "the context of");

        FakePlatform.Delivery c = fake.deliverOrderFieldChanged(TENANT, "812", "status", "SHIPPED", "COMPLETED");
        assertEquals(200, c.status(), c.body());
        assertEquals("COMPLETED", changes.get(0).newValue());
        assertEquals(c.eventId(), changes.get(0).eventId());

        assertThrows(IllegalStateException.class, () -> fake.deliverFlowEvent(FlowEvents.STEP_COMPLETED, TENANT,
                Map.of("orderId", 1, "instanceId", 2, "stepKey", "a")), "not subscribed: the platform would not deliver it");
    }
}
