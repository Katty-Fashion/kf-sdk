package ro.kf.modules.apisettingstest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ro.kf.sdk.FieldType;
import ro.kf.sdk.HookResult;
import ro.kf.sdk.KfException;
import ro.kf.sdk.KfHttpServer;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.Manifest;
import ro.kf.sdk.PlatformRefusal;
import ro.kf.sdk.RequestContext;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The test module {@code api-settings-test}: three services of protocol 1.1, each behind
 * one route, so that the scenarios, and can be checked on a stack through a real module. It
 * has no logic of its own.
 *
 * <ul>
 *   <li>{@code GET /m/api-settings-test/settings} → {@code kf.settings().effective(ctx)} (§15.2): {@code 200
 *       {tenant, settings, defaults}} -- the tenant's effective values, typed by the manifest, next to the declared
 *       defaults.</li>
 *   <li>{@code POST /m/api-settings-test/extensions/model-version} with {@code {"id": "<versionId>", "values":
 *       {"aas_shell": "X"}}} → {@code kf.extensions().write(ctx, "model_version", id, values)} (§15.4): {@code 200
 *       {entity, id, values}} with the module's values after the write; the values are sent as given, so
 *       {@code {"aas_shell": 12}} reaches the platform and comes back as its {@code 400}.</li>
 *   <li>{@code GET /m/api-settings-test/factory/stations}, declared in {@code provides: factory.stations.v1} (§15.5):
 *       the stations in the strict shape of {@code FactoryStationsV1}, and one log line with the caller
 *       ({@code actor.type}, {@code actor.via}, {@code user}): the platform's read is {@code via=platform user=null}
 *      .</li>
 * </ul>
 * A refusal of the platform comes back with its own status and {@code {refused: true, status, kind, reason, detail,
 * errors}}; a local refusal of the SDK or a {@code 503} as {@code 502}. {@code /health} answers without a context, for
 * the host only (it is not declared, so the platform never proxies it).
 */
public final class ApiSettingsTestModule {

    static final String SLUG = "api-settings-test";
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final System.Logger LOG = System.getLogger(SLUG);

    /**
     * The stations this module provides: the capability tokens of the platform's seeded station capabilities
     * ({@code overlock}, {@code buttonhole}, {@code bartack}, {@code fusing_press}, {@code single_needle}, {@code iron},
     * {@code qc}), on names that cannot be mistaken for the monolith's ({@code APITEST-…}), and one placeholder.
     */
    static final List<Map<String, Object>> STATIONS = List.of(
            station("APITEST-WS1", "SewingCell", List.of("single_needle", "overlock"), true),
            station("APITEST-WS2", "SewingCell", List.of("buttonhole", "bartack"), true),
            station("APITEST-PRESS1", "PressingCell", List.of("fusing_press", "iron"), true),
            station("APITEST-QC1", "QCStation", List.of("qc"), true),
            station("APITEST-PLACEHOLDER", null, List.of("overlock"), false));

    private ApiSettingsTestModule() {
    }

    public static void main(String[] args) throws IOException {
        KfModule kf = configure(KfModule.fromEnv());
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        install(server, kf);
        server.start();
        kf.start();
        LOG.log(System.Logger.Level.INFO, SLUG + " listening on " + port);
    }

    /** What the module registers: its extension field on {@code model_version} (declared in the manifest). */
    static KfModule configure(KfModule kf) {
        return kf.registerField("model_version", "aas_shell", FieldType.STRING, "version_aas", "field.aas-shell");
    }

    /** The hook endpoint, the three routes and {@code /health}, on {@code server}. */
    static void install(HttpServer server, KfModule kf) {
        KfHttpServer.mount(server, kf);
        KfHttpServer.route(server, kf, "/settings", (ctx, exchange, body) -> settings(kf, ctx));
        KfHttpServer.route(server, kf, "/extensions/model-version", (ctx, exchange, body) -> writeVersion(kf, ctx,
                exchange, body));
        KfHttpServer.route(server, kf, "/factory/stations", (ctx, exchange, body) -> stations(ctx, exchange));
        server.createContext("/health", exchange -> health(kf, exchange));
    }

