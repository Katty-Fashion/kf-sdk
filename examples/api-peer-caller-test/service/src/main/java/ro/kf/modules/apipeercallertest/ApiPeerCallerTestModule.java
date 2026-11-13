package ro.kf.modules.apipeercallertest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ro.kf.sdk.HookEvents;
import ro.kf.sdk.HookResult;
import ro.kf.sdk.KfException;
import ro.kf.sdk.KfHttpServer;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.PeersClient;
import ro.kf.sdk.PlatformRefusal;
import ro.kf.sdk.RequestContext;
import ro.kf.sdk.Surface;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The test module {@code api-peer-caller-test} (protocol 2.0): the CALLER of the proof
 * between modules. It requires {@code api-peer-owner-test} as OPTIONAL ({@code requires[].optional}) and:
 * <ul>
 *   <li>reads its port {@code m.api-peer-owner-test.items.v1} and calls its command {@code register-item}, through the
 *       platform ({@code kf.peers()}, {@code capabilities.peers});</li>
 *   <li>subscribes to {@code m.api-peer-owner-test.after.item-registered}: the handler keeps the last event and, with the
 *       delivery's context, reads the owner's port again as a background job would;</li>
 *   <li>contributes a panel to the owner's slot {@code m.api-peer-owner-test.panel.sections.v1} (it shows the last item
 *       read, {@code GET /latest});</li>
 *   <li>{@code GET /probe} reports, for the human proof: the read, the command with a key (201 expected), the
 *       command WITHOUT a key sent by a plain HTTP client (the SDK refuses it locally; the platform answers 400), whether
 *       the owner is unavailable ({@code MODULE_UNAVAILABLE}, typed) and the last event received. Always {@code 200}.</li>
 * </ul>
 */
public final class ApiPeerCallerTestModule {

    static final String SLUG = "api-peer-caller-test";
    static final String OWNER = "api-peer-owner-test";
    static final String PORT = "m.api-peer-owner-test.items.v1";
    static final String COMMAND = "register-item";
    static final String EVENT = "item-registered";
    static final String SLOT = "m.api-peer-owner-test.panel.sections.v1";
    static final String UI = "/m/" + SLUG + "/ui/surfaces.mjs";
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ASSET = Pattern.compile("^/ui/[a-z0-9-]+\\.(mjs|js|css)$");

    /** Where the platform is, for the command sent without the SDK (no key): {@code KF_PLATFORM_URL}, the token. */
    record RawPlatform(String url, String token) {
    }

    /** What the module remembers between requests (in memory). */
    static final class State {
        final AtomicReference<Map<String, Object>> lastEvent = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> lastJobRead = new AtomicReference<>();
    }

    private ApiPeerCallerTestModule() {
    }

    public static void main(String[] args) throws IOException {
        State state = new State();
        KfModule kf = configure(KfModule.fromEnv(), state);
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        install(server, kf, state, new RawPlatform(System.getenv("KF_PLATFORM_URL"), System.getenv("KF_MODULE_TOKEN")));
        server.start();
        kf.start();
        System.getLogger(SLUG).log(System.Logger.Level.INFO, SLUG + " listening on " + port);
    }

    /** The contribution to the owner's slot and the subscription to its event. */
    static KfModule configure(KfModule kf, State state) {
        kf.ui().contribute(SLOT, Surface.react(UI, "PeerCallerSlotPanel").labelKey("slot.contribution").peer("react", "^19.2"));
        kf.events().on(OWNER, EVENT, ev -> onItemRegistered(kf, state, ev));
        return kf;
    }

    /** The event handler: keeps the event and, with the delivery's context, reads the owner's port again. */
    static void onItemRegistered(KfModule kf, State state, HookEvents.ModuleEvent ev) {
        Map<String, Object> seen = new LinkedHashMap<>();
        seen.put("eventId", ev.eventId());
        seen.put("tenant", ev.tenant());
        seen.put("data", ev.data());
        seen.put("withDeliveryContext", ev.deliveryContext().isPresent());
        state.lastEvent.set(seen);
        ev.deliveryContext().ifPresent(delivery -> state.lastJobRead.set(read(() ->
                kf.peers().read(delivery, OWNER, PORT, Map.of()))));
    }

    static void install(HttpServer server, KfModule kf, State state, RawPlatform raw) {
        KfHttpServer.mount(server, kf);
        KfHttpServer.route(server, kf, "/probe", (ctx, exchange, body) -> probe(kf, state, raw, ctx, exchange));
        KfHttpServer.route(server, kf, "/latest", (ctx, exchange, body) -> latest(kf, ctx));
        server.createContext("/ui/", ApiPeerCallerTestModule::asset);
        server.createContext("/health", exchange -> health(kf, exchange));
    }

