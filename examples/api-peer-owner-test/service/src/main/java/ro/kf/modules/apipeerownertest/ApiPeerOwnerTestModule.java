package ro.kf.modules.apipeerownertest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ro.kf.sdk.EventsClient;
import ro.kf.sdk.HookResult;
import ro.kf.sdk.KfException;
import ro.kf.sdk.KfHttpServer;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.RequestContext;
import ro.kf.sdk.Surface;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The test module {@code api-peer-owner-test} (phase 230, plan 230-32, protocol 2.0, D-230-15): the OWNER of the proof
 * between modules. It offers to other modules (manifest {@code schemaVersion} 3):
 * <ul>
 *   <li>the read port {@code m.api-peer-owner-test.items.v1} = {@code GET /items} (the items of the context's tenant);</li>
 *   <li>the command {@code register-item} = {@code POST /items {name}}: registers an item in memory, answers
 *       {@code 201 {item, eventId}} and emits the event {@code item-registered} through the platform
 *       ({@code kf.events().emit});</li>
 *   <li>the public slot {@code m.api-peer-owner-test.panel.sections.v1} (kind A, type extension), mounted by its page
 *       {@code module.pages / panel} with the host's {@code Slot} (host API 5).</li>
 * </ul>
 * The items are kept in memory, per tenant (a restart forgets them): a test module, never a KF module.
 */
public final class ApiPeerOwnerTestModule {

    static final String SLUG = "api-peer-owner-test";
    static final String UI = "/m/" + SLUG + "/ui/surfaces.mjs";
    static final String EVENT = "item-registered";
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ASSET = Pattern.compile("^/ui/[a-z0-9-]+\\.(mjs|js|css)$");

    /** The items of each tenant, in the order they were registered. */
    static final class Store {
        private final Map<String, List<Map<String, Object>>> byTenant = new ConcurrentHashMap<>();
        private final AtomicLong sequence = new AtomicLong();

        List<Map<String, Object>> items(String tenant) {
            return List.copyOf(byTenant.getOrDefault(tenant, List.of()));
        }

        Map<String, Object> register(String tenant, String name, String by) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "item-" + sequence.incrementAndGet());
            item.put("name", name);
            item.put("registeredBy", by);
            byTenant.computeIfAbsent(tenant, t -> new CopyOnWriteArrayList<>()).add(item);
            return item;
        }
    }

    private ApiPeerOwnerTestModule() {
    }

    public static void main(String[] args) throws IOException {
        KfModule kf = configure(KfModule.fromEnv());
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        install(server, kf, new Store());
        server.start();
        kf.start();
        System.getLogger(SLUG).log(System.Logger.Level.INFO, SLUG + " listening on " + port);
    }

    /** The module page that mounts the slot (module.pages, page "panel"). */
    static KfModule configure(KfModule kf) {
        kf.ui().contribute("module.pages",
                Surface.react(UI, "PeerOwnerPanelPage").labelKey("page.panel").key("panel").peer("react", "^19.2"));
        return kf;
    }

    /** The hook endpoint, the route {@code /items} (GET read, POST command), the UI files and {@code /health}. */
    static void install(HttpServer server, KfModule kf, Store store) {
        KfHttpServer.mount(server, kf);
        KfHttpServer.route(server, kf, "/items", (ctx, exchange, body) -> "POST".equals(exchange.getRequestMethod())
                ? register(kf, store, ctx, body) : list(store, ctx));
        server.createContext("/ui/", ApiPeerOwnerTestModule::asset);
        server.createContext("/health", exchange -> health(kf, exchange));
    }

    /** The read {@code m.api-peer-owner-test.items.v1}: {@code 200 {items}} of the context's tenant. */
    static HookResult list(Store store, RequestContext ctx) {
        return json(200, Map.of("items", store.items(ctx.tenant())));
    }

    /**
     * The command {@code register-item}: {@code {name}} (1–100 characters) → {@code 201 {item, eventId}}, after the event
     * {@code item-registered} was accepted by the platform; a body without a name → {@code 400}.
     */
    static HookResult register(KfModule kf, Store store, RequestContext ctx, byte[] body) {
        Object name;
        try {
            Object parsed = body.length == 0 ? null : JSON.readValue(body, Object.class);
            name = parsed instanceof Map<?, ?> m ? m.get("name") : null;
        } catch (RuntimeException notJson) {
            name = null;
        }
        if (!(name instanceof String n) || n.isBlank() || n.length() > 100) {
            return json(400, Map.of("error", "the body must be {name: text of 1 to 100 characters}"));
        }
        String by = ctx.actor().via() == null ? "user" : ctx.actor().via();
        Map<String, Object> item = store.register(ctx.tenant(), n, by);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", item);
        try {
            EventsClient.Emitted emitted = kf.events().emit(ctx, EVENT, item);
            out.put("eventId", emitted.eventId());
        } catch (KfException notEmitted) {
            out.put("eventId", null);
            out.put("eventError", notEmitted.getMessage());
        }
        return json(201, out);
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
            try (InputStream in = ApiPeerOwnerTestModule.class.getResourceAsStream("/static" + path)) {
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
            byte[] bytes = JSON.writeValueAsString(out).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", HookResult.CONTENT_TYPE);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
