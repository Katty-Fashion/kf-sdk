package ro.kf.modules.democard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.Manifest;
import ro.kf.sdk.ManifestCheck;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** kf-module-conf.json against the SDK's local rules, and what the module registers against the manifest. */
class ManifestTest {

    static final Path MANIFEST = Path.of("..", "kf-module-conf.json");

    static String manifest() throws Exception {
        return Files.readString(MANIFEST, StandardCharsets.UTF_8);
    }

    static Map<String, Manifest.RouteCapability> routes() throws Exception {
        Map<String, Manifest.RouteCapability> routes = new LinkedHashMap<>();
        Manifest.parse(manifest()).capabilities().routes().forEach(r -> routes.put(r.method() + " " + r.path(), r));
        return routes;
    }

    static KfModule configured(String slug) {
        return DemoCardModule.configure(KfModule.of("http://platform.invalid", slug, "kfm_test", "secret",
                "http://api." + slug + ".kfmod:8080"), ModuleIdentity.of(slug),
                new DemoCardModule.Handlers(event -> { }, deleted -> { }));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> surfaces(KfModule kf) {
        return (List<Map<String, Object>>) kf.registrationBody().get("surfaces");
    }

    @Test
    @DisplayName("the manifest is a valid schemaVersion 2 manifest, en and ro, no warning; identity and activation tenant")
    void manifestIsValid() throws Exception {
        ManifestCheck.Result result = ManifestCheck.validate(manifest(), Set.of("en", "ro"));
        assertTrue(result.ok(), () -> "errors: " + result.errors());
        assertTrue(result.warnings().isEmpty(), () -> "warnings: " + result.warnings());
        Manifest m = Manifest.parse(manifest());
        assertEquals(ModuleIdentity.SOURCE_SLUG, m.slug());
        assertEquals(2, m.schemaVersion());
        assertThat(m.version()).matches("^\\d+\\.\\d+\\.\\d+$");
        assertEquals(">=6.1", m.platform());
        assertEquals(Manifest.Activation.TENANT, m.activation());
        assertThat(m.i18n().get("ro").keySet()).isEqualTo(m.i18n().get("en").keySet());
    }

    @Test
    @DisplayName("everything the module registers at boot is declared in the manifest (hooks, surfaces)")
    void registrationMatchesTheManifest() throws Exception {
        KfModule kf = configured(ModuleIdentity.SOURCE_SLUG);
        ManifestCheck.Result result = ManifestCheck.check(manifest(), kf.registrationBody(), Set.of("en", "ro"));
        assertTrue(result.ok(), () -> "errors: " + result.errors());
        assertThat(String.valueOf(kf.registrationBody().get("hooks"))).contains("model.after.delete", "tenant.after.delete");
    }

    @Test
    @DisplayName("the five card routes are audience member: no roles, no admin, not hookable, not on the portal")
    void memberRoutes() throws Exception {
        Map<String, Manifest.RouteCapability> routes = routes();
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("GET /m/kf-demo-model-card/cards", "api.v1.m.kf-demo-model-card.cards.get");
        expected.put("GET /m/kf-demo-model-card/models", "api.v1.m.kf-demo-model-card.models.get");
        expected.put("GET /m/kf-demo-model-card/models/{modelId}", "api.v1.m.kf-demo-model-card.models.get");
        expected.put("PUT /m/kf-demo-model-card/pins/{modelId}", "api.v1.m.kf-demo-model-card.pins.put");
        expected.put("DELETE /m/kf-demo-model-card/pins/{modelId}", "api.v1.m.kf-demo-model-card.pins.delete");
        expected.forEach((key, right) -> {
            Manifest.RouteCapability r = routes.get(key);
            assertThat(r).as(key).isNotNull();
            assertThat(r.right()).as(key).isEqualTo(right);
            assertThat(r.audience()).as(key).isEqualTo("member");
            assertThat(r.roles()).as(key).isNullOrEmpty();
            assertThat(r.admin()).as(key).isNull();
            assertThat(r.hookable()).as(key).isFalse();
            assertThat(r.portal()).as(key).isFalse();
        });
    }

    @Test
    @DisplayName("the reset is admin: tenant; the export and the import are admin: platform with 60 s; tenantData declares the format")
    void adminRoutes() throws Exception {
        Map<String, Manifest.RouteCapability> routes = routes();
        Manifest.RouteCapability reset = routes.get("DELETE /m/kf-demo-model-card/admin/pins");
        assertEquals("tenant", reset.admin());
        assertThat(reset.roles()).isNullOrEmpty();
        for (String key : List.of("GET /m/kf-demo-model-card/admin/export", "POST /m/kf-demo-model-card/admin/import")) {
            Manifest.RouteCapability r = routes.get(key);
            assertThat(r).as(key).isNotNull();
            assertEquals("platform", r.admin(), key);
            assertEquals(60, r.timeoutSeconds(), key);
        }
        JsonNode tenantData = JsonMapper.builder().build().readTree(manifest()).get("tenantData");
        assertEquals("kf-demo-model-card/tenant-data/v1", tenantData.get("format").asString());
        assertTrue(tenantData.get("export").asBoolean());
        assertThat(routes.keySet()).allSatisfy(k -> assertThat(k).contains("/m/kf-demo-model-card/"));
    }

    @Test
    @DisplayName("reads model.v1 and model-version.garment-image.v1 (protocol 1.3.1); hooks model.after.delete and tenant.after.delete; the notification pinned-model-deleted in_app with modelName")
    void readsHooksNotifications() throws Exception {
        Manifest.Capabilities caps = Manifest.parse(manifest()).capabilities();
        assertThat(caps.reads()).extracting(Manifest.ReadCapability::port)
                .containsExactly("model.v1", "model-version.garment-image.v1");
        assertThat(caps.hooks()).extracting(Manifest.HookCapability::point)
                .containsExactly("model.after.delete", "tenant.after.delete");
        assertThat(caps.notifications()).singleElement().satisfies(n -> {
            assertEquals("pinned-model-deleted", n.key());
            assertThat(n.variables()).containsExactly("modelName");
            assertThat(n.channels()).containsExactly("in_app");
        });
        assertThat(caps.fields()).isEmpty();
        assertThat(caps.settings()).isEmpty();
    }

    @Test
    @DisplayName("surfaces: the card on the Overview surface (key card) and the page cards on module.pages, from the module's bundle and stylesheet; the staff menu entry")
    void surfacesAndNav() throws Exception {
        Manifest m = Manifest.parse(manifest());
        assertThat(m.capabilities().surfaces()).extracting(Manifest.SurfaceCapability::slug)
                .containsExactly(DemoCardModule.OVERVIEW_CARDS, "module.pages");
        assertThat(surfaces(configured(ModuleIdentity.SOURCE_SLUG))).satisfiesExactly(
                card -> {
                    assertEquals(DemoCardModule.OVERVIEW_CARDS, card.get("slug"));
                    assertEquals("ModelCard", card.get("export"));
                    assertEquals("card", card.get("key"));
                    assertEquals("/m/kf-demo-model-card/ui/surfaces.mjs", card.get("entry"));
                    assertEquals("/m/kf-demo-model-card/ui/surfaces.css", card.get("css"));
                },
                page -> {
                    assertEquals("module.pages", page.get("slug"));
                    assertEquals("ModelCardsPage", page.get("export"));
                    assertEquals("cards", page.get("key"));
                });
        assertThat(m.capabilities().nav()).singleElement().satisfies(n -> {
            assertEquals("staff", n.surface());
            // a group of the staff menu: the platform checks it at upload (navigation/base-catalog.json, "product")
            assertEquals("product", n.group());
            assertEquals("/m/kf-demo-model-card/cards", n.url());
            assertThat(n.right()).isNull();
        });
    }

    @Test
    @DisplayName("the dev build kf-demo-model-card-dev registers its own bundle: the slug is read at run time, never literal")
    void devBuildIdentity() {
        assertThat(surfaces(configured("kf-demo-model-card-dev"))).allSatisfy(s -> {
            assertThat((String) s.get("entry")).startsWith("/m/kf-demo-model-card-dev/ui/");
            assertThat((String) s.get("css")).startsWith("/m/kf-demo-model-card-dev/ui/");
        });
        assertEquals("api.v1.m.kf-demo-model-card-dev.admin.export.get",
                ModuleIdentity.of("kf-demo-model-card-dev").right("api.v1.m.kf-demo-model-card.admin.export.get"));
    }
}
