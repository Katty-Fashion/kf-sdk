package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keys added to {@code kf-module-conf.json} in protocol 1.1 (additive, no new
 * {@code schemaVersion}): {@code settings} (closed scalars only: the rule "settings carry no data"),
 * {@code routes[].admin}, {@code secrets} (by reference), {@code provides}, {@code surfaces[].page}, and the grammar of
 * a port id (one optional sub-segment). The SDK reads them on the typed manifest and refuses locally what it
 * can see without the platform's catalogs (the published and provided ports, the surface types: the platform's check
 * at upload). A 1.0 manifest without the new keys stays accepted unchanged.
 */
class ManifestContract11Test {

    private static final String BASE_ROUTES = """
            {"method":"GET","path":"/m/c11/stations","right":"api.v1.m.c11.stations.get"},
            {"method":"GET","path":"/m/c11/admin/config","right":"api.v1.m.c11.admin.config.get","admin":"tenant"},
            {"method":"PUT","path":"/m/c11/admin/keys","right":"api.v1.m.c11.admin.keys.put","admin":"platform"},
            {"method":"PUT","path":"/m/c11/admin/tenant-keys","right":"api.v1.m.c11.admin.tenant-keys.put","admin":"tenant"}""";

    private static final String I18N = """
            "i18n":{"en":{"module.name":"Contract 1.1","setting.push":"Push on save","setting.timeout":"Timeout",
             "setting.mode":"Mode","setting.mode.help":"How the module pushes","tab.label":"AAS"}}""";

    private static String manifest(String routes, String extra) {
        return """
                {"schemaVersion":2,"slug":"c11","version":"1.0.0","platform":">=4.1","name":"module.name",%s,
                 "capabilities":{"routes":[%s]%s}}
                """.formatted(I18N, routes, extra == null ? "" : "," + extra);
    }

    private static String manifest(String extra) {
        return manifest(BASE_ROUTES, extra);
    }

    private static final String VALID_SETTINGS = """
            "settings":[
              {"key":"push-on-save","type":"BOOLEAN","default":false,"label":"setting.push"},
              {"key":"timeout-seconds","type":"NUMBER","default":30,"min":5,"max":600,"label":"setting.timeout"},
              {"key":"mode","type":"ENUM","default":"auto","values":["auto","manual"],"label":"setting.mode",
               "description":"setting.mode.help"}]""";

    private static final String VALID_SECRETS = """
            "secrets":[
              {"name":"r3-client-secret","scope":"platform","writeRight":"api.v1.m.c11.admin.keys.put"},
              {"name":"tenant-token","scope":"tenant","writeRight":"api.v1.m.c11.admin.tenant-keys.put"}]""";

    private static final String VALID_PROVIDES = """
            "provides":[{"port":"factory.stations.v1","path":"/m/c11/stations"}]""";

    private static final String VALID_SURFACES = """
            "surfaces":[{"slug":"module.page","kind":"react","label":"tab.label","page":"shells"}]""";

    private static final String VALID_READS = """
            "reads":[{"port":"model.v1"},{"port":"file.public-url.v1"}]""";

    private static List<String> errors(String json) {
        return ManifestCheck.validate(json).errors();
    }

    private static void refused(String json, String expected) {
        List<String> errors = errors(json);
        assertFalse(errors.isEmpty(), json);
        assertTrue(errors.stream().anyMatch(e -> e.contains(expected)), expected + " not in " + errors);
    }

