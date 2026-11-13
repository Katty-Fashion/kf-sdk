package ro.kf.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KfModule#start()} checks {@code kf-module-conf.json} locally before the first request: a violation is a
 * permanent error, nothing is sent.
 */
class LocalManifestCheckTest {

    private static final String HOOKABLE_GET = """
            {"schemaVersion":2,"slug":"local-check","version":"1.0.0","platform":">=4.1","name":"module.name",
             "i18n":{"en":{"module.name":"Local check"}},
             "capabilities":{"routes":[{"method":"GET","path":"/m/local-check/events","right":"api.v1.m.local-check.events.get","hookable":true}]}}
            """;

    @Test
    @DisplayName("hookable on a GET route stops the module before the first request, naming the route")
    void hookableGetStopsBeforeTheFirstRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer platform = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        platform.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        platform.start();
        try {
            Path manifest = Files.createTempFile("kf-module-conf", ".json");
            Files.writeString(manifest, HOOKABLE_GET);
            KfModule kf = KfModule.fromEnv(Map.of(
                    "KF_PLATFORM_URL", "http://127.0.0.1:" + platform.getAddress().getPort(),
                    "KF_MODULE_SLUG", "local-check", "KF_MODULE_TOKEN", "kfm_local", "KF_HOOK_SECRET", "s",
                    "KF_MODULE_MANIFEST", manifest.toString()));
            kf.start();
            Thread.sleep(200);

            assertTrue(kf.isRegistrationStopped());
            assertEquals(0, requests.get(), "nothing may be sent");
            assertEquals(ErrorClass.PERMANENT, kf.lastRegistrationProblem().errorClass());
            String error = kf.lastRegistrationError();
            assertTrue(error.contains("GET /m/local-check/events"), error);
            assertTrue(error.contains("hookable only on POST, PUT or PATCH"), error);
        } finally {
            platform.stop(0);
        }
    }

    @Test
    @DisplayName("without a manifest file the local check is skipped (warning), the registration proceeds")
    void missingManifestSkipsTheCheck() {
        KfModule kf = KfModule.fromEnv(Map.of("KF_MODULE_MANIFEST", "does-not-exist/kf-module-conf.json"));
        assertNull(kf.manifest());
        kf.start(); // no platform env: only the warning, no thread
        assertTrue(!kf.isRegistrationStopped());
    }

    @Test
    @DisplayName("SdkVersion carries the pom version and the manifest schemaVersion 3 (protocol 2.0)")
    void sdkVersion() {
        assertNotNull(SdkVersion.SDK_VERSION);
        assertTrue(SdkVersion.SDK_VERSION.matches("[0-9]+[.][0-9]+[.][0-9]+.*"), SdkVersion.SDK_VERSION);
        assertEquals(3, SdkVersion.PROTOCOL_SCHEMA_VERSION);
    }
}
