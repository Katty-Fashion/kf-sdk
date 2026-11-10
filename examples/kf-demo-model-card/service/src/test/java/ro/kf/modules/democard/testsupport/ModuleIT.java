package ro.kf.modules.democard.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ro.kf.modules.democard.DemoCardModule;
import ro.kf.modules.democard.ModuleIdentity;
import ro.kf.modules.democard.hooks.ModelDeletedHandler;
import ro.kf.modules.democard.hooks.TenantDeletedHandler;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.testing.FakePlatform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The module as it runs on the platform, without the platform (the pattern of kf-qc): the whole application on a random
 * port, its own database ({@link ModuleDb}, Testcontainers) and the SDK's {@link FakePlatform} in place of the platform,
 * with the module's manifest, so the fake checks the declared capabilities as the platform does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kf.module-manifest=../kf-module-conf.json", "module.version=0.0.0-test"})
@Import(ModuleIT.OnFakePlatform.class)
public abstract class ModuleIT {

    public static final Path MANIFEST = Path.of("..", "kf-module-conf.json");
    public static final String MANIFEST_JSON = read(MANIFEST);
    public static final FakePlatform FAKE = startFake();

    @Value("${local.server.port}")
    protected int port;

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static FakePlatform startFake() {
        try {
            return FakePlatform.start(ModuleIdentity.SOURCE_SLUG).manifest(MANIFEST_JSON);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ModuleDb.register(registry);
    }

    @BeforeEach
    void callbacksToThisModule() {
        FAKE.callbackBase("http://127.0.0.1:" + port);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class OnFakePlatform {

        @Bean
        @Primary
        KfModule fakePlatformKfModule(ModelDeletedHandler onModelDeleted, TenantDeletedHandler onTenantDeleted) {
            return DemoCardModule.configure(FAKE.module(), ModuleIdentity.source(),
                    new DemoCardModule.Handlers(onModelDeleted, onTenantDeleted));
        }
    }

    protected ModuleCalls calls() {
        return new ModuleCalls(FAKE, port);
    }

    /** A fresh tenant for one test: the tests share one database and never see each other's tenants. */
    protected static String tenant() {
        return UUID.randomUUID().toString();
    }

    /** The model {@code id} of {@code model.v1}, visible only in {@code tenant}; owner {@code "Owner <id>"}. */
    protected static void model(String tenant, long id, String name) {
        model(tenant, id, name, "Owner " + id);
    }

    /**
     * The model {@code id} of {@code model.v1} (protocol 1.3.1: with {@code modelOwner}), visible only in
     * {@code tenant}, with two versions {@code id * 10} and {@code id * 10 + 1}; the current one is {@code id * 10 + 1}.
     */
    protected static void model(String tenant, long id, String name, String owner) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", id);
        object.put("name", name);
        object.put("styleNumber", "ST-" + id);
        object.put("modelOwner", owner);
        object.put("createdAt", "2026-10-01T08:00:00Z");
        object.put("currentVersionId", id * 10 + 1);
        object.put("versions", List.of(Map.of("id", id * 10, "name", "V1", "status", "DRAFT"),
                Map.of("id", id * 10 + 1, "name", "V2", "status", "SAMPLING")));
        FAKE.reads("model.v1").put(tenant, String.valueOf(id), object);
    }

    /**
     * The garment image of version {@code versionId} ({@code model-version.garment-image.v1}, protocol 1.3.1) in
     * {@code tenant}: a presigned URL valid 10 minutes, or {@code url: null} (the version has no image).
     */
    protected static void image(String tenant, long versionId, String url) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", versionId);
        object.put("url", url);
        object.put("expiresAt", url == null ? null : "2026-11-02T10:10:00Z");
        FAKE.reads("model-version.garment-image.v1").put(tenant, String.valueOf(versionId), object);
    }
}
