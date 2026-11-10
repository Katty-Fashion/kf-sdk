package ro.kf.modules.apipeerownertest;

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
 * {@link ApiPeerOwnerTestModule} on the SDK's {@link FakePlatform} (plan 230-32): the manifest passes the SDK's local
 * check (schema 3, the declarations between modules, the slot); the read answers the tenant's items; the command
 * registers an item and emits {@code item-registered} through the platform; the page that mounts the slot is registered.
 */
class ApiPeerOwnerTestModuleTest {

    private static final String TENANT = "00000000-0000-0000-0000-0000000000a1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FakePlatform fake;
    private KfModule kf;
    private HttpServer module;
    private String base;

    @BeforeEach
    void start() throws Exception {
        String manifest = Files.readString(Path.of("..", "kf-module-conf.json"), StandardCharsets.UTF_8);
        fake = FakePlatform.start(ApiPeerOwnerTestModule.SLUG).manifest(manifest);
        kf = ApiPeerOwnerTestModule.configure(fake.module());
        module = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ApiPeerOwnerTestModule.install(module, kf, new ApiPeerOwnerTestModule.Store());
        module.start();
        base = "http://127.0.0.1:" + module.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        module.stop(0);
        fake.close();
    }

    private HttpResponse<String> call(String method, String path, String body, String via) throws Exception {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        FakePlatform.SignedContext ctx = fake.signedContext(TENANT, "sub-1", List.of(), method, path, null, bytes);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(bytes));
        ctx.applyTo(request);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void theManifestPassesTheLocalCheck() throws Exception {
        String manifest = Files.readString(Path.of("..", "kf-module-conf.json"), StandardCharsets.UTF_8);
        var result = ManifestCheck.check(manifest, kf.registrationBody(), java.util.Set.of());
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(3, kf.manifest().schemaVersion());
        assertEquals("panel.sections.v1", kf.manifest().capabilities().slots().get(0).key());
    }

    @Test
    void theCommandRegistersAnItemAndEmitsTheEvent_theReadListsIt() throws Exception {
        HttpResponse<String> created = call("POST", "/items", "{\"name\":\"Button A\"}", null);
        assertEquals(201, created.statusCode(), created.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> answer = JSON.readValue(created.body(), Map.class);
        assertNotNull(answer.get("eventId"), created.body());

        Map<String, Object> emitted = fake.emittedEvents().get(0);
        assertEquals("item-registered", emitted.get("name"));
        assertEquals("m.api-peer-owner-test.after.item-registered", emitted.get("type"));
        assertEquals(TENANT, emitted.get("tenant"));

        HttpResponse<String> listed = call("GET", "/items", null, null);
        assertEquals(200, listed.statusCode(), listed.body());
        assertTrue(listed.body().contains("Button A"), listed.body());
    }

    @Test
    void aCommandWithoutANameIs400() throws Exception {
        assertEquals(400, call("POST", "/items", "{}", null).statusCode());
        assertTrue(fake.emittedEvents().isEmpty());
    }

    @Test
    void thePageThatMountsTheSlotIsRegistered() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> surfaces = (List<Map<String, Object>>) kf.registrationBody().get("surfaces");
        assertEquals("module.pages", surfaces.get(0).get("slug"));
        assertEquals("panel", surfaces.get(0).get("key"));
    }
}
