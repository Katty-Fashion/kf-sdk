package ro.kf.sdk.conformance;

import com.sun.net.httpserver.HttpServer;
import ro.kf.sdk.AuditClient;
import ro.kf.sdk.AuditRecord;
import ro.kf.sdk.BeforeContext;
import ro.kf.sdk.ErrorClass;
import ro.kf.sdk.KfException;
import ro.kf.sdk.Decision;
import ro.kf.sdk.HookResult;
import ro.kf.sdk.RequestContext;
import ro.kf.sdk.Signatures;
import ro.kf.sdk.StorageClient;
import ro.kf.sdk.FieldType;
import ro.kf.sdk.HookEvents;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.Manifest;
import ro.kf.sdk.ManifestCheck;
import ro.kf.sdk.ModelSnapshot;
import ro.kf.sdk.ModelHooks;
import ro.kf.sdk.NotificationOptions;
import ro.kf.sdk.PeersClient;
import ro.kf.sdk.EventsClient;
import ro.kf.sdk.FlowEvents;
import ro.kf.sdk.FlowRules;
import ro.kf.sdk.OrderServices;
import ro.kf.sdk.PlatformRefusal;
import ro.kf.sdk.ReadPage;
import ro.kf.sdk.Notifications;
import ro.kf.sdk.Surface;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs one conformance fixture against the Java SDK, by area. An area this runner does not know fails the fixture
 * (nothing is skipped).
 */
final class FixtureRunner {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String TOKEN = "kfm_conformance-token-never-logged";

    private FixtureRunner() {
    }

    static void run(Map<String, Object> fixture) throws Exception {
        String area = (String) fixture.get("area");
        Map<String, Object> input = map(fixture.get("input"));
        Map<String, Object> expect = map(fixture.get("expect"));
        String id = (String) fixture.get("id");
        switch (area) {
            case "errors" -> errors(id, input, expect);
            case "registration" -> registration(id, input, expect);
            case "manifest" -> manifest(id, input, expect);
            case "signature" -> signature(id, input, expect);
            case "envelope", "before" -> callback(id, input, expect);
            case "flags" -> flags(id, input, expect);
            case "context" -> context(id, input, expect);
            case "audit" -> audit(id, input, expect);
            case "notifications" -> notifications(id, input, expect);
            case "reads", "settings", "secrets", "extensions", "storage", "peers", "services" ->
                    service(area, id, input, expect);
            case "slots" -> slots(id, input, expect);
            case "flow" -> flow(id, input, expect);
            case "events" -> {
                if (input.containsKey("envelope")) moduleEvent(id, input, expect);
                else service(area, id, input, expect);
            }
            default -> fail(id + ": the Java runner does not know the area \"" + area + "\"");
        }
    }

    // ---- errors ---------------------------------------------------------------------------------------------

    /** The platform's answer to the registration → whether the SDK retries, the class, the error line. */
    private static void errors(String id, Map<String, Object> input, Map<String, Object> expect) throws Exception {
        boolean retry = (Boolean) expect.get("retry");
        String expectedClass = (String) expect.get("class");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = null;
        String url;
        if ("connection-refused".equals(input.get("network"))) {
            try (ServerSocket socket = new ServerSocket(0)) {
                url = "http://127.0.0.1:" + socket.getLocalPort();
            }
        } else {
            Map<String, Object> response = map(input.get("response"));
            assertNotNull(response, id + ": input.response or input.network is required");
            server = answering(response, requests);
            url = "http://127.0.0.1:" + server.getAddress().getPort();
        }
        KfModule kf = KfModule.of(url, "conformance", TOKEN, "conformance-secret", null)
                .withRetryDelays(List.of(Duration.ofMillis(50)));
        try {
            kf.start();
            if (server == null) {
                await(id, () -> kf.lastRegistrationError() != null, 15_000);
                Thread.sleep(150);
                assertEquals(retry, !kf.isRegistrationStopped(), id + ": retry");
            } else if (retry) {
                await(id, () -> requests.get() >= 2, 10_000);
                assertFalse(kf.isRegistrationStopped(), id + ": a transient error must not stop the registration");
            } else {
                await(id, kf::isRegistrationStopped, 10_000);
                Thread.sleep(200);
                assertEquals(1, requests.get(), id + ": a permanent error must not be retried");
            }
            assertNotNull(kf.lastRegistrationProblem(), id + ": the problem must be kept");
            assertEquals(expectedClass, kf.lastRegistrationProblem().errorClass().wire(), id + ": class");
            String line = kf.lastRegistrationError();
            for (Object fragment : list(expect.get("messageContains"))) {
                assertTrue(line.contains(String.valueOf(fragment)), id + ": the error line must contain \"" + fragment
                        + "\": " + line);
            }
            assertFalse(line.contains(TOKEN), id + ": the module token must never reach the error line");
        } finally {
            kf.stop();
            if (server != null) server.stop(0);
        }
    }

