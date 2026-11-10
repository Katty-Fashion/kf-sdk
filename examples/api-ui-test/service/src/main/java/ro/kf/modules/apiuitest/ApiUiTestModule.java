package ro.kf.modules.apiuitest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ro.kf.sdk.HookResult;
import ro.kf.sdk.KfHttpServer;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.RequestContext;
import ro.kf.sdk.Surface;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The test module {@code api-ui-test} (quick 261026-pachete-test): one React contribution on each UI surface of protocol
 * 1.1, from one hand-written ES module ({@code static/ui/surfaces.mjs}, no build step), so that the scenarios 213-17.1,
 * 213-17.3, 213-18.1, 213-18.2 and 213-18.3 can be checked on a stack through a real module.
 *
 * <ul>
 *   <li>{@code module.pages}, page {@code demo} → {@code ApiUiDemoPage} (213-17.1; the menu entry is {@code nav[]}
 *       staff → {@code /m/api-ui-test/demo});</li>
 *   <li>{@code setup.sections}, section {@code servers} → {@code ApiUiServersSection} and
 *       {@code tenant-management.cards} → {@code ApiUiTenantCard} (213-17.3; {@code nav[]} platform →
 *       {@code /setup/m/api-ui-test/servers});</li>
 *   <li>{@code orderflow.rail.sections} → {@code ApiUiOrderPanel} (label "AAS", a button that calls
 *       {@code refresh()}, 213-18.1) and {@code orderflow.review.after} → {@code ApiUiOrderReview} (213-18.2).</li>
 * </ul>
 * With {@code API_UI_TEST_CRASH=1} (set by the compose of the "crapa" pack, 213-18.3) the two order contributions are
 * registered on {@code ApiUiOrderPanelCrash} / {@code ApiUiOrderReviewCrash}, which throw while rendering; the manifest
 * is the same (it does not name exports).
 *
 * <p>The UI is served at {@code /ui/**} without a context (the platform's asset proxy fetches it; {@code .mjs} as
 * {@code text/javascript}, sdk-protocol §7.10). {@code GET /info} answers the caller's context (tenant, user, locale,
 * rights), for the demo page.
 */
public final class ApiUiTestModule {

    static final String SLUG = "api-ui-test";
    static final String UI = "/m/" + SLUG + "/ui/surfaces.mjs";
    static final String CRASH_ENV = "API_UI_TEST_CRASH";
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ASSET = Pattern.compile("^/ui/[a-z0-9-]+\\.(mjs|js|css)$");

    private ApiUiTestModule() {
    }

    public static void main(String[] args) throws IOException {
        boolean crash = "1".equals(System.getenv(CRASH_ENV));
        KfModule kf = configure(KfModule.fromEnv(), crash);
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        install(server, kf);
        server.start();
        kf.start();
        System.getLogger(SLUG).log(System.Logger.Level.INFO, SLUG + " listening on " + port
                + (crash ? " (crash variant: the order contributions throw)" : ""));
    }

    /** The contributions; on a surface of type {@code page} the key is the page (§15.6). */
    static KfModule configure(KfModule kf, boolean crash) {
        kf.ui().contribute("module.pages",
                Surface.react(UI, "ApiUiDemoPage").labelKey("page.demo").key("demo").peer("react", "^19.2"));
        kf.ui().contribute("setup.sections",
                Surface.react(UI, "ApiUiServersSection").labelKey("section.servers").key("servers")
                        .peer("react", "^19.2"));
        kf.ui().contribute("tenant-management.cards",
                Surface.react(UI, "ApiUiTenantCard").labelKey("card.tenant").key("tenant").peer("react", "^19.2"));
        kf.ui().contribute("orderflow.rail.sections",
                Surface.react(UI, crash ? "ApiUiOrderPanelCrash" : "ApiUiOrderPanel").labelKey("surface.order-panel")
                        .key("order-panel").peer("react", "^19.2"));
        kf.ui().contribute("orderflow.review.after",
                Surface.react(UI, crash ? "ApiUiOrderReviewCrash" : "ApiUiOrderReview")
                        .labelKey("surface.order-review").key("order-review").peer("react", "^19.2"));
        return kf;
    }

    static void install(HttpServer server, KfModule kf) {
        KfHttpServer.mount(server, kf);
        KfHttpServer.route(server, kf, "/info", (ctx, exchange, body) -> info(ctx));
        server.createContext("/ui/", ApiUiTestModule::asset);
        server.createContext("/health", exchange -> health(kf, exchange));
    }

    static HookResult info(RequestContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("module", SLUG);
        out.put("tenant", ctx.tenant());
        out.put("user", ctx.user());
        out.put("locale", ctx.locale());
        out.put("rights", ctx.rights());
        return new HookResult(200, JSON.writeValueAsString(out));
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
            try (InputStream in = ApiUiTestModule.class.getResourceAsStream("/static" + path)) {
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
            byte[] bytes = JSON.writeValueAsBytes(out);
            exchange.getResponseHeaders().set("Content-Type", HookResult.CONTENT_TYPE);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