    /** {@code GET /probe}: everything the proof of looks at, in one answer, always {@code 200}. */
    static HookResult probe(KfModule kf, State state, RawPlatform raw, RequestContext ctx, HttpExchange exchange) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> read = read(() -> kf.peers().read(ctx, OWNER, PORT, Map.of()));
        out.put("read", read);
        out.put("commandWithKey", read(() -> kf.peers().command(ctx, OWNER, COMMAND, PeersClient.newIdempotencyKey(),
                Map.of("name", "probe-" + System.currentTimeMillis()))));
        out.put("commandWithoutKey", commandWithoutKey(raw, exchange));
        out.put("ownerUnavailable", PeersClient.MODULE_UNAVAILABLE.equals(read.get("reason")));
        out.put("lastEvent", state.lastEvent.get());
        out.put("lastJobRead", state.lastJobRead.get());
        return json(200, out);
    }

    /** {@code GET /latest}: the last item of the owner, for the panel in the slot; {@code {item: null}} when none. */
    static HookResult latest(KfModule kf, RequestContext ctx) {
        Map<String, Object> read = read(() -> kf.peers().read(ctx, OWNER, PORT, Map.of()));
        Object item = null;
        if (read.get("body") instanceof Map<?, ?> body && body.get("items") instanceof List<?> items && !items.isEmpty()) {
            item = items.get(items.size() - 1);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", item);
        out.put("read", read);
        return json(200, out);
    }

    @FunctionalInterface
    interface PeerCall {
        PeersClient.Answer call();
    }

    /** A call between modules as {@code {status, body}}, or its refusal as {@code {refused, status, reason, class}}. */
    static Map<String, Object> read(PeerCall call) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            PeersClient.Answer a = call.call();
            out.put("status", a.status());
            out.put("body", a.body());
        } catch (PlatformRefusal refusal) {
            out.put("refused", true);
            out.put("status", refusal.problem().status());
            out.put("kind", refusal.kind().wire());
            out.put("reason", refusal.reason());
            out.put("class", refusal.errorClass() == null ? null : refusal.errorClass().wire());
        } catch (KfException local) {
            out.put("refused", true);
            out.put("status", local.problem().status());
            out.put("reason", "local");
            out.put("detail", local.getMessage());
        }
        return out;
    }

    /**
     * The command sent WITHOUT {@code Idempotency-Key} by a plain HTTP client (the SDK would refuse it locally): the
     * platform's answer, {@code {status, reason}} -- {@code 400 idempotency-key-required} expected. The context and its
     * proof are those the platform put on this request.
     */
    static Map<String, Object> commandWithoutKey(RawPlatform raw, HttpExchange exchange) {
        Map<String, Object> out = new LinkedHashMap<>();
        String context = exchange.getRequestHeaders().getFirst(RequestContext.CONTEXT_HEADER);
        String proof = exchange.getRequestHeaders().getFirst(RequestContext.PROOF_HEADER);
        if (raw == null || raw.url() == null || raw.token() == null || context == null || proof == null) {
            out.put("status", null);
            out.put("reason", "not-sent: no platform url, token, context or proof");
            return out;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(raw.url().replaceAll("/+$", "") + "/api/v1/platform/modules/"
                        + SLUG + "/peers/" + OWNER + "/commands/" + COMMAND))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + raw.token())
                .header("Content-Type", "application/json")
                .header(RequestContext.CONTEXT_HEADER, context)
                .header(RequestContext.PROOF_HEADER, proof)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"without-key\"}", StandardCharsets.UTF_8))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            out.put("status", response.statusCode());
            Object reason = null;
            try {
                Object body = JSON.readValue(response.body(), Object.class);
                if (body instanceof Map<?, ?> m) {
                    // the protocol's reason: the body's, or the last segment of the problem type
                    reason = m.get("reason") != null ? m.get("reason")
                            : m.get("type") instanceof String type ? type.substring(type.lastIndexOf('/') + 1) : null;
                }
            } catch (RuntimeException notJson) {
                reason = null;
            }
            out.put("reason", reason);
        } catch (IOException e) {
            out.put("status", null);
            out.put("reason", "unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.put("status", null);
            out.put("reason", "interrupted");
        }
        return out;
    }

    static HookResult json(int status, Object body) {
        return new HookResult(status, JSON.writeValueAsString(body));
    }

    /** A file of {@code static/ui/} on the classpath, with an explicit MIME type and {@code no-cache}; 404 otherwise. */
    static void asset(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!"GET".equals(exchange.getRequestMethod()) || !ASSET.matcher(path).matches()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes;
            try (InputStream in = ApiPeerCallerTestModule.class.getResourceAsStream("/static" + path)) {
                if (in == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                bytes = in.readAllBytes();
            }
            String type = path.endsWith(".css") ? "text/css; charset=utf-8" : "text/javascript; charset=utf-8";
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static void health(KfModule kf, HttpExchange exchange) throws IOException {
        try (exchange) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "UP");
            out.put("module", SLUG);
            out.put("registered", kf.isRegistered());
            out.put("registrationError", kf.lastRegistrationError());
            out.put("registrationWarnings", kf.registrationWarnings());
            byte[] bytes = JSON.writeValueAsString(out).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", HookResult.CONTENT_TYPE);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
