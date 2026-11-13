package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keys protocol 1.2 adds to {@code kf-module-conf.json} (additive, no new
 * {@code schemaVersion}): {@code routes[].audience} ({@code "member"}: any member of the active tenant calls the route),
 * {@code routes[].portal} (the route is served on the customer portal too, only on {@code audience: member}), the
 * top-level {@code activation} ({@code "tenant"} by default, {@code "platform"}: live in every tenant) and
 * {@code capabilities.storage} (at most one element {@code {contentTypes, maxBytes}}), plus the hook point
 * {@code tenant.after.delete}. The SDK reads them on the typed manifest and refuses locally, naming the rule, with the
 * texts the platform uses at upload. A 1.1 manifest without the new keys stays accepted unchanged.
 */
class ManifestContract12Test {

    private static final String I18N = """
            "i18n":{"en":{"module.name":"Feedback","nav.feedback":"Feedback","widget.label":"Feedback",
             "page.label":"Feedback triage"},"ro":{"module.name":"Feedback"}}""";

    /** The routes of the module contract.md §4 (kf-feedback 0.1.0). */
    private static final String FEEDBACK_ROUTES = """
            {"method":"POST","path":"/m/kf-feedback/tickets","right":"api.v1.m.kf-feedback.tickets.post","audience":"member","portal":true},
            {"method":"POST","path":"/m/kf-feedback/screenshots","right":"api.v1.m.kf-feedback.screenshots.post","audience":"member","portal":true},
            {"method":"GET","path":"/m/kf-feedback/admin/tickets","right":"api.v1.m.kf-feedback.admin.tickets.get","admin":"platform"},
            {"method":"PATCH","path":"/m/kf-feedback/admin/tickets/{id}/resolve","right":"api.v1.m.kf-feedback.admin.tickets.patch","admin":"platform"},
            {"method":"PATCH","path":"/m/kf-feedback/admin/tickets/{id}/reopen","right":"api.v1.m.kf-feedback.admin.tickets.patch","admin":"platform"},
            {"method":"GET","path":"/m/kf-feedback/admin/tickets/{id}/screenshot-url","right":"api.v1.m.kf-feedback.admin.screenshots.get","admin":"platform"},
            {"method":"GET","path":"/m/kf-feedback/admin/github","right":"api.v1.m.kf-feedback.admin.github.get","admin":"platform"},
            {"method":"PUT","path":"/m/kf-feedback/admin/github","right":"api.v1.m.kf-feedback.admin.github.put","admin":"platform"},
            {"method":"POST","path":"/m/kf-feedback/admin/tickets/{id}/github","right":"api.v1.m.kf-feedback.admin.tickets.post","admin":"platform","timeoutSeconds":30},
            {"method":"POST","path":"/m/kf-feedback/admin/import","right":"api.v1.m.kf-feedback.admin.import.post","admin":"platform","timeoutSeconds":60}""";

    private static final String FEEDBACK_CAPABILITIES = """
            "hooks":[{"point":"tenant.after.delete"}],
            "storage":[{"contentTypes":["image/jpeg","image/png"],"maxBytes":5242880}],
            "reads":[{"port":"tenant.v1"}],
            "audit":[{"access":"write"}],
            "secrets":[{"name":"github-token","scope":"platform","writeRight":"api.v1.m.kf-feedback.admin.github.put"}],
            "surfaces":[{"slug":"app.overlay","kind":"react","label":"widget.label"},
                        {"slug":"setup.sections","kind":"react","label":"page.label","page":"feedback"}],
            "nav":[{"surface":"platform","group":"platform-main","key":"feedback","label":"nav.feedback",
                    "url":"/setup/m/kf-feedback/feedback","icon":"MessageSquare","order":15}]""";

    /** The complete manifest.md §4: every 1.2 key, used correctly. */
    private static final String FEEDBACK = """
            {"schemaVersion":2,"slug":"kf-feedback","name":"module.name","version":"0.1.0","rollbackFloor":"0.1.0",
             "platform":">=4.1","activation":"platform","author":"Katty Fashion",%s,
             "capabilities":{"routes":[%s],%s}}
            """.formatted(I18N, FEEDBACK_ROUTES, FEEDBACK_CAPABILITIES);

    private static String manifest(String root, String routes, String extra) {
        return """
                {"schemaVersion":2,"slug":"c12","version":"1.0.0","platform":">=4.1","name":"module.name"%s,%s,
                 "capabilities":{"routes":[%s]%s}}
                """.formatted(root == null ? "" : "," + root, I18N, routes, extra == null ? "" : "," + extra);
    }