    @Test
    @DisplayName("a manifest that uses every 1.1 key of correctly is accepted and read")
    void everyNewKeyUsedCorrectlyIsAcceptedAndRead() {
        String json = manifest(String.join(",", VALID_SETTINGS, VALID_SECRETS, VALID_PROVIDES, VALID_SURFACES, VALID_READS));
        ManifestCheck.Result result = ManifestCheck.validate(json);
        assertTrue(result.ok(), result.errors().toString());

        Manifest.Capabilities caps = Manifest.parse(json).capabilities();
        assertEquals(3, caps.settings().size());
        Manifest.SettingCapability timeout = caps.settings().get(1);
        assertEquals("timeout-seconds", timeout.key());
        assertEquals("NUMBER", timeout.type());
        assertEquals(30, ((Number) timeout.defaultValue()).intValue());
        assertEquals(5, timeout.min().intValue());
        assertEquals(600, timeout.max().intValue());
        Manifest.SettingCapability mode = caps.settings().get(2);
        assertEquals(List.of("auto", "manual"), mode.values());
        assertEquals("setting.mode.help", mode.description());
        assertEquals(Boolean.FALSE, caps.settings().get(0).defaultValue());

        assertEquals(new Manifest.SecretCapability("r3-client-secret", "platform", "api.v1.m.c11.admin.keys.put"),
                caps.secrets().get(0));
        assertEquals(new Manifest.ProvideCapability("factory.stations.v1", "/m/c11/stations"), caps.provides().get(0));
        assertEquals("shells", caps.surfaces().get(0).page());
        assertNull(caps.routes().get(0).admin());
        assertEquals("tenant", caps.routes().get(1).admin());
        assertEquals("platform", caps.routes().get(2).admin());
    }

    @Test
    @DisplayName("a 1.0 manifest (none of the new keys) is accepted unchanged; the new lists read as []")
    void aManifest10IsAcceptedUnchanged() {
        String json = manifest("""
                {"method":"POST","path":"/m/c11/notes","right":"api.v1.m.c11.notes.post","roles":["QC"],"hookable":true}""",
                "\"surfaces\":[{\"slug\":\"models.detail.tabs\",\"kind\":\"react\",\"label\":\"tab.label\"}]");
        assertTrue(ManifestCheck.validate(json).ok(), errors(json).toString());
        Manifest.Capabilities caps = Manifest.parse(json).capabilities();
        assertTrue(caps.settings().isEmpty());
        assertTrue(caps.secrets().isEmpty());
        assertTrue(caps.provides().isEmpty());
        assertNull(caps.surfaces().get(0).page());
        assertNull(caps.routes().get(0).admin());
    }

    // ---- settings ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a STRING setting is refused: settings carry no data")
    void aStringSettingIsRefused() {
        refused(manifest("""
                "settings":[{"key":"base-url","type":"STRING","default":"https://x","label":"setting.push"}]"""),
                "settings carry no data: BOOLEAN, NUMBER or ENUM");
    }

    @Test
    @DisplayName("an ENUM setting without values is refused")
    void anEnumWithoutValuesIsRefused() {
        refused(manifest("""
                "settings":[{"key":"mode","type":"ENUM","default":"auto","label":"setting.mode"}]"""),
                ".values must be a non-empty list");
    }

    @Test
    @DisplayName("an ENUM default outside its values is refused")
    void anEnumDefaultOutsideValuesIsRefused() {
        refused(manifest("""
                "settings":[{"key":"mode","type":"ENUM","default":"other","values":["auto"],"label":"setting.mode"}]"""),
                ".default must be one of the values");
    }

    @Test
    @DisplayName("a NUMBER default outside min..max is refused")
    void aNumberOutsideRangeIsRefused() {
        refused(manifest("""
                "settings":[{"key":"timeout","type":"NUMBER","default":1,"min":5,"max":10,"label":"setting.timeout"}]"""),
                ".default must be between min and max");
    }

    @Test
    @DisplayName("a setting key declared twice is refused")
    void aDuplicateSettingKeyIsRefused() {
        refused(manifest("""
                "settings":[{"key":"push","type":"BOOLEAN","default":true,"label":"setting.push"},
                            {"key":"push","type":"BOOLEAN","default":false,"label":"setting.push"}]"""),
                ".key \"push\" is declared twice");
    }

    @Test
    @DisplayName("a setting with a key outside the closed object (e.g. a value list) is refused")
    void aSettingIsAClosedObject() {
        refused(manifest("""
                "settings":[{"key":"push","type":"BOOLEAN","default":true,"label":"setting.push","items":[1]}]"""),
                "\"items\" is not a key of a setting");
    }

    @Test
    @DisplayName("more than 30 settings are refused")
    void moreThan30SettingsAreRefused() {
        StringBuilder settings = new StringBuilder("\"settings\":[");
        for (int i = 0; i < 31; i++) {
            if (i > 0) settings.append(',');
            settings.append("{\"key\":\"s").append(i).append("x\",\"type\":\"BOOLEAN\",\"default\":true,\"label\":\"setting.push\"}");
        }
        refused(manifest(settings.append(']').toString()), "at most 30 settings");
    }

