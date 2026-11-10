package ro.kf.modules.apisettingstest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.ManifestCheck;
import ro.kf.sdk.testing.FakePlatform;

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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three routes of api-settings-test against the SDK's fake platform. */
class ApiSettingsTestModuleTest {

    private static final String TENANT = "730112ba-1588-4b9d-9b02-a7853f540bf1";
    private static final String OTHER_TENANT = "7860fc2b-18bd-4941-a975-382e19f0d346";
    private static final String SETTINGS = "api.v1.m.api-settings-test.settings.get";
    private static final String WRITE = "api.v1.m.api-settings-test.extensions.model-version.post";

    private final HttpClient http = HttpClient.newHttpClient();
    private String manifest;
    private FakePlatform fake;
    private KfModule kf;
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        manifest = Files.readString(Path.of("..", "kf-module-conf.json"));
        fake = FakePlatform.start("api-settings-test").manifest(manifest);
        kf = ApiSettingsTestModule.configure(fake.module());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ApiSettingsTestModule.install(server, kf);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        fake.close();
    }

    @Test
    void theManifestAndTheRegistrationPassTheSdkCheck() {
        ManifestCheck.Result result = ManifestCheck.check(manifest, kf.registrationBody(), Set.of());
        assertTrue(result.ok(), () -> "errors: " + result.errors());
    }

    @Test
    void settingsAnswerTheTenantsValuesNextToTheDefaults() throws Exception {
        fake.settings(TENANT, "batch-size", 42).settings(TENANT, "push-mode", "manual");

        HttpResponse<String> answer = send("GET", "/settings", SETTINGS, TENANT, null);

        assertEquals(200, answer.statusCode(), answer.body());
        Map<?, ?> body = ApiSettingsTestModule.JSON.readValue(answer.body(), Map.class);
        Map<?, ?> settings = (Map<?, ?>) body.get("settings");
        assertEquals(42, ((Number) settings.get("batch-size")).intValue());
        assertEquals("manual", settings.get("push-mode"));
        assertEquals(false, settings.get("verbose"));
        assertEquals(10, ((Number) ((Map<?, ?>) body.get("defaults")).get("batch-size")).intValue());
        assertEquals(TENANT, body.get("tenant"));
    }

    @Test
    void anotherTenantGetsTheDefaults() throws Exception {
        fake.settings(TENANT, "push-mode", "off");

        HttpResponse<String> answer = send("GET", "/settings", SETTINGS, OTHER_TENANT, null);

        assertEquals(200, answer.statusCode(), answer.body());
        Map<?, ?> settings = (Map<?, ?>) ApiSettingsTestModule.JSON.readValue(answer.body(), Map.class).get("settings");
        assertEquals("auto", settings.get("push-mode"));
        assertEquals(10, ((Number) settings.get("batch-size")).intValue());
    }

    @Test
    void theWriteSendsTheValuesToTheModelVersionExtension() throws Exception {
        HttpResponse<String> answer = send("POST", "/extensions/model-version", WRITE, TENANT,
                "{\"id\":\"17\",\"values\":{\"aas_shell\":\"X\"}}");

        assertEquals(200, answer.statusCode(), answer.body());
        assertEquals("X", fake.extensions(TENANT, "model_version", "17").get("aas_shell"));
        assertTrue(fake.requests().stream().anyMatch(r -> "PATCH".equals(r.method())
                && r.path().endsWith("/extensions/model_version/17")), () -> "requests: " + fake.requests());
    }

    @Test
    void aWriteWithoutIdOrValuesIs400WithoutCallingThePlatform() throws Exception {
        HttpResponse<String> answer = send("POST", "/extensions/model-version", WRITE, TENANT, "{\"values\":{}}");

        assertEquals(400, answer.statusCode(), answer.body());
        assertFalse(fake.requests().stream().anyMatch(r -> r.path().contains("/extensions/")));
    }

    @Test
    void thePlatformsReadOfTheStationsGetsTheStrictShape() throws Exception {
        FakePlatform.SignedContext ctx = fake.systemContext(TENANT, "GET", "/factory/stations");
        HttpResponse<String> answer = http.send(ctx.applyTo(HttpRequest.newBuilder(URI.create(base
                + "/factory/stations"))).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, answer.statusCode(), answer.body());
        Map<?, ?> body = ApiSettingsTestModule.JSON.readValue(answer.body(), Map.class);
        assertEquals(Set.of("stations", "warnings"), body.keySet());
        List<?> stations = (List<?>) body.get("stations");
        assertEquals(5, stations.size());
        for (Object s : stations) {
            Map<?, ?> station = (Map<?, ?>) s;
            assertTrue(Set.of("name", "cellRole", "functions", "complete").containsAll(station.keySet()), station::toString);
            assertTrue(station.get("complete") instanceof Boolean);
        }
    }

    @Test
    void aCallWithoutAContextIs401() throws Exception {
        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(URI.create(base + "/settings")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, answer.statusCode(), answer.body());
    }

    private HttpResponse<String> send(String method, String path, String right, String tenant, String json)
            throws Exception {
        byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        FakePlatform.SignedContext ctx = fake.signedContext(tenant, "u-1", List.of(right), method, path, null, body);
        HttpRequest.Builder request = ctx.applyTo(HttpRequest.newBuilder(URI.create(base + path)));
        request = json == null ? request.GET()
                : request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
