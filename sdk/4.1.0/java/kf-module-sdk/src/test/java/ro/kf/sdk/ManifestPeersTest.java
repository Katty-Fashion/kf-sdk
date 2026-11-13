package ro.kf.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol 2.0 (sdk-protocol.md §3.2, §15.9, §6.3): the declarations between modules, checked locally with the
 * platform's rules and messages -- the cases that need no catalog of the platform.
 */
class ManifestPeersTest {

    private static final String ROUTES = """
            "routes": [ { "method": "GET", "path": "/m/kf-qc/peer/checks", "right": "api.v1.m.kf-qc.peer.checks.get" },
                        { "method": "POST", "path": "/m/kf-qc/peer/checks", "right": "api.v1.m.kf-qc.peer.checks.post" } ]""";

    private static String owner(int schemaVersion, String capabilities) {
        return """
                { "schemaVersion": %d, "slug": "kf-qc", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
                  "i18n": { "en": { "module.name": "Quality" } },
                  "capabilities": { %s, %s } }""".formatted(schemaVersion, ROUTES, capabilities);
    }

    private static String caller(String requires, String peers) {
        return """
                { "schemaVersion": 3, "slug": "kf-3d", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
                  "i18n": { "en": { "module.name": "3D" } }, "requires": %s,
                  "capabilities": { "peers": %s } }""".formatted(requires, peers);
    }

    private static final String REQUIRES_QC = "[ { \"slug\": \"kf-qc\", \"versionRange\": \">=1.0.0\" } ]";

    private static List<String> errors(String json) {
        return ManifestCheck.validate(json).errors();
    }