    // ---- routes[].admin -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("admin platform with roles is refused")
    void adminPlatformWithRolesIsRefused() {
        refused(manifest("""
                {"method":"PUT","path":"/m/c11/admin/keys","right":"api.v1.m.c11.admin.keys.put","admin":"platform","roles":["QC"]}""",
                null), ".admin \"platform\" declares no roles");
    }

    @Test
    @DisplayName("admin on a hookable route is refused")
    void adminOnAHookableRouteIsRefused() {
        refused(manifest("""
                {"method":"POST","path":"/m/c11/admin/run","right":"api.v1.m.c11.admin.run.post","admin":"tenant","hookable":true}""",
                null), "admin and hookable exclude each other");
    }

    @Test
    @DisplayName("an unknown admin value is refused")
    void anUnknownAdminValueIsRefused() {
        refused(manifest("""
                {"method":"GET","path":"/m/c11/admin/x","right":"api.v1.m.c11.admin.x.get","admin":"super"}""", null),
                ".admin must be one of tenant, platform");
    }

    // ---- secrets ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a secret whose writeRight is not the right of a declared route is refused")
    void aSecretWithAnUndeclaredWriteRightIsRefused() {
        refused(manifest("""
                "secrets":[{"name":"token","scope":"tenant","writeRight":"api.v1.m.c11.nowhere.put"}]"""),
                ".writeRight \"api.v1.m.c11.nowhere.put\" is not the right of a route this module declares");
    }

    @Test
    @DisplayName("a platform secret whose writeRight is the right of a route without admin platform is refused")
    void aPlatformSecretOnATenantRouteIsRefused() {
        refused(manifest("""
                "secrets":[{"name":"token","scope":"platform","writeRight":"api.v1.m.c11.admin.tenant-keys.put"}]"""),
                "a platform secret is written only through a route with \"admin\": \"platform\"");
    }

    @Test
    @DisplayName("a secret that carries a value is refused (a closed object: secrets travel by reference)")
    void aSecretWithAValueIsRefused() {
        refused(manifest("""
                "secrets":[{"name":"token","scope":"tenant","writeRight":"api.v1.m.c11.admin.tenant-keys.put","value":"x"}]"""),
                "\"value\" is not a key of a secret");
    }

    @Test
    @DisplayName("a secret name declared twice and an unknown scope are refused")
    void aDuplicateSecretAndAnUnknownScopeAreRefused() {
        String json = manifest("""
                "secrets":[{"name":"token","scope":"tenant","writeRight":"api.v1.m.c11.admin.tenant-keys.put"},
                           {"name":"token","scope":"global","writeRight":"api.v1.m.c11.admin.tenant-keys.put"}]""");
        refused(json, ".name \"token\" is declared twice");
        refused(json, ".scope must be one of platform, tenant");
    }

    // ---- provides ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("provides on a path that is not a declared GET route is refused")
    void providesOnANonGetRouteIsRefused() {
        refused(manifest("""
                "provides":[{"port":"factory.stations.v1","path":"/m/c11/admin/keys"}]"""),
                ".path \"/m/c11/admin/keys\" is not a GET route this module declares");
    }

    @Test
    @DisplayName("provides with a malformed port id is refused")
    void providesWithAMalformedPortIsRefused() {
        refused(manifest("""
                "provides":[{"port":"Factory.Stations","path":"/m/c11/stations"}]"""),
                ".port \"Factory.Stations\" must match");
    }

    // ---- surfaces[].page and reads[].port -----------------------------------------------------------------------

    @Test
    @DisplayName("a page with upper case letters is refused")
    void aPageWithUpperCaseIsRefused() {
        refused(manifest("""
                "surfaces":[{"slug":"module.page","kind":"react","page":"Shells"}]"""),
                ".page \"Shells\" must match");
    }

