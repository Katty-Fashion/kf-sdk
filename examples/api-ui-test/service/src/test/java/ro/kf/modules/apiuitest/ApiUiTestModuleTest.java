package ro.kf.modules.apiuitest;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The contributions, the UI assets and /info of api-ui-test against the SDK's fake platform. */
class ApiUiTestModuleTest {

    private static final String TENANT = "730112ba-1588-4b9d-9b02-a7853f540bf1";

    private final HttpClient http = HttpClient.newHttpClient();
    private String manifest;
    private FakePlatform fake;
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        manifest = Files.readString(Path.of("..", "kf-module-conf.json"));
        fake = FakePlatform.start("api-ui-test").manifest(manifest);
        KfModule kf = ApiUiTestModule.configure(fake.module(), false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ApiUiTestModule.install(server, kf);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        fake.close();
    }

    @Test
    void theManifestAndBothRegistrationsPassTheSdkCheck() {
        for (boolean crash : new boolean[]{false, true}) {
            KfModule kf = ApiUiTestModule.configure(fake.module(), crash);
            ManifestCheck.Result result = ManifestCheck.check(manifest, kf.registrationBody(), Set.of());
            assertTrue(result.ok(), () -> "crash=" + crash + " errors: " + result.errors());
        }
    }

    @Test
    void theCrashVariantChangesOnlyTheExportsOfTheOrderContributions() {
        String normal = String.valueOf(ApiUiTestModule.configure(fake.module(), false).registrationBody());
        String crash = String.valueOf(ApiUiTestModule.configure(fake.module(), true).registrationBody());
        assertTrue(normal.contains("ApiUiOrderPanel") && !normal.contains("Crash"), normal);
        assertTrue(crash.contains("ApiUiOrderPanelCrash") && crash.contains("ApiUiOrderReviewCrash"), crash);
        assertEquals(normal.replace("ApiUiOrderPanel", "X").replace("ApiUiOrderReview", "Y"),
                crash.replace("ApiUiOrderPanelCrash", "X").replace("ApiUiOrderReviewCrash", "Y"));
    }

    @Test
    void theBundleIsServedAsJavascriptWithoutAContext() throws Exception {
        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(URI.create(base + "/ui/surfaces.mjs")).GET()
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, answer.statusCode());
        assertTrue(answer.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
        for (String export : List.of("ApiUiDemoPage", "ApiUiServersSection", "ApiUiTenantCard", "ApiUiOrderPanel",
                "ApiUiOrderReview", "ApiUiOrderPanelCrash", "ApiUiOrderReviewCrash")) {
            assertTrue(answer.body().contains("export function " + export + "("), export);
        }
    }

    @Test
    void anUnknownOrMalformedAssetIs404() throws Exception {
        for (String path : List.of("/ui/missing.mjs", "/ui/../kf-module-conf.json", "/ui/surfaces.txt")) {
            HttpResponse<String> answer = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, answer.statusCode(), path);
        }
    }

    @Test
    void infoAnswersTheCallersContext() throws Exception {
        FakePlatform.SignedContext ctx = fake.signedContext(TENANT, "u-1", List.of("api.v1.m.api-ui-test.info.get"),
                "GET", "/info");
        HttpResponse<String> answer = http.send(ctx.applyTo(HttpRequest.newBuilder(URI.create(base + "/info"))).GET()
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, answer.statusCode(), answer.body());
        Map<?, ?> body = ApiUiTestModule.JSON.readValue(answer.body(), Map.class);
        assertEquals(TENANT, body.get("tenant"));
        assertEquals("u-1", body.get("user"));
    }

    @Test
    void infoWithoutAContextIs401() throws Exception {
        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(URI.create(base + "/info")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, answer.statusCode(), answer.body());
    }
}
