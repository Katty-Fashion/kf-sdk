package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capability {@code nav} of {@code kf-module-conf.json} (protocol 1.1, additive): the
 * module's menu entries, on the staff sidebar, the customer portal menu or the platform menu. The SDK reads them on the
 * typed manifest and refuses locally what it can see without the platform's catalogs: the shape, the keys, the url,
 * the label key, a right that is not one of the module's own routes, a right on the customer surface, an undeclared
 * flag. The groups of a surface and the platform's codes stay the platform's check at upload.
 */
class ManifestNavTest {

    private static final String ROUTES = """
            "routes":[{"method":"GET","path":"/m/nav-mod/events","right":"api.v1.m.nav-mod.events.get"}]""";

    private static String manifest(String nav) {
        return """
                {"schemaVersion":2,"slug":"nav-mod","version":"1.0.0","platform":">=4.1","name":"module.name",
                 "featureFlags":[{"key":"beta-menu","label":"flag.beta","default":false}],
                 "i18n":{"en":{"module.name":"Nav module","nav.events":"Events","flag.beta":"Beta menu"}},
                 "capabilities":{%s%s}}
                """.formatted(ROUTES, nav == null ? "" : ",\"nav\":" + nav);
    }

    private static String entry(String surface, String extra) {
        return "{\"surface\":\"" + surface + "\",\"group\":\"admin-utils\",\"key\":\"events\",\"label\":\"nav.events\","
                + "\"url\":\"/modules\"" + extra + "}";
    }

    private static List<String> errors(String nav) {
        return ManifestCheck.validate(manifest(nav)).errors();
    }

    private static void refused(String nav, String expected) {
        List<String> errors = errors(nav);
        assertFalse(errors.isEmpty(), nav);
        assertTrue(errors.stream().anyMatch(e -> e.contains(expected)), expected + " not in " + errors);
    }

    @Test
    @DisplayName("a valid staff entry with the right of one of the module's routes is read and accepted")
    void aValidEntryIsReadAndAccepted() {
        String json = manifest("[" + entry("staff", ",\"icon\":\"Boxes\",\"right\":\"api.v1.m.nav-mod.events.get\","
                + "\"order\":20,\"flag\":\"beta-menu\"") + "]");
        ManifestCheck.Result result = ManifestCheck.validate(json);
        assertTrue(result.ok(), result.errors().toString());
        Manifest.NavCapability nav = Manifest.parse(json).capabilities().nav().get(0);
        assertEquals("staff", nav.surface());
        assertEquals("admin-utils", nav.group());
        assertEquals("events", nav.key());
        assertEquals("nav.events", nav.label());
        assertEquals("/modules", nav.url());
        assertEquals("Boxes", nav.icon());
        assertEquals("api.v1.m.nav-mod.events.get", nav.right());
        assertEquals(20, nav.order());
        assertEquals("beta-menu", nav.flag());
    }

    @Test
    @DisplayName("a platform code as right is accepted locally (whether it is declared is the platform's check)")
    void aPlatformCodeIsAcceptedLocally() {
        assertTrue(errors("[" + entry("staff", ",\"right\":\"api.v1.customers.get\"") + "]").isEmpty());
    }

    @Test
    @DisplayName("nav absent reads as [], and the optional keys read as their defaults")
    void absentReadsEmptyAndDefaults() {
        assertTrue(ManifestCheck.validate(manifest(null)).ok());
        assertTrue(Manifest.parse(manifest(null)).capabilities().nav().isEmpty());
        Manifest.NavCapability nav = Manifest.parse(manifest("[" + entry("platform", "") + "]")).capabilities().nav().get(0);
        assertNull(nav.icon());
        assertNull(nav.right());
        assertNull(nav.flag());
        assertEquals(100, nav.order());
        assertTrue(Manifest.CAPABILITY_KEYS.contains("nav"));
    }

    @Test
    @DisplayName("an unknown surface is refused, naming the three surfaces")
    void unknownSurfaceIsRefused() {
        refused("[" + entry("sidebar", "") + "]",
                "capabilities.nav[0].surface must be one of staff, customer, platform (got \"sidebar\")");
    }

    @Test
    @DisplayName("the same key twice on one surface is refused; the same key on two surfaces is legal")
    void duplicateKeyIsRefused() {
        refused("[" + entry("staff", "") + "," + entry("staff", "") + "]",
                "capabilities.nav[1].key \"events\" is declared twice on the surface staff");
        assertTrue(errors("[" + entry("staff", "") + "," + entry("platform", "") + "]").isEmpty());
    }

    @Test
    @DisplayName("a url that is not an in-app path is refused: //, .., a scheme, a query, %")
    void invalidUrlIsRefused() {
        for (String url : List.of("//evil.example/x", "/a/../setup", "https://evil.example", "/a?x=1", "/a%2e", "modules")) {
            refused("[{\"surface\":\"staff\",\"group\":\"admin-utils\",\"key\":\"events\",\"label\":\"nav.events\","
                    + "\"url\":\"" + url + "\"}]", "capabilities.nav[0].url must be an in-app path");
        }
    }

    @Test
    @DisplayName("a label that is not a key of i18n.en is refused")
    void labelNotInI18nIsRefused() {
        refused("[{\"surface\":\"staff\",\"group\":\"admin-utils\",\"key\":\"events\",\"label\":\"nav.missing\","
                + "\"url\":\"/modules\"}]", "capabilities.nav[0].label \"nav.missing\" is not defined in i18n.en");
    }

    @Test
    @DisplayName("a right on the customer surface is refused: the customer menu is filtered on identity")
    void rightOnCustomerIsRefused() {
        refused("[" + entry("customer", ",\"right\":\"api.v1.m.nav-mod.events.get\"") + "]",
                "capabilities.nav[0].right is not allowed on the customer surface");
    }

    @Test
    @DisplayName("a module right that is not one of the module's routes is refused")
    void undeclaredModuleRightIsRefused() {
        refused("[" + entry("staff", ",\"right\":\"api.v1.m.nav-mod.secret.get\"") + "]",
                "capabilities.nav[0].right \"api.v1.m.nav-mod.secret.get\" is not the right of a route this module declares");
        refused("[" + entry("staff", ",\"right\":\"api.v1.m.other-mod.events.get\"") + "]",
                "capabilities.nav[0].right \"api.v1.m.other-mod.events.get\" is not the right of a route this module declares");
    }

    @Test
    @DisplayName("an undeclared flag, an order out of range, a bad group or key, an unknown key are refused")
    void otherShapeErrorsAreRefused() {
        refused("[" + entry("staff", ",\"flag\":\"nope\"") + "]",
                "capabilities.nav[0].flag \"nope\" is not a feature flag of this module");
        refused("[" + entry("staff", ",\"order\":10001") + "]",
                "capabilities.nav[0].order must be an integer from 0 to 10000");
        refused("[" + entry("staff", ",\"icon\":\"<svg>\"") + "]", "capabilities.nav[0].icon must be");
        refused("[{\"surface\":\"staff\",\"group\":\"Admin Utils\",\"key\":\"events\",\"label\":\"nav.events\","
                + "\"url\":\"/modules\"}]", "capabilities.nav[0].group must match");
        refused("[{\"surface\":\"staff\",\"group\":\"admin-utils\",\"key\":\"m.events\",\"label\":\"nav.events\","
                + "\"url\":\"/modules\"}]", "capabilities.nav[0].key must match");
        refused("[" + entry("staff", ",\"target\":\"_blank\"") + "]",
                "capabilities.nav[0]: \"target\" is not a key of a nav entry");
        refused("{}", "capabilities.nav must be an array");
    }
}