    private static HttpServer answering(Map<String, Object> response, AtomicInteger requests) throws Exception {
        int status = ((Number) response.get("status")).intValue();
        Map<String, Object> headers = map(response.get("headers"));
        Object body = response.get("body");
        byte[] bytes = body == null ? new byte[0]
                : body instanceof String text ? text.getBytes(StandardCharsets.UTF_8) : JSON.writeValueAsBytes(body);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                if (headers != null) headers.forEach((k, v) -> exchange.getResponseHeaders().set(k, String.valueOf(v)));
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                }
            }
        });
        server.start();
        return server;
    }

    // ---- registration -----------------------------------------------------------------------------------------

    /** A declarative module description -> {@code registrationBody()}, equal as JSON to {@code expect.body}. */
    private static void registration(String id, Map<String, Object> input, Map<String, Object> expect) {
        KfModule kf = module(input);
        Object actual = JSON.readValue(JSON.writeValueAsString(kf.registrationBody()), Object.class);
        assertEquals(expect.get("body"), actual, id + ": registration body");
    }

    /**
     * Builds a module from a declarative description (shared by the areas that need one):
     * {@code slug}, {@code callbackBase}, {@code fields[{entity,name,type,table,labelKey}]},
     * {@code hooks[{event,priority,onFailure,fields}]}, {@code surfaces[{slug,kind,entry,export|tag,css,order,labelKey,
     * flag,key,peer}]}.
     */
    static KfModule module(Map<String, Object> input) {
        return module("http://127.0.0.1:1", input);
    }

    static KfModule module(String platformUrl, Map<String, Object> input) {
        KfModule kf = KfModule.of(platformUrl, (String) input.getOrDefault("slug", "conformance"), TOKEN,
                (String) input.getOrDefault("hookSecret", "conformance-secret"), (String) input.get("callbackBase"));
        for (Object o : list(input.get("fields"))) {
            Map<String, Object> f = map(o);
            kf.registerField((String) f.get("entity"), (String) f.get("name"), FieldType.valueOf((String) f.get("type")),
                    (String) f.get("table"), (String) f.get("labelKey"));
        }
        for (Object o : list(input.get("hooks"))) hook(kf, map(o));
        for (Object o : list(input.get("surfaces"))) {
            Map<String, Object> d = map(o);
            Surface s = switch (String.valueOf(d.get("kind"))) {
                case "wc" -> Surface.wc((String) d.get("entry"), (String) d.get("tag"));
                // protocol 2.0 (§7.12): the kinds without code of a slot of another module
                case "layer" -> Surface.layer((String) d.get("layer"), (String) d.get("source"));
                case "data" -> Surface.data(slotData(d));
                default -> Surface.react((String) d.get("entry"), (String) d.get("export"));
            };
            if (d.get("css") != null) s.css((String) d.get("css"));
            if (d.get("order") != null) s.order(((Number) d.get("order")).intValue());
            if (d.get("labelKey") != null) s.labelKey((String) d.get("labelKey"));
            if (d.get("flag") != null) s.flag((String) d.get("flag"));
            if (d.get("key") != null) s.key((String) d.get("key"));
            Map<String, Object> peer = map(d.get("peer"));
            if (peer != null) peer.forEach((k, v) -> s.peer(k, String.valueOf(v)));
            kf.ui().contribute((String) d.get("slug"), s);
        }
        return kf;
    }

    /** {@code data}, or {@code dataRepeat {field, char, count}}: an object with one text of {@code count} characters. */
    private static Map<String, Object> slotData(Map<String, Object> d) {
        Map<String, Object> repeat = map(d.get("dataRepeat"));
        if (repeat == null) return map(d.get("data"));
        return Map.of((String) repeat.get("field"),
                String.valueOf(repeat.get("char")).repeat(((Number) repeat.get("count")).intValue()));
    }

    // ---- slots (protocol 2.0) --------------------------------------------------------------------

    /**
     * The contributions to the slots of other modules: what the SDK sends ({@code expect.surfaces}) and the
     * registration warnings it exposes ({@code expect.warnings}) from the platform's scripted answer; or a
     * contribution refused locally before anything is sent ({@code expect.localError}).
     */
    private static void slots(String id, Map<String, Object> input, Map<String, Object> expect) throws Exception {
        if (expect.get("localError") != null) {
            IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> module(input), id + ": refused locally");
            assertTrue(refused.getMessage().contains((String) expect.get("localError")),
                    id + ": the local refusal must contain <" + expect.get("localError") + ">: " + refused.getMessage());
            return;
        }
        Map<String, Object> answer = map(input.get("platform"));
        java.util.concurrent.atomic.AtomicReference<Object> sent = new java.util.concurrent.atomic.AtomicReference<>();
        byte[] bytes = JSON.writeValueAsBytes(answer.get("body"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (exchange.getRequestURI().getPath().endsWith("/registrations")) {
                    sent.set(JSON.readValue(body, Object.class));
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(((Number) answer.get("status")).intValue(), bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                } else {
                    // the flags read after a registration: none
                    byte[] none = "[]".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, none.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(none);
                    }
                }
            }
        });
        server.start();
        KfModule kf = module("http://127.0.0.1:" + server.getAddress().getPort(), input);
        try {
            kf.start();
            await(id, kf::isRegistered, 10_000);
            assertEquals(expect.get("surfaces"), map(sent.get()).get("surfaces"), id + ": the contributions sent");
            assertEquals(expect.get("warnings"), kf.registrationWarnings(), id + ": the warnings exposed");
        } finally {
            kf.stop();
            server.stop(0);
        }
    }

    /** One declared hook; before handlers allow. */
    private static void hook(KfModule kf, Map<String, Object> h) {
        String event = (String) h.get("event");
        int priority = h.get("priority") == null ? ModelHooks.DEFAULT_PRIORITY : ((Number) h.get("priority")).intValue();
        ModelHooks.OnFailure onFailure = "block".equals(h.get("onFailure")) ? ModelHooks.OnFailure.BLOCK : ModelHooks.OnFailure.ALLOW;
        HookEvents.BeforeHandler allow = BeforeContext::allow;
        HookEvents.AfterHandler noop = ev -> {
        };
        ModelHooks m = kf.models();
        switch (event) {
            case HookEvents.BEFORE_CREATE -> m.beforeCreate(priority, onFailure, allow);
            case HookEvents.BEFORE_UPDATE -> m.beforeUpdate(priority, onFailure, allow);
            case HookEvents.BEFORE_DELETE -> m.beforeDelete(priority, onFailure, allow);
            case HookEvents.AFTER_CREATE -> m.afterCreate(noop);
            case HookEvents.AFTER_UPDATE -> m.afterUpdate(noop);
            case HookEvents.AFTER_DELETE -> m.afterDelete(noop);
            case HookEvents.FIELD_CHANGED -> m.onFieldChange(list(h.get("fields")).stream().map(String::valueOf).toList(), ch -> {
            });
            default -> fail("the declarative module does not know the event " + event);
        }
    }

    // ---- manifest ---------------------------------------------------------------------------------------------

    /**
     * {@code input.manifest} (+ optional {@code input.registration}, a declarative module; {@code platformLanguages})
     * -> {@code ManifestCheck}: {@code expect.valid}, {@code errorsContain}, {@code warningsContain}, and {@code read}
     * (JSON pointer -> value on the typed {@code Manifest}).
     */
    private static void manifest(String id, Map<String, Object> input, Map<String, Object> expect) {
        String manifest = JSON.writeValueAsString(input.get("manifest"));
        Set<String> languages = list(input.get("platformLanguages")).stream().map(String::valueOf).collect(Collectors.toSet());
        ManifestCheck.Result result = input.get("registration") == null
                ? ManifestCheck.validate(manifest, languages)
                : ManifestCheck.check(manifest, module(map(input.get("registration"))).registrationBody(), languages);
        boolean valid = (Boolean) expect.get("valid");
        assertEquals(valid, result.ok(), id + ": valid; errors=" + result.errors());
        for (Object fragment : list(expect.get("errorsContain"))) {
            assertTrue(result.errors().stream().anyMatch(e -> e.contains(String.valueOf(fragment))),
                    id + ": an error must contain <" + fragment + ">: " + result.errors());
        }
        for (Object fragment : list(expect.get("warningsContain"))) {
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains(String.valueOf(fragment))),
                    id + ": a warning must contain <" + fragment + ">: " + result.warnings());
        }
        if (expect.containsKey("warningsContain") && list(expect.get("warningsContain")).isEmpty()) {
            assertTrue(result.warnings().isEmpty(), id + ": no warning expected: " + result.warnings());
        }
        Map<String, Object> read = map(expect.get("read"));
        if (read != null) {
            Object typed = JSON.convertValue(Manifest.parse(manifest), Object.class);
            read.forEach((pointer, value) -> assertEquals(value, pointer(typed, pointer), id + ": " + pointer));
        }
    }

    /** A JSON pointer ({@code /a/0/b}) into a {@code Map}/{@code List} tree. */
    static Object pointer(Object tree, String pointer) {
        Object at = tree;
        for (String token : pointer.substring(1).split("/")) {
            if (at instanceof Map<?, ?> m) at = m.get(token);
            else if (at instanceof List<?> l) at = l.get(Integer.parseInt(token));
            else fail("pointer " + pointer + " leaves the tree at " + token);
        }
        return at;
    }

    // ---- signature ----------------------------------------------------------------------------------------------

    /** {@code sign} must produce {@code expect.signature}; {@code verify} must answer {@code expect.valid}. */
    private static void signature(String id, Map<String, Object> input, Map<String, Object> expect) {
        String secret = (String) input.get("secret");
        String ts = (String) input.get("timestamp");
        byte[] body = ((String) input.get("body")).getBytes(StandardCharsets.UTF_8);
        if (expect.containsKey("signature")) {
            assertEquals(expect.get("signature"), Signatures.sign(secret, ts, body), id + ": signature");
        }
        if (expect.containsKey("valid")) {
            long now = ((Number) input.get("now")).longValue();
            String reason = Signatures.verify(secret, ts, (String) input.get("signature"), body, now);
            assertEquals(expect.get("valid"), reason == null, id + ": valid (reason: " + reason + ")");
            if (expect.get("reasonContains") != null) {
                assertNotNull(reason, id + ": a reason is expected");
                assertTrue(reason.contains((String) expect.get("reasonContains")), id + ": reason " + reason);
            }
        }
    }

    // ---- envelope / before -------------------------------------------------------------------------------------

    static final String SECRET = "conformance-secret";

    /**
     * One callback: {@code input.envelope} (or {@code input.rawBody}), signed by the runner with the module's secret
     * now ({@code input.signature}: {@code valid} by default, {@code wrong}); the module has a recording handler on
     * every point, whose before handlers answer {@code input.answer} ({@code {decision: allow | reject | patch, key,
     * params, set, extensions}}, allow by default). {@code expect}: {@code status}, {@code handlerCalled},
     * {@code handler} (what the handler saw), {@code body} (exact JSON) or {@code bodyContains} (subset),
     * {@code contentType}.
     */
    private static void callback(String id, Map<String, Object> input, Map<String, Object> expect) {
        Recorder recorder = new Recorder(map(input.get("answer")));
        KfModule kf = recorder.module();
        HookResult result = send(kf, input.get("envelope"), (String) input.get("rawBody"), (String) input.get("signature"));
        assertEquals(((Number) expect.get("status")).intValue(), result.status(), id + ": status; body " + result.body());
        if (expect.containsKey("handlerCalled")) {
            assertEquals(expect.get("handlerCalled"), recorder.seen != null, id + ": handlerCalled");
        }
        Map<String, Object> handler = map(expect.get("handler"));
        if (handler != null) {
            assertNotNull(recorder.seen, id + ": the handler was not called");
            handler.forEach((k, v) -> assertEquals(v, recorder.seen.get(k), id + ": handler saw " + k));
        }
        Object body = JSON.readValue(result.body(), Object.class);
        if (expect.containsKey("body")) assertEquals(expect.get("body"), body, id + ": body");
        Map<String, Object> contains = map(expect.get("bodyContains"));
        if (contains != null) {
            Map<String, Object> actual = map(body);
            contains.forEach((k, v) -> assertEquals(v, actual.get(k), id + ": body." + k + " in " + result.body()));
        }
        if (expect.containsKey("contentType")) assertEquals(expect.get("contentType"), result.contentType(), id + ": content type");
    }

    static HookResult send(KfModule kf, Object envelope, String rawBody, String signature) {
        byte[] bytes = rawBody != null ? rawBody.getBytes(StandardCharsets.UTF_8) : JSON.writeValueAsBytes(envelope);
        long now = Instant.now().getEpochSecond();
        String ts = String.valueOf(now);
        String sig = "wrong".equals(signature) ? "0".repeat(64) : Signatures.sign(SECRET, ts, bytes);
        return kf.handle(bytes, ts, sig, now);
    }

    /** A module with a recording handler on every point. */
    static final class Recorder {
        final Map<String, Object> answer;
        Map<String, Object> seen;

        Recorder(Map<String, Object> answer) {
            this.answer = answer == null ? Map.of("decision", "allow") : answer;
        }

        KfModule module() {
            KfModule kf = KfModule.of("http://127.0.0.1:1", "conformance", TOKEN, SECRET, null);
            HookEvents.BeforeHandler before = ctx -> {
                seen = seen(ctx.event(), ctx.envelope(), ctx.id());
                versions(seen, ctx.changedVersionIds(), ctx.changedVersionFields(), ctx.before(), ctx.after());
                return decide(ctx);
            };
            HookEvents.AfterHandler after = ev -> {
                seen = seen(ev.event(), ev.envelope(), ev.id());
                versions(seen, ev.changedVersionIds(), ev.changedVersionFields(), ev.before(), ev.after());
            };
            kf.models().beforeCreate(before).beforeUpdate(before).beforeDelete(before)
                    .afterCreate(after).afterUpdate(after).afterDelete(after)
                    .onFieldChange(List.of(), ch -> {
                        seen = seen(HookEvents.FIELD_CHANGED, ch.envelope(), ch.id());
                        seen.put("field", ch.field());
                        seen.put("versionField", versionField(ch.versionField()));
                    });
            // protocol 2.0 (sdk-protocol.md 16.2, 16.3): the order's field changes, the events of the flow engine
            kf.orders().onFieldChange(List.of(), ch -> {
                seen = seen(HookEvents.ORDER_FIELD_CHANGED, ch.envelope(), ch.id());
                seen.put("field", ch.field());
                seen.put("oldValue", ch.oldValue());
                seen.put("newValue", ch.newValue());
            });
            for (String type : FlowEvents.TYPES) {
                kf.flows().on(type, ev -> {
                    seen = seen(ev.type(), ev.envelope(), ev.orderId());
                    seen.put("orderId", ev.orderId());
                    seen.put("instanceId", ev.instanceId());
                    seen.put("flowKey", ev.flowKey());
                    seen.put("flowVersion", ev.flowVersion());
                    seen.put("stepKey", ev.stepKey());
                });
            }
            kf.tenants().onDeleted(deleted -> seen = seen(HookEvents.TENANT_AFTER_DELETE, deleted.envelope(), deleted.tenantId()))
                    .onSoftDelete(e -> seen = seen(HookEvents.TENANT_AFTER_SOFT_DELETE, e.envelope(), e.tenantId()))
                    .onRestore(e -> seen = seen(HookEvents.TENANT_AFTER_RESTORE, e.envelope(), e.tenantId()));
            return kf;
        }

        /**
         * A version's values in the model's envelope: {@code changedVersionIds},
         * {@code changedVersionFields} ({@code [{versionId, slug, field}]}) and {@code versionValues} (the same, with
         * {@code before} / {@code after} read by {@code versionExtension}).
         */
        private static void versions(Map<String, Object> seen, List<String> ids, List<HookEvents.VersionField> fields,
                                     ModelSnapshot before, ModelSnapshot after) {
            List<Object> changed = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (HookEvents.VersionField f : fields) {
                changed.add(versionField(f));
                Map<String, Object> v = versionField(f);
                v.put("before", before == null ? null : before.versionExtension(f.versionId(), f.slug(), f.field()));
                v.put("after", after == null ? null : after.versionExtension(f.versionId(), f.slug(), f.field()));
                values.add(v);
            }
            seen.put("changedVersionIds", ids);
            seen.put("changedVersionFields", changed);
            seen.put("versionValues", values);
        }

        private static Map<String, Object> versionField(HookEvents.VersionField f) {
            if (f == null) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("versionId", f.versionId());
            m.put("slug", f.slug());
            m.put("field", f.field());
            return m;
        }

        private static Map<String, Object> seen(String event, HookEvents.Envelope e, String id) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event", event);
            m.put("id", id);
            m.put("tenant", e.tenant());
            m.put("locale", e.locale());
            m.put("eventId", e.eventId());
            m.put("deliveryId", e.deliveryId());
            m.put("envelopeVersion", e.envelopeVersion());
            m.put("actor", e.actor());
            m.put("aggregateType", e.aggregateType());
            return m;
        }

        @SuppressWarnings("unchecked")
        private Decision decide(BeforeContext ctx) {
            String decision = (String) answer.get("decision");
            return switch (decision) {
                case "allow" -> ctx.allow();
                case "reject" -> answer.containsKey("params")
                        ? ctx.reject((String) answer.get("key"), (Map<String, Object>) answer.get("params"))
                        : ctx.reject((String) answer.get("key"));
                case "patch" -> {
                    Decision.Patch patch = ctx.patch();
                    Map<String, Object> set = map(answer.get("set"));
                    if (set != null) set.forEach(patch::set);
                    Map<String, Object> ext = map(answer.get("extensions"));
                    if (ext != null) ext.forEach(patch::extension);
                    yield patch;
                }
                default -> throw new IllegalArgumentException("unknown answer " + decision);
            };
        }
    }

    // ---- flow (protocol 2.0, sdk-protocol.md 16.4) ----------------------------------------------------

    /**
     * The module's side of a flow rule. {@code input.query} (the raw query of the rule's request) then
     * {@code expect.valid} and {@code expect.projection} (the typed projection: each field the fixture writes, compared;
     * {@code orderStages} a list, {@code onLine} a boolean) or {@code expect.errorContains}. {@code input.decision}
     * ({@code pass} | {@code block}, with {@code key} and {@code params}) then {@code expect.valid} and
     * {@code expect.json} (the answer's body) or {@code expect.errorContains} (refused locally).
     */
    private static void flow(String id, Map<String, Object> input, Map<String, Object> expect) {
        boolean valid = (Boolean) expect.get("valid");
        if (input.containsKey("query")) {
            try {
                FlowRules.Projection p = FlowRules.Projection.fromQuery((String) input.get("query"));
                assertTrue(valid, id + ": the projection must be refused, it was read: " + p);
                Map<String, Object> actual = new LinkedHashMap<>();
                actual.put("ruleKey", p.ruleKey());
                actual.put("orderId", p.orderId());
                actual.put("orderStatus", p.orderStatus());
                actual.put("flowKey", p.flowKey());
                actual.put("flowVersion", p.flowVersion());
                actual.put("instanceId", p.instanceId());
                actual.put("stepKey", p.stepKey());
                actual.put("stepTo", p.stepTo());
                actual.put("orderNumber", p.orderNumber());
                actual.put("orderInternal", p.orderInternal());
                actual.put("orderCustomerId", p.orderCustomerId());
                actual.put("stepBehavesLike", p.stepBehavesLike());
                actual.put("orderStages", p.orderStages());
                actual.put("lineId", p.lineId());
                actual.put("lineModelId", p.lineModelId());
                actual.put("lineModelVersionId", p.lineModelVersionId());
                actual.put("lineQuantity", p.lineQuantity());
                actual.put("onLine", p.onLine());
                map(expect.get("projection")).forEach((k, v) -> assertEquals(v, actual.get(k), id + ": projection." + k));
            } catch (IllegalArgumentException e) {
                assertFalse(valid, id + ": the projection must be read, it was refused: " + e.getMessage());
                assertTrue(e.getMessage().contains((String) expect.get("errorContains")), id + ": " + e.getMessage());
            }
            return;
        }
        String decision = (String) input.get("decision");
        try {
            FlowRules.Decision d = switch (decision) {
                case "pass" -> FlowRules.Decision.pass();
                case "block" -> FlowRules.Decision.block((String) input.get("key"), map(input.get("params")));
                default -> throw new IllegalStateException(id + ": unknown decision " + decision);
            };
            assertTrue(valid, id + ": the decision must be refused locally, it was built: " + d);
            assertEquals(JSON.readValue(JSON.writeValueAsString(expect.get("json")), Object.class),
                    JSON.readValue(JSON.writeValueAsString(d.toJson()), Object.class), id + ": json");
        } catch (IllegalArgumentException e) {
            assertFalse(valid, id + ": the decision must be built, it was refused: " + e.getMessage());
            assertTrue(e.getMessage().contains((String) expect.get("errorContains")), id + ": " + e.getMessage());
        }
    }

    // ---- flags ------------------------------------------------------------------------------------------------

    /** {@code input.envelopes} handled in order; then {@code expect.flags} ({@code key -> enabled}). */
    private static void flags(String id, Map<String, Object> input, Map<String, Object> expect) {
        KfModule kf = new Recorder(null).module();
        for (Object envelope : list(input.get("envelopes"))) {
            HookResult result = send(kf, envelope, null, null);
            assertEquals(200, result.status(), id + ": every callback is answered 200; " + result.body());
        }
        map(expect.get("flags")).forEach((key, enabled) ->
                assertEquals(enabled, kf.flags().isEnabled(key), id + ": flag " + key));
    }

    // ---- context ----------------------------------------------------------------------------------------

    /**
     * {@code input}: {@code secret}, {@code now}, {@code headers} (the three X-KF-* headers; any may be absent),
     * {@code body}. {@code expect}: {@code valid: true} + {@code context} (every key, {@code actor} with its wire type,
     * {@code person} only when the context carries one), or {@code valid: false} + {@code status}, {@code class},
     * {@code reasonContains}.
     */
    private static void context(String id, Map<String, Object> input, Map<String, Object> expect) {
        Map<String, Object> headers = map(input.get("headers"));
        byte[] body = ((String) input.getOrDefault("body", "")).getBytes(StandardCharsets.UTF_8);
        long now = ((Number) input.get("now")).longValue();
        boolean valid = (Boolean) expect.get("valid");
        try {
            RequestContext ctx = RequestContext.verify((String) headers.get(RequestContext.CONTEXT_HEADER),
                    (String) headers.get(RequestContext.TIMESTAMP_HEADER), (String) headers.get(RequestContext.SIGNATURE_HEADER),
                    body, (String) input.get("secret"), now);
            assertTrue(valid, id + ": the context must be refused, it was accepted: " + ctx);
            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("v", ctx.v());
            actual.put("tenant", ctx.tenant());
            actual.put("user", ctx.user());
            Map<String, Object> actor = new LinkedHashMap<>();
            actor.put("type", ctx.actor().type().wire());
            actor.put("via", ctx.actor().via());
            actor.put("hookId", ctx.actor().hookId());
            actual.put("actor", actor);
            actual.put("locale", ctx.locale());
            actual.put("rights", ctx.rights());
            actual.put("method", ctx.method());
            actual.put("path", ctx.path());
            actual.put("query", ctx.query());
            actual.put("ts", ctx.ts());
            actual.put("requestId", ctx.requestId());
            if (ctx.person() != null) { // protocol 1.2: only when the context carries it
                Map<String, Object> person = new LinkedHashMap<>();
                person.put("email", ctx.person().email());
                person.put("displayName", ctx.person().displayName());
                actual.put("person", person);
            }
            Object normalized = JSON.readValue(JSON.writeValueAsString(actual), Object.class);
            assertEquals(expect.get("context"), normalized, id + ": context");
        } catch (RequestContext.Rejected rejected) {
            assertFalse(valid, id + ": the context must be accepted, it was refused: " + rejected.getMessage());
            assertEquals(((Number) expect.get("status")).intValue(), rejected.problem().status(), id + ": status");
            assertEquals(expect.get("class"), rejected.errorClass().wire(), id + ": class");
            if (expect.get("reasonContains") != null) {
                assertTrue(rejected.getMessage().contains((String) expect.get("reasonContains")),
                        id + ": reason " + rejected.getMessage());
            }
            Map<String, Object> problem = map(JSON.readValue(rejected.problemJson(), Object.class));
            assertEquals(expect.get("class"), problem.get("class"), id + ": RFC 9457 body class");
        }
    }

    // ---- audit ---------------------------------------------------------------------------------------

    /**
     * {@code AuditClient.record/list} against a platform stub: the request it sends (method, path, query, the three
     * headers, the body) and what it makes of the answer. The context of {@code input.context} is signed by the runner
     * with {@code ts} = now and verified through {@code KfModule.verifyContext} with {@code input.proof}, as an adapter
     * does; {@code "$context"} in the expected headers is that exact {@code X-KF-Context}.
     */
    private static void audit(String id, Map<String, Object> input, Map<String, Object> expect) throws Exception {
        String secret = "conformance-audit-secret";
        long now = Instant.now().getEpochSecond();
        Map<String, Object> contextJson = new LinkedHashMap<>(map(input.get("context")));
        contextJson.put("ts", now);
        String context = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JSON.writeValueAsString(contextJson).getBytes(StandardCharsets.UTF_8));
        String signature = Signatures.sign(secret, String.valueOf(now), (context + ".").getBytes(StandardCharsets.US_ASCII));

        Map<String, Object> answer = map(input.get("platform"));
        Map<String, Object> seen = new LinkedHashMap<>();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                seen.put("method", exchange.getRequestMethod());
                seen.put("path", exchange.getRequestURI().getRawPath());
                seen.put("query", exchange.getRequestURI().getRawQuery());
                Map<String, Object> headers = new LinkedHashMap<>();
                for (String name : List.of("Authorization", "X-KF-Context", "X-KF-Context-Proof")) {
                    headers.put(name, exchange.getRequestHeaders().getFirst(name));
                }
                seen.put("headers", headers);
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                seen.put("body", body.isEmpty() ? null : JSON.readValue(body, Object.class));
                byte[] bytes = ((String) answer.get("body")).getBytes(StandardCharsets.UTF_8);
                int status = ((Number) answer.get("status")).intValue();
                exchange.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            }
        });
        server.start();
        try {
            KfModule kf = KfModule.of("http://127.0.0.1:" + server.getAddress().getPort(), (String) input.get("slug"),
                    TOKEN, secret, null);
            RequestContext ctx = kf.verifyContext(context, String.valueOf(now), signature, (String) input.get("proof"),
                    new byte[0]);
            Object rows = null;
            KfException error = null;
            try {
                if ("record".equals(input.get("call"))) {
                    Map<String, Object> r = map(input.get("record"));
                    kf.audit().record(ctx, new AuditRecord((String) r.get("action"), (String) r.get("aggregateType"),
                            (String) r.get("aggregateRef")));
                } else if ("list".equals(input.get("call"))) {
                    Map<String, Object> q = map(input.get("query"));
                    rows = kf.audit().list(ctx, (String) q.get("aggregateType"), (String) q.get("aggregateRef"));
                } else {
                    fail(id + ": unknown call " + input.get("call"));
                }
            } catch (KfException e) {
                error = e;
            }

            Map<String, Object> request = map(expect.get("request"));
            if (request == null) {
                assertEquals(0, requests.get(), id + ": no request may be sent");
            } else {
                assertEquals(1, requests.get(), id + ": exactly one request");
                Map<String, Object> wanted = new LinkedHashMap<>(request);
                Map<String, Object> wantedHeaders = new LinkedHashMap<>(map(request.get("headers")));
                wantedHeaders.replaceAll((k, v) -> "$context".equals(v) ? context : v);
                wanted.put("headers", wantedHeaders);
                assertEquals(JSON.readValue(JSON.writeValueAsString(wanted), Object.class),
                        JSON.readValue(JSON.writeValueAsString(seen), Object.class), id + ": request");
            }

            if ("ok".equals(expect.get("outcome"))) {
                assertTrue(error == null, id + ": must succeed, got " + error);
                if (expect.containsKey("rows")) {
                    assertEquals(expect.get("rows"), JSON.readValue(JSON.writeValueAsString(rows), Object.class), id + ": rows");
                }
            } else {
                assertNotNull(error, id + ": must fail");
                Map<String, Object> e = map(expect.get("error"));
                assertEquals(((Number) e.get("status")).intValue(), error.problem().status(), id + ": status");
                assertEquals(e.get("class"), error.errorClass().wire(), id + ": class");
                String kind = error instanceof AuditClient.Forbidden ? "forbidden"
                        : error instanceof AuditClient.Unauthorized ? "unauthorized" : "other";
                assertEquals(e.get("kind"), kind, id + ": kind");
                for (Object fragment : list(e.get("messageContains"))) {
                    assertTrue(error.getMessage().contains((String) fragment), id + ": message " + error.getMessage());
                }
                assertFalse(error.getMessage().contains(TOKEN), id + ": the token in an error");
                assertTrue(ErrorClass.PERMANENT == error.errorClass() || ErrorClass.TRANSIENT == error.errorClass());
            }
        } finally {
            server.stop(0);
        }
    }

    // ---- notifications ------------------------------------------------------------------------------

    /**
     * {@code Notifications.send} against a platform stub that answers the n-th request with {@code input.platform[n]}
     * (the last one repeats): how many requests the SDK sends, that EVERY one of them is {@code expect.request} (method,
     * path, the module token, the body -- a retry sends the same idempotency key), and what it makes of the answer. The
     * runner shortens the retry schedule to three steps of 10 ms (the default has three steps too).
     */
    private static void notifications(String id, Map<String, Object> input, Map<String, Object> expect) throws Exception {
        List<Object> answers = list(input.get("platform"));
        List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("method", exchange.getRequestMethod());
                request.put("path", exchange.getRequestURI().getRawPath());
                Map<String, Object> headers = new LinkedHashMap<>();
                headers.put("Authorization", exchange.getRequestHeaders().getFirst("Authorization"));
                request.put("headers", headers);
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                request.put("body", body.isEmpty() ? null : JSON.readValue(body, Object.class));
                Map<String, Object> answer = map(answers.get(Math.min(seen.size(), answers.size() - 1)));
                seen.add(request);
                byte[] bytes = ((String) answer.get("body")).getBytes(StandardCharsets.UTF_8);
                int status = ((Number) answer.get("status")).intValue();
                exchange.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            }
        });
        server.start();
        try {
            KfModule kf = KfModule.of("http://127.0.0.1:" + server.getAddress().getPort(), (String) input.get("slug"),
                    TOKEN, "conformance-notifications-secret", null);
            kf.notifications().withRetryDelays(List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10)));
            if (!"send".equals(input.get("call"))) fail(id + ": unknown call " + input.get("call"));
            Map<String, Object> o = map(input.get("options"));
            NotificationOptions options = o == null ? NotificationOptions.none() : new NotificationOptions(
                    (String) o.get("deepLink"),
                    o.get("channels") == null ? null : list(o.get("channels")).stream().map(String.class::cast).toList(),
                    o.get("idempotencyKey") == null ? null : UUID.fromString((String) o.get("idempotencyKey")),
                    o.get("recipientRoles") == null ? null
                            : list(o.get("recipientRoles")).stream().map(String.class::cast).toList());
            @SuppressWarnings("unchecked")
            Map<String, Object> variables = (Map<String, Object>) input.get("variables");
            Notifications.Result result = null;
            KfException error = null;
            try {
                result = kf.notifications().send(UUID.fromString((String) input.get("tenantId")), (String) input.get("key"),
                        list(input.get("recipients")).stream().map(String.class::cast).toList(), variables, options);
            } catch (KfException e) {
                error = e;
            }

            assertEquals(((Number) expect.get("requests")).intValue(), seen.size(), id + ": requests sent");
            Map<String, Object> request = map(expect.get("request"));
            for (Map<String, Object> one : seen) {
                assertEquals(JSON.readValue(JSON.writeValueAsString(request), Object.class),
                        JSON.readValue(JSON.writeValueAsString(one), Object.class), id + ": request");
            }

            if ("ok".equals(expect.get("outcome"))) {
                assertTrue(error == null, id + ": must succeed, got " + error);
                Map<String, Object> r = map(expect.get("result"));
                assertEquals(((Number) r.get("accepted")).intValue(), result.accepted(), id + ": accepted");
                assertEquals(((Number) r.get("duplicates")).intValue(), result.duplicates(), id + ": duplicates");
            } else {
                assertNotNull(error, id + ": must fail");
                Map<String, Object> e = map(expect.get("error"));
                assertEquals(((Number) e.get("status")).intValue(), error.problem().status(), id + ": status");
                assertEquals(e.get("class"), error.errorClass().wire(), id + ": class");
                String kind = error instanceof Notifications.Rejected ? "rejected" : "other";
                assertEquals(e.get("kind"), kind, id + ": kind");
                if (error instanceof Notifications.Rejected rejected) {
                    assertEquals(e.get("reason"), rejected.reason(), id + ": reason");
                }
                for (Object fragment : list(e.get("messageContains"))) {
                    assertTrue(error.getMessage().contains((String) fragment), id + ": message " + error.getMessage());
                }
                assertFalse(error.getMessage().contains(TOKEN), id + ": the token in an error");
            }
        } finally {
            server.stop(0);
        }
    }

    // ---- the platform services of protocol 1.1: reads, settings, secrets, extensions --------------

    /**
     * One call of a protocol 1.1 client ({@code reads().list/get}, …) against a platform stub, exactly as
     * {@link #audit}: the context of {@code input.context} is signed with {@code ts} = now and verified through
     * {@code KfModule.verifyContext} with {@code input.proof}; {@code input.manifest}, when present, is given to the
     * module ({@code withManifest}); the call is {@code input.call} with {@code input.args}; the server answers
     * {@code input.platform}. {@code expect.request} is the request sent (or {@code null} = none); {@code expect.result}
     * the value returned ({@code null} = an empty object read); {@code expect.error} = {@code {status (0 = local), class,
     * kind: invalid | unauthorized | forbidden | not-found | other, reason?, errors?, messageContains[]}}. Neither the
     * token, nor the proof, nor a secret value of {@code args.value} may appear in an error.
     */
    private static void service(String area, String id, Map<String, Object> input, Map<String, Object> expect) throws Exception {
        String secret = "conformance-service-secret";
        long now = Instant.now().getEpochSecond();
        Map<String, Object> contextJson = new LinkedHashMap<>(map(input.get("context")));
        // protocol 2.0: an after delivery's context for a background job, possibly aged past its window
        boolean delivered = "delivery".equals(input.get("contextSource"));
        long age = input.get("contextAgeSeconds") instanceof Number n ? n.longValue() : 0;
        contextJson.put("ts", now - age);
        String context = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JSON.writeValueAsString(contextJson).getBytes(StandardCharsets.UTF_8));
        String signature = Signatures.sign(secret, String.valueOf(now), (context + ".").getBytes(StandardCharsets.US_ASCII));

        Map<String, Object> answer = map(input.get("platform"));
        Map<String, Object> seen = new LinkedHashMap<>();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                seen.put("method", exchange.getRequestMethod());
                seen.put("path", exchange.getRequestURI().getRawPath());
                seen.put("query", exchange.getRequestURI().getRawQuery());
                Map<String, Object> headers = new LinkedHashMap<>();
                for (String name : List.of("Authorization", "X-KF-Context", "X-KF-Context-Proof")) {
                    headers.put(name, exchange.getRequestHeaders().getFirst(name));
                }
                // protocol 2.0: a command's key, only when sent
                String key = exchange.getRequestHeaders().getFirst(PeersClient.IDEMPOTENCY_HEADER);
                if (key != null) headers.put(PeersClient.IDEMPOTENCY_HEADER, key);
                seen.put("headers", headers);
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                seen.put("body", body.isEmpty() ? null : JSON.readValue(body, Object.class));
                byte[] bytes = ((String) answer.get("body")).getBytes(StandardCharsets.UTF_8);
                int status = ((Number) answer.get("status")).intValue();
                exchange.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            }
        });
        server.start();
        try {
            KfModule kf = KfModule.of("http://127.0.0.1:" + server.getAddress().getPort(), (String) input.get("slug"),
                    TOKEN, secret, null);
            if (input.get("manifest") != null) kf.withManifest(JSON.writeValueAsString(input.get("manifest")));
            RequestContext ctx = delivered ? null : kf.verifyContext(context, String.valueOf(now), signature,
                    (String) input.get("proof"), new byte[0]);
            HookEvents.DeliveryContext delivery = delivered
                    ? HookEvents.DeliveryContext.of(context, (String) input.get("proof")) : null;
            Map<String, Object> args = map(input.get("args"));
            String call = area + "." + input.get("call");
            Object result = null;
            KfException error = null;
            try {
                result = call(id, call, kf, ctx, delivery, args);
            } catch (KfException e) {
                error = e;
            }

            Map<String, Object> request = map(expect.get("request"));
            if (request == null) {
                assertEquals(0, requests.get(), id + ": no request may be sent");
            } else {
                assertEquals(1, requests.get(), id + ": exactly one request");
                Map<String, Object> wanted = new LinkedHashMap<>(request);
                Map<String, Object> wantedHeaders = new LinkedHashMap<>(map(request.get("headers")));
                wantedHeaders.replaceAll((k, v) -> "$context".equals(v) ? context : v);
                wanted.put("headers", wantedHeaders);
                assertEquals(JSON.readValue(JSON.writeValueAsString(wanted), Object.class),
                        JSON.readValue(JSON.writeValueAsString(seen), Object.class), id + ": request");
            }

            if ("ok".equals(expect.get("outcome"))) {
                assertTrue(error == null, id + ": must succeed, got " + error);
                assertEquals(JSON.readValue(JSON.writeValueAsString(expect.get("result")), Object.class),
                        JSON.readValue(JSON.writeValueAsString(result), Object.class), id + ": result");
            } else {
                assertNotNull(error, id + ": must fail");
                Map<String, Object> e = map(expect.get("error"));
                assertEquals(((Number) e.get("status")).intValue(), error.problem().status(), id + ": status");
                assertEquals(e.get("class"), error.errorClass().wire(), id + ": class");
                String kind = error instanceof PlatformRefusal refusal ? refusal.kind().wire() : "other";
                assertEquals(e.get("kind"), kind, id + ": kind");
                if (e.containsKey("reason")) {
                    assertEquals(e.get("reason"), ((PlatformRefusal) error).reason(), id + ": reason");
                }
                if (e.containsKey("errors")) {
                    assertEquals(e.get("errors"), ((PlatformRefusal) error).errors(), id + ": errors");
                }
                for (Object fragment : list(e.get("messageContains"))) {
                    assertTrue(error.getMessage().contains((String) fragment), id + ": message " + error.getMessage());
                }
                assertFalse(error.getMessage().contains(TOKEN), id + ": the token in an error");
                if (input.get("proof") instanceof String proof) {
                    assertFalse(error.getMessage().contains(proof), id + ": the proof in an error");
                }
                if (args.get("value") instanceof String value && !value.isEmpty()) {
                    assertFalse(error.getMessage().contains(value), id + ": the secret value in an error");
                }
            }
        } finally {
            server.stop(0);
        }
    }

    /** The call of {@link #service}, by {@code <area>.<call>}; the value it returns, as the fixtures write it. */
    private static Object call(String id, String call, KfModule kf, RequestContext ctx,
                               HookEvents.DeliveryContext delivery, Map<String, Object> args) {
        switch (call) {
            case "peers.read" -> {
                @SuppressWarnings("unchecked")
                Map<String, String> query = (Map<String, String>) args.get("query");
                PeersClient.Answer a = delivery != null
                        ? kf.peers().read(delivery, (String) args.get("owner"), (String) args.get("port"), query)
                        : kf.peers().read(ctx, (String) args.get("owner"), (String) args.get("port"), query);
                return peerAnswer(a);
            }
            case "peers.command" -> {
                String key = (String) args.get("idempotencyKey");
                PeersClient.Answer a = delivery != null
                        ? kf.peers().command(delivery, (String) args.get("owner"), (String) args.get("name"), key, args.get("body"))
                        : kf.peers().command(ctx, (String) args.get("owner"), (String) args.get("name"), key, args.get("body"));
                return peerAnswer(a);
            }
            case "events.emit" -> {
                EventsClient.Emitted e = delivery != null
                        ? kf.events().emit(delivery, (String) args.get("name"), map(args.get("data")))
                        : kf.events().emit(ctx, (String) args.get("name"), map(args.get("data")));
                Map<String, Object> shape = new LinkedHashMap<>();
                shape.put("eventId", e.eventId());
                shape.put("type", e.type());
                return shape;
            }
            case "reads.list" -> {
                @SuppressWarnings("unchecked")
                Map<String, String> filters = (Map<String, String>) args.get("filters");
                ReadPage page = args.containsKey("size")
                        ? kf.reads().list(ctx, (String) args.get("port"), filters, ((Number) args.get("page")).intValue(),
                        ((Number) args.get("size")).intValue())
                        : kf.reads().list(ctx, (String) args.get("port"), filters);
                Map<String, Object> shape = new LinkedHashMap<>();
                shape.put("items", page.items());
                shape.put("page", page.page());
                shape.put("size", page.size());
                shape.put("total", page.total());
                return shape;
            }
            case "reads.get" -> {
                String port = (String) args.get("port");
                String objectId = (String) args.get("id");
                return (args.containsKey("include")
                        ? kf.reads().get(ctx, port, objectId, list(args.get("include")).stream().map(String.class::cast).toList())
                        : kf.reads().get(ctx, port, objectId)).orElse(null);
            }
            case "settings.effective" -> {
                return kf.settings().effective(ctx);
            }
            case "secrets.write" -> {
                return kf.secrets().write(ctx, (String) args.get("name"), (String) args.get("value"));
            }
            case "secrets.resolve" -> {
                return kf.secrets().resolve(ctx, (String) args.get("ref")).value();
            }
            case "storage.uploadUrl" -> {
                StorageClient.UploadUrl u = kf.storage().uploadUrl(ctx, (String) args.get("contentType"),
                        ((Number) args.get("size")).longValue());
                Map<String, Object> shape = new LinkedHashMap<>();
                shape.put("key", u.key());
                shape.put("url", u.url());
                shape.put("method", u.method());
                shape.put("headers", u.headers());
                shape.put("expiresAt", u.expiresAt());
                return shape;
            }
            case "storage.downloadUrl" -> {
                StorageClient.DownloadUrl d = kf.storage().downloadUrl(ctx, (String) args.get("key"));
                Map<String, Object> shape = new LinkedHashMap<>();
                shape.put("url", d.url());
                shape.put("expiresAt", d.expiresAt());
                return shape;
            }
            case "extensions.write" -> {
                return kf.extensions().write(ctx, (String) args.get("entity"), (String) args.get("id"), map(args.get("values")));
            }
            case "services.upsert" -> {
                // protocol 2.0 (sdk-protocol.md 16.6): order.services.v1; the amount as text keeps 12.50 exact
                Object amount = args.get("amount");
                OrderServices.Line line = new OrderServices.Line((String) args.get("orderId"), (String) args.get("entryId"),
                        (String) args.get("name"), amount == null ? null : new java.math.BigDecimal(String.valueOf(amount)),
                        (String) args.get("currency"), (String) args.get("externalRef"));
                if (delivery != null) kf.orderServices().upsert(delivery, line);
                else kf.orderServices().upsert(ctx, line);
                return null;
            }
            case "services.delete" -> {
                if (delivery != null) {
                    kf.orderServices().delete(delivery, (String) args.get("orderId"), (String) args.get("externalRef"));
                } else {
                    kf.orderServices().delete(ctx, (String) args.get("orderId"), (String) args.get("externalRef"));
                }
                return null;
            }
            default -> {
                fail(id + ": unknown call " + call);
                return null;
            }
        }
    }

    /** A peer's answer as the fixtures write it: {@code {status, body}}. */
    private static Map<String, Object> peerAnswer(PeersClient.Answer a) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("status", a.status());
        shape.put("body", a.body());
        return shape;
    }

    /**
     * Protocol 2.0 (§6.3): a module event {@code input.envelope}, signed with {@code ts} = now,
     * delivered to a module subscribed with {@code events().on(input.subscribe.owner, input.subscribe.name)}, with the
     * delivery's {@code X-KF-Context} (built from {@code input.context}, {@code ts} = now) and {@code input.proof}.
     * {@code expect.status} is the answer; {@code expect.handler} what the handler saw: {@code owner}, {@code name},
     * {@code eventId}, {@code tenant}, {@code data} and {@code deliveryContext} = {@code {tenant, contextHeader
     * ("$context" = the header sent), proof}} or {@code null} when none.
     */
    private static void moduleEvent(String id, Map<String, Object> input, Map<String, Object> expect) {
        long now = Instant.now().getEpochSecond();
        String header = null;
        if (input.get("context") != null) {
            Map<String, Object> contextJson = new LinkedHashMap<>(map(input.get("context")));
            contextJson.put("ts", now);
            header = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(JSON.writeValueAsString(contextJson).getBytes(StandardCharsets.UTF_8));
        }
        KfModule kf = KfModule.of("http://127.0.0.1:1", "conformance", TOKEN, SECRET, null);
        Map<String, Object> subscribe = map(input.get("subscribe"));
        List<HookEvents.ModuleEvent> seen = new CopyOnWriteArrayList<>();
        kf.events().on((String) subscribe.get("owner"), (String) subscribe.get("name"), seen::add);
        byte[] bytes = JSON.writeValueAsBytes(input.get("envelope"));
        String ts = String.valueOf(now);
        HookResult result = kf.handle(bytes, ts, Signatures.sign(SECRET, ts, bytes), header, (String) input.get("proof"), now);
        assertEquals(((Number) expect.get("status")).intValue(), result.status(), id + ": status; body " + result.body());
        Map<String, Object> handler = map(expect.get("handler"));
        if (handler == null) {
            assertTrue(seen.isEmpty(), id + ": the handler must not be called");
            return;
        }
        assertEquals(1, seen.size(), id + ": the handler was called " + seen.size() + " times");
        HookEvents.ModuleEvent ev = seen.getFirst();
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("owner", ev.owner());
        actual.put("name", ev.name());
        actual.put("eventId", ev.eventId());
        actual.put("tenant", ev.tenant());
        actual.put("data", ev.data());
        actual.put("deliveryContext", ev.deliveryContext().map(d -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("tenant", d.tenant());
            c.put("contextHeader", d.contextHeader());
            c.put("proof", d.proof());
            return (Object) c;
        }).orElse(null));
        Map<String, Object> wanted = new LinkedHashMap<>(handler);
        if (wanted.get("deliveryContext") instanceof Map<?, ?> dc) {
            Map<String, Object> d = new LinkedHashMap<>(map(dc));
            if ("$context".equals(d.get("contextHeader"))) d.put("contextHeader", header);
            wanted.put("deliveryContext", d);
        }
        assertEquals(JSON.readValue(JSON.writeValueAsString(wanted), Object.class),
                JSON.readValue(JSON.writeValueAsString(actual), Object.class), id + ": handler");
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    static void await(String id, BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail(id + ": condition not reached within " + timeoutMs + " ms");
            Thread.sleep(10);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o == null ? null : (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o == null ? List.of() : (List<Object>) o;
    }
}