    private static String route(String extra) {
        return "{\"method\":\"POST\",\"path\":\"/m/c12/tickets\",\"right\":\"api.v1.m.c12.tickets.post\""
                + (extra == null ? "" : "," + extra) + "}";
    }

    private static String storage(String elements) {
        return "\"storage\":[" + elements + "]";
    }

    private static List<String> errors(String json) {
        return ManifestCheck.validate(json).errors();
    }

    private static void accepted(String json) {
        ManifestCheck.Result result = ManifestCheck.validate(json);
        assertTrue(result.ok(), result.errors().toString());
    }

    private static void refused(String json, String expected) {
        List<String> errors = errors(json);
        assertFalse(errors.isEmpty(), json);
        assertTrue(errors.stream().anyMatch(e -> e.contains(expected)), expected + " not in " + errors);
    }

    // ---- the whole contract -------------------------------------------------------------------------------------

    @Test
    @DisplayName("the kf-feedback manifest (every 1.2 key) is accepted and read typed")
    void theFeedbackManifestIsAcceptedAndRead() {
        accepted(FEEDBACK);
        Manifest manifest = Manifest.parse(FEEDBACK);
        assertEquals(Manifest.Activation.PLATFORM, manifest.activation());
        assertEquals("platform", manifest.activation().wire());

        Manifest.Capabilities caps = manifest.capabilities();
        Manifest.RouteCapability tickets = caps.routes().get(0);
        assertEquals(Manifest.ROUTE_AUDIENCE_MEMBER, tickets.audience());
        assertTrue(tickets.portal());
        assertTrue(tickets.roles().isEmpty());
        Manifest.RouteCapability triage = caps.routes().get(2);
        assertNull(triage.audience());
        assertFalse(triage.portal());
        assertEquals("platform", triage.admin());

        assertEquals(1, caps.storage().size());
        assertEquals(List.of("image/jpeg", "image/png"), caps.storage().get(0).contentTypes());
        assertEquals(5_242_880, caps.storage().get(0).maxBytes());

        assertEquals(HookEvents.TENANT_AFTER_DELETE, caps.hooks().get(0).point());
        assertEquals("tenant.after.delete", HookEvents.TENANT_AFTER_DELETE);
        assertTrue(Manifest.CAPABILITY_KEYS.contains("storage"));
    }

    @Test
    @DisplayName("without the 1.2 keys: activation reads tenant, audience null, portal false, storage []")
    void theDefaultsOfThe12Keys() {
        String json = manifest(null, route(null), null);
        accepted(json);
        Manifest manifest = Manifest.parse(json);
        assertEquals(Manifest.Activation.TENANT, manifest.activation());
        assertEquals("tenant", manifest.activation().wire());
        assertNull(manifest.capabilities().routes().get(0).audience());
        assertFalse(manifest.capabilities().routes().get(0).portal());
        assertTrue(manifest.capabilities().storage().isEmpty());
    }