    static HookResult settings(KfModule kf, RequestContext ctx) {
        try {
            Map<String, Object> effective = kf.settings().effective(ctx);
            Map<String, Object> defaults = new LinkedHashMap<>();
            for (Manifest.SettingCapability setting : kf.manifest().capabilities().settings()) {
                defaults.put(setting.key(), setting.defaultValue());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tenant", ctx.tenant());
            out.put("settings", effective);
            out.put("defaults", defaults);
            return json(200, out);
        } catch (KfException refused) {
            return refusal("settings", refused);
        }
    }

    static HookResult writeVersion(KfModule kf, RequestContext ctx, HttpExchange exchange, byte[] body) {
        if (!"POST".equals(exchange.getRequestMethod())) {
            return json(405, Map.of("error", "POST {\"id\": \"<versionId>\", \"values\": {\"aas_shell\": \"X\"}}"));
        }
        Map<?, ?> request;
        try {
            request = body.length == 0 ? Map.of() : JSON.readValue(body, Map.class);
        } catch (RuntimeException notJson) {
            return json(400, Map.of("error", "the body must be JSON: {\"id\": \"<versionId>\", \"values\": {…}}"));
        }
        Object id = request.get("id");
        Object values = request.get("values");
        if (id == null || String.valueOf(id).isBlank() || !(values instanceof Map<?, ?> map) || map.isEmpty()) {
            return json(400, Map.of("error", "id and a non-empty values object are required: "
                    + "{\"id\": \"<versionId>\", \"values\": {\"aas_shell\": \"X\"}}"));
        }
        Map<String, Object> sent = new LinkedHashMap<>();
        map.forEach((k, v) -> sent.put(String.valueOf(k), v));
        try {
            Map<String, Object> after = kf.extensions().write(ctx, "model_version", String.valueOf(id), sent);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("entity", "model_version");
            out.put("id", String.valueOf(id));
            out.put("sent", sent);
            out.put("values", after);
            return json(200, out);
        } catch (KfException refused) {
            return refusal("extensions", refused);
        }
    }

    static HookResult stations(RequestContext ctx, HttpExchange exchange) {
        RequestContext.Actor actor = ctx.actor();
        LOG.log(System.Logger.Level.INFO, "factory/stations served: tenant=" + ctx.tenant()
                + " actor.type=" + (actor == null ? null : actor.type().wire())
                + " actor.via=" + (actor == null ? null : actor.via())
                + " user=" + ctx.user()
                + " requestId=" + ctx.requestId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stations", STATIONS);
        out.put("warnings", List.of("served by the test module api-settings-test"));
        return json(200, out);
    }

    /** The platform's refusal with its own status; a local refusal or a 503 as 502 (the platform did not decide). */
    static HookResult refusal(String call, KfException refused) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("call", call);
        out.put("refused", true);
        out.put("status", refused.problem().status());
        out.put("detail", refused.problem().detail());
        out.put("class", refused.errorClass().wire());
        if (refused instanceof PlatformRefusal platform) {
            out.put("kind", platform.kind().wire());
            out.put("reason", platform.reason());
            out.put("errors", platform.errors());
            return json(refused.problem().status(), out);
        }
        out.put("sdk", refused.getMessage());
        return json(502, out);
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

    private static Map<String, Object> station(String name, String cellRole, List<String> functions,
                                               boolean complete) {
        Map<String, Object> station = new LinkedHashMap<>();
        station.put("name", name);
        if (cellRole != null) station.put("cellRole", cellRole);
        station.put("functions", new ArrayList<>(functions));
        station.put("complete", complete);
        return station;
    }

    static HookResult json(int status, Object body) {
        return new HookResult(status, JSON.writeValueAsString(body));
    }
}