    @Test
    @DisplayName("the port grammar takes one optional sub-segment, not two")
    void thePortGrammarTakesOneSubSegment() {
        assertTrue(errors(manifest("\"reads\":[{\"port\":\"file.public-url.v1\"}]")).isEmpty());
        refused(manifest("\"reads\":[{\"port\":\"a.b.c.v1\"}]"), ".port \"a.b.c.v1\" must match");
        refused(manifest("\"reads\":[{\"port\":\"model\"}]"), ".port \"model\" must match");
    }

    // ---- secrets[].external -----------------------------------------------------------

    @Test
    @DisplayName("a platform secret with external prefixes is accepted")
    void aPlatformSecretWithExternalPrefixesIsAccepted() {
        String json = manifest("""
                "secrets":[{"name":"r3-client-secret","scope":"platform","writeRight":"api.v1.m.c11.admin.keys.put",
                            "external":["platform/aas/","kv/r3-clients/"]}]""");
        assertTrue(ManifestCheck.validate(json).ok(), errors(json).toString());
    }

    @Test
    @DisplayName("external on a tenant secret, empty, outside the grammar, into kf-modules or twice is refused")
    void externalOutsideItsRulesIsRefused() {
        refused(manifest("""
                "secrets":[{"name":"token","scope":"tenant","writeRight":"api.v1.m.c11.admin.tenant-keys.put",
                            "external":["platform/aas/"]}]"""),
                ".external is allowed only on a platform secret");
        refused(manifest("""
                "secrets":[{"name":"token","scope":"platform","writeRight":"api.v1.m.c11.admin.keys.put","external":[]}]"""),
                ".external must be a non-empty array of at most 5 prefixes");
        String json = manifest("""
                "secrets":[{"name":"token","scope":"platform","writeRight":"api.v1.m.c11.admin.keys.put",
                            "external":["platform/","platform/aas","platform/kf-modules/x/","platform/aas/","platform/aas/"]}]""");
        refused(json, ".external[0] must match ^[a-z0-9][a-z0-9_-]{0,63}(/[a-z0-9][a-z0-9_-]{0,63})+/$ (got \"platform/\")");
        refused(json, ".external[1] must match");
        refused(json, ".external[2] \"platform/kf-modules/x/\" must not reach the modules' secrets (kf-modules)");
        refused(json, ".external[4] \"platform/aas/\" is declared twice");
    }

    // ---- routes[].timeoutSeconds (SDK 0.7.3) ------------------------------------------

    private static String timeoutRoute(String value) {
        return BASE_ROUTES + """
                ,{"method":"GET","path":"/m/c11/shells","right":"api.v1.m.c11.shells.get","admin":"tenant","timeoutSeconds":%s}"""
                .formatted(value);
    }

    @Test
    @DisplayName("timeoutSeconds between 1 and the cap is read on the route; absent or null reads null (the platform's default)")
    void aRouteTimeoutIsRead() {
        String json = manifest(timeoutRoute("60"), null);
        assertTrue(ManifestCheck.validate(json).ok(), errors(json).toString());
        Manifest.Capabilities caps = Manifest.parse(json).capabilities();
        assertEquals(Integer.valueOf(60), caps.routes().get(4).timeoutSeconds());
        assertNull(caps.routes().get(0).timeoutSeconds());
        assertEquals(60, Manifest.MAX_ROUTE_TIMEOUT_SECONDS);
        String nullJson = manifest(timeoutRoute("null"), null);
        assertTrue(ManifestCheck.validate(nullJson).ok(), errors(nullJson).toString());
        assertNull(Manifest.parse(nullJson).capabilities().routes().get(4).timeoutSeconds());
        assertTrue(ManifestCheck.validate(manifest(timeoutRoute("1"), null)).ok());
    }

    @Test
    @DisplayName("timeoutSeconds over the cap, zero, negative, fractional or not a number is refused, naming the cap")
    void aRouteTimeoutOutsideItsRulesIsRefused() {
        refused(manifest(timeoutRoute("61"), null),
                "(GET /m/c11/shells).timeoutSeconds must be a whole number of seconds between 1 and 60 (got 61)");
        for (String value : List.of("0", "-1", "1.5", "\"15\"", "true")) {
            refused(manifest(timeoutRoute(value), null),
                    ".timeoutSeconds must be a whole number of seconds between 1 and 60");
        }
    }
}