    @Test
    @DisplayName("the 1.1 example of sdk-protocol.md §3 (its texts defined) is accepted unchanged under SDK 0.8.0")
    void theProtocol11ExampleIsAcceptedUnchanged() {
        String json = """
                {"schemaVersion":2,"slug":"api-test","name":"module.name","description":"module.description",
                 "version":"0.5.0","rollbackFloor":"0.4.0","platform":">=4.1","author":"Katty Fashion",
                 "featureFlags":[{"key":"author-tab","label":"flag.author-tab.label","description":"flag.author-tab.desc","default":false}],
                 "requires":[{"slug":"other","versionRange":">=1.0.0"}],
                 "i18n":{"en":{"module.name":"API Test","module.description":"Notes and an author tab on models",
                   "field.priority":"Priority","flag.author-tab.label":"Author tab","flag.author-tab.desc":"Shows the author",
                   "tab.label":"Author","page.label":"Shells","n.subject":"Author changed","n.body":"{modelName}",
                   "nav.notes":"Notes","setting.push":"Push on save","setting.timeout":"Timeout","setting.mode":"Mode"},
                  "ro":{"module.name":"Test API"}},
                 "capabilities":{
                  "fields":[{"entity":"order","name":"priority_code","type":"STRING","table":"orders_extra","label":"field.priority","editable":true}],
                  "hooks":[{"point":"model.before.update","onFailure":"block"}],
                  "routes":[{"method":"POST","path":"/m/api-test/notes","right":"api.v1.m.api-test.notes.post","roles":["QC"],"hookable":true},
                            {"method":"GET","path":"/m/api-test/stations","right":"api.v1.m.api-test.stations.get"},
                            {"method":"PUT","path":"/m/api-test/admin/r3-keys","right":"api.v1.m.api-test.admin.r3-keys.put","admin":"platform"},
                            {"method":"GET","path":"/m/api-test/admin/shells","right":"api.v1.m.api-test.admin.shells.get","admin":"tenant"}],
                  "surfaces":[{"slug":"models.detail.tabs","kind":"react","label":"tab.label"},
                              {"slug":"module.pages","kind":"react","label":"page.label","page":"shells"}],
                  "notifications":[{"key":"author-changed","variables":["modelName"],"channels":["in_app"],"subject":"n.subject","body":"n.body"}],
                  "reads":[{"port":"model.v1"},{"port":"file.public-url.v1"}],
                  "audit":[{"access":"write"},{"access":"read-own"}],
                  "nav":[{"surface":"staff","group":"admin-utils","key":"notes","label":"nav.notes","url":"/modules","icon":"Boxes","right":"api.v1.m.api-test.notes.post","order":20,"flag":"author-tab"}],
                  "settings":[{"key":"push-on-save","type":"BOOLEAN","default":false,"label":"setting.push"},
                              {"key":"timeout-seconds","type":"NUMBER","default":30,"min":5,"max":600,"label":"setting.timeout"},
                              {"key":"mode","type":"ENUM","default":"auto","values":["auto","manual"],"label":"setting.mode"}],
                  "secrets":[{"name":"r3-client-secret","scope":"platform","writeRight":"api.v1.m.api-test.admin.r3-keys.put"}],
                  "provides":[{"port":"factory.stations.v1","path":"/m/api-test/stations"}]}}
                """;
        accepted(json);
        Manifest manifest = Manifest.parse(json);
        assertEquals(Manifest.Activation.TENANT, manifest.activation());
        assertTrue(manifest.capabilities().storage().isEmpty());
        assertTrue(manifest.capabilities().routes().stream().allMatch(r -> r.audience() == null && !r.portal()));
    }

    // ---- routes[].audience ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("audience member without roles, admin or hookable is accepted (an empty roles list too)")
    void audienceMemberIsAccepted() {
        accepted(manifest(null, route("\"audience\":\"member\",\"roles\":[],\"hookable\":false"), null));
        accepted(manifest(null, route("\"audience\":null"), null));
    }

    @Test
    @DisplayName("an audience other than member is refused, naming the value")
    void anUnknownAudienceIsRefused() {
        refused(manifest(null, route("\"audience\":\"everyone\""), null),
                ".audience must be \"member\" (got \"everyone\")");
        refused(manifest(null, route("\"audience\":true"), null), ".audience must be \"member\" (got true)");
    }

    @Test
    @DisplayName("audience member with roles, admin or hookable is refused")
    void audienceMemberExcludesRolesAdminAndHookable() {
        String rule = ": audience member excludes roles, admin and hookable (any member of the tenant calls it)";
        refused(manifest(null, route("\"audience\":\"member\",\"roles\":[\"QC\"]"), null), rule);
        refused(manifest(null, route("\"audience\":\"member\",\"admin\":\"tenant\""), null), rule);
        refused(manifest(null, route("\"audience\":\"member\",\"admin\":\"platform\""), null), rule);
        refused(manifest(null, route("\"audience\":\"member\",\"hookable\":true"), null), rule);
    }

    // ---- routes[].portal ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("portal false is accepted on any route; portal true only on audience member")
    void portalOnlyOnAudienceMember() {
        accepted(manifest(null, route("\"portal\":false"), null));
        accepted(manifest(null, route("\"audience\":\"member\",\"portal\":true"), null));
        refused(manifest(null, route("\"portal\":true"), null),
                ".portal must be a boolean, and only on audience member (got true");
    }

    @Test
    @DisplayName("a portal that is not a boolean is refused (null too)")
    void aPortalThatIsNotABooleanIsRefused() {
        refused(manifest(null, route("\"audience\":\"member\",\"portal\":\"yes\""), null),
                ".portal must be a boolean, and only on audience member (got \"yes\")");
        refused(manifest(null, route("\"audience\":\"member\",\"portal\":null"), null),
                ".portal must be a boolean, and only on audience member (got null)");
    }