    private static void refused(String json, String fragment) {
        List<String> errors = errors(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains(fragment)), "an error must contain <" + fragment + ">: " + errors);
    }

    @Test
    void anOwnerAndACallerAreValidAndReadAsDeclared() {
        String json = owner(3, """
                "peerReads": [ { "port": "m.kf-qc.checks.v1", "path": "/m/kf-qc/peer/checks", "visibility": "public" } ],
                "commands": [ { "name": "open-check", "path": "/m/kf-qc/peer/checks", "method": "POST", "visibility": "internal" } ],
                "events": [ { "name": "check-closed", "visibility": "public" } ]""");
        assertTrue(errors(json).isEmpty(), errors(json).toString());
        Manifest manifest = Manifest.parse(json);
        assertEquals("m.kf-qc.checks.v1", manifest.capabilities().peerReads().get(0).port());
        assertEquals("internal", manifest.capabilities().commands().get(0).visibility());
        assertEquals("check-closed", manifest.capabilities().events().get(0).name());

        String use = caller(REQUIRES_QC, "[ { \"owner\": \"kf-qc\", \"reads\": [\"m.kf-qc.checks.v1\"], \"commands\": [\"open-check\"] } ]");
        assertTrue(errors(use).isEmpty(), errors(use).toString());
        assertEquals(List.of("open-check"), Manifest.parse(use).capabilities().peers().get(0).commands());
    }

    @Test
    void underSchema2EveryKeyIsRefused() {
        String json = owner(2, "\"events\": [ { \"name\": \"check-closed\", \"visibility\": \"public\" } ]");
        refused(json, "capabilities.events requires \"schemaVersion\": 3 (this manifest is schemaVersion 2)");
    }

    @Test
    void theGrammarsTheRoutesTheDuplicatesAndTheVisibility() {
        refused(owner(3, "\"peerReads\": [ { \"port\": \"m.kf-3d.checks.v1\", \"path\": \"/m/kf-qc/peer/checks\", \"visibility\": \"public\" } ]"),
                "capabilities.peerReads[0].port \"m.kf-3d.checks.v1\" must be m.kf-qc.{name}.v{N}");
        refused(owner(3, "\"peerReads\": [ { \"port\": \"m.kf-qc.checks.v1\", \"path\": \"/m/kf-qc/nope\", \"visibility\": \"public\" } ]"),
                "is not a GET route this module declares");
        refused(owner(3, "\"commands\": [ { \"name\": \"open-check\", \"path\": \"/m/kf-qc/peer/checks\", \"method\": \"PUT\", \"visibility\": \"public\" } ]"),
                "is not a PUT route this module declares");
        refused(owner(3, "\"commands\": [ { \"name\": \"x\", \"path\": \"/m/kf-qc/peer/checks\", \"method\": \"PATCH\", \"visibility\": \"public\" } ]"),
                "capabilities.commands[0].method \"PATCH\" must be one of POST, PUT, DELETE");
        refused(owner(3, "\"events\": [ { \"name\": \"a-b\", \"visibility\": \"public\" }, { \"name\": \"a-b\", \"visibility\": \"public\" } ]"),
                "capabilities.events[1].name \"a-b\" is declared twice");
        refused(owner(3, "\"events\": [ { \"name\": \"a-b\" } ]"), "capabilities.events[0].visibility null must be one of internal, public");
        refused(owner(3, "\"events\": [ { \"name\": \"a-b\", \"visibility\": \"public\", \"path\": \"/x\" } ]"),
                "\"path\" is not a key of an event");
    }

    @Test
    void aPeerMustBeRequiredNotItselfOnceAndNameSomething() {
        refused(caller("[]", "[ { \"owner\": \"kf-qc\", \"commands\": [\"open-check\"] } ]"),
                "capabilities.peers[0].owner \"kf-qc\" is not in requires");
        refused(caller(REQUIRES_QC, "[ { \"owner\": \"kf-3d\", \"commands\": [\"x-y\"] } ]"), "is this module itself");
        refused(caller(REQUIRES_QC, "[ { \"owner\": \"kf-qc\", \"commands\": [\"a-b\"] }, { \"owner\": \"kf-qc\", \"commands\": [\"c-d\"] } ]"),
                "capabilities.peers[1].owner \"kf-qc\" is declared twice");
        refused(caller(REQUIRES_QC, "[ { \"owner\": \"kf-qc\" } ]"), "must name at least one of reads, commands");
        refused(caller(REQUIRES_QC, "[ { \"owner\": \"kf-qc\", \"reads\": [\"m.kf-3d.x.v1\"] } ]"), "capabilities.peers[0].reads[0]");
        String optional = "[ { \"slug\": \"kf-qc\", \"versionRange\": \">=1.0.0\", \"optional\": true } ]";
        assertTrue(errors(caller(optional, "[ { \"owner\": \"kf-qc\", \"reads\": [\"m.kf-qc.checks.v1\"] } ]")).isEmpty());
    }

    // ---- (an event of a module as a hook) (a route with {param}) ----------------------------

    private static String hooker(int schemaVersion, String requires, String hook) {
        return """
                { "schemaVersion": %d, "slug": "kf-3d", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
                  "i18n": { "en": { "module.name": "3D" } }, "requires": %s,
                  "capabilities": { "hooks": [ %s ] } }""".formatted(schemaVersion, requires, hook);
    }

    private static final String REQUIRES_STOCK = "[ { \"slug\": \"kf-stock\", \"versionRange\": \">=1.0.0\" } ]";

    @Test
    void anEventOfAModuleAsAHookIsCheckedLikeThePlatformDoes() {
        String point = "{ \"point\": \"m.kf-stock.after.level-changed\" }";
        assertTrue(errors(hooker(3, REQUIRES_STOCK, point)).isEmpty(), errors(hooker(3, REQUIRES_STOCK, point)).toString());
        refused(hooker(2, REQUIRES_STOCK, point), "capabilities.hooks[0].point \"m.kf-stock.after.level-changed\": "
                + "an event of a module requires \"schemaVersion\": 3");
        refused(hooker(3, REQUIRES_STOCK, "{ \"point\": \"m.kf-stock.after\" }"),
                "capabilities.hooks[0].point \"m.kf-stock.after\" is not an event of a module (m.{owner}.after.{name})");
        refused(hooker(3, "[]", point), "capabilities.hooks[0].point \"m.kf-stock.after.level-changed\" is an event of "
                + "kf-stock, which this manifest does not require (requires, mandatory or optional)");
        String blocking = "{ \"point\": \"m.kf-stock.after.level-changed\", \"onFailure\": \"block\" }";
        refused(hooker(3, REQUIRES_STOCK, blocking),
                "capabilities.hooks[0].onFailure is not allowed on an event of a module: it runs after the fact");
        assertTrue(errors(hooker(3, REQUIRES_STOCK, blocking)).stream().noneMatch(e -> e.contains("only on before points")),
                errors(hooker(3, REQUIRES_STOCK, blocking)).toString());
        refused(hooker(3, REQUIRES_STOCK, point + ", " + point),
                "capabilities.hooks[1].point \"m.kf-stock.after.level-changed\" is declared twice");
    }

    @Test
    void aPeerPathWithAParamSegmentIsRefusedWithThePlatformsText() {
        String routes = """
                "routes": [ { "method": "GET", "path": "/m/kf-qc/peer/checks/{id}", "right": "api.v1.m.kf-qc.peer.checks.id.get" },
                            { "method": "POST", "path": "/m/kf-qc/peer/checks/{id}", "right": "api.v1.m.kf-qc.peer.checks.id.post" } ]""";
        String json = """
                { "schemaVersion": 3, "slug": "kf-qc", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
                  "i18n": { "en": { "module.name": "Quality" } },
                  "capabilities": { %s, %s } }""";
        String read = json.formatted(routes, "\"peerReads\": [ { \"port\": \"m.kf-qc.checks.v1\", \"path\": \"/m/kf-qc/peer/checks/{id}\", \"visibility\": \"public\" } ]");
        refused(read, "kf-module-conf.json: capabilities.peerReads[0].path \"/m/kf-qc/peer/checks/{id}\" must be a route "
                + "with literal segments only (no {param} segment)");
        String command = json.formatted(routes, "\"commands\": [ { \"name\": \"open-check\", \"path\": \"/m/kf-qc/peer/checks/{id}\", \"method\": \"POST\", \"visibility\": \"public\" } ]");
        refused(command, "kf-module-conf.json: capabilities.commands[0].path \"/m/kf-qc/peer/checks/{id}\" must be a route "
                + "with literal segments only (no {param} segment)");
    }
}