    // ---- activation -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("activation tenant, platform and null are accepted; null reads tenant")
    void activationValuesAreAccepted() {
        accepted(manifest("\"activation\":\"tenant\"", route(null), null));
        accepted(manifest("\"activation\":\"platform\"", route(null), null));
        String nullActivation = manifest("\"activation\":null", route(null), null);
        accepted(nullActivation);
        assertEquals(Manifest.Activation.TENANT, Manifest.parse(nullActivation).activation());
    }

    @Test
    @DisplayName("another activation is refused, naming the value")
    void anUnknownActivationIsRefused() {
        refused(manifest("\"activation\":\"global\"", route(null), null),
                "kf-module-conf.json: activation must be \"tenant\" or \"platform\" (got \"global\")");
        refused(manifest("\"activation\":true", route(null), null),
                "activation must be \"tenant\" or \"platform\" (got true)");
    }

    // ---- capabilities.storage -------------------------------------------------------------------------------------

    @Test
    @DisplayName("one storage element with 1 to 10 types and 1 to 10485760 bytes is accepted")
    void storageAtItsLimitsIsAccepted() {
        accepted(manifest(null, route(null), storage("{\"contentTypes\":[\"application/pdf\"],\"maxBytes\":1}")));
        accepted(manifest(null, route(null), storage("""
                {"contentTypes":["image/jpeg","image/png","image/webp","image/gif","image/avif","image/svg+xml",
                 "application/pdf","text/csv","application/vnd.ms-excel","application/json"],"maxBytes":10485760}""")));
        accepted(manifest(null, route(null), "\"storage\":[]"));
    }

    @Test
    @DisplayName("more than one storage element is refused")
    void twoStorageElementsAreRefused() {
        String one = "{\"contentTypes\":[\"image/png\"],\"maxBytes\":100}";
        refused(manifest(null, route(null), storage(one + "," + one)),
                "kf-module-conf.json: capabilities.storage holds at most one element");
    }

    @Test
    @DisplayName("contentTypes empty, with a wildcard, duplicated, over 10 or not type/subtype is refused")
    void badContentTypesAreRefused() {
        String rule = "capabilities.storage[0].contentTypes must be 1 to 10 distinct type/subtype values, without wildcards";
        for (String types : List.of("[]", "[\"image/*\"]", "[\"*/*\"]", "[\"image/png\",\"image/png\"]",
                "[\"image\"]", "[\"Image/PNG\"]", "[1]", "\"image/png\"",
                "[\"a/a\",\"a/b\",\"a/c\",\"a/d\",\"a/e\",\"a/f\",\"a/g\",\"a/h\",\"a/i\",\"a/j\",\"a/k\"]")) {
            refused(manifest(null, route(null), storage("{\"contentTypes\":" + types + ",\"maxBytes\":100}")), rule);
        }
        refused(manifest(null, route(null), storage("{\"maxBytes\":100}")), rule);
    }

    @Test
    @DisplayName("maxBytes 0, negative, fractional, over 10485760, text or absent is refused, naming the value")
    void badMaxBytesAreRefused() {
        String rule = "capabilities.storage[0].maxBytes must be a whole number of bytes between 1 and 10485760 (got ";
        for (String bytes : List.of("0", "-1", "5.5", "10485761", "\"100\"", "null")) {
            String got = bytes.equals("\"100\"") ? "\"100\"" : bytes;
            refused(manifest(null, route(null), storage("{\"contentTypes\":[\"image/png\"],\"maxBytes\":" + bytes + "}")),
                    rule + got + ")");
        }
        refused(manifest(null, route(null), storage("{\"contentTypes\":[\"image/png\"]}")), rule + "null)");
    }

    @Test
    @DisplayName("a storage element is a closed object")
    void aStorageElementIsAClosedObject() {
        refused(manifest(null, route(null), storage("{\"contentTypes\":[\"image/png\"],\"maxBytes\":100,\"bucket\":\"x\"}")),
                "capabilities.storage[0]: \"bucket\" is not a key of a storage element (known: contentTypes, maxBytes)");
        refused(manifest(null, route(null), storage("\"image/png\"")), "capabilities.storage[0] must be an object");
    }

    // ---- tenant.after.delete --------------------------------------------------------------------------------------

    @Test
    @DisplayName("the hook point tenant.after.delete is declared; onFailure is refused on it (an after point)")
    void tenantAfterDeleteIsAnAfterPoint() {
        accepted(manifest(null, route(null), "\"hooks\":[{\"point\":\"" + HookEvents.TENANT_AFTER_DELETE + "\"}]"));
        refused(manifest(null, route(null), "\"hooks\":[{\"point\":\"tenant.after.delete\",\"onFailure\":\"block\"}]"),
                ".onFailure only on before points");
    }
}
