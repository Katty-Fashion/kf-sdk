package ro.kf.sdk;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol 2.0 (sdk-protocol.md §16): the keys of the order flow, checked locally with the platform's rules and
 * messages (ModuleManifestValidator / SurfaceValidator) -- only the grammar: an older platform /
 * refuses them at upload, the SDK never does.
 */
class ManifestOrderKeysTest {

    private static final String ROUTES = """
            "routes": [ { "method": "GET", "path": "/m/kf-step/rules/ready", "right": "api.v1.m.kf-step.rules.ready.get" },
                        { "method": "POST", "path": "/m/kf-step/items", "right": "api.v1.m.kf-step.items.post" } ]""";

    private static String manifest(int schemaVersion, String capabilities) {
        return """
                { "schemaVersion": %d, "slug": "kf-step", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
                  "i18n": { "en": { "module.name": "Step", "rule.blocked": "Not ready" } },
                  "capabilities": { %s, %s } }""".formatted(schemaVersion, ROUTES, capabilities);
    }

    private static final String FLOW_RULE = """
            "flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/ready", "projection": "order.flow-rule.v1" } ]""";
    private static final String SERVICES = "\"services\": [ { \"port\": \"order.services.v1\" } ]";
    private static final String STEP = """
            "surfaces": [ { "slug": "orderflow.step", "kind": "react", "key": "item-check" } ]""";
    private static final String FLOW_HOOK = "\"hooks\": [ { \"point\": \"workflow.flow-step-entered\" } ]";

    private static List<String> errors(String json) {
        return ManifestCheck.validate(json).errors();
    }

    private static void refused(String json, String fragment) {
        List<String> errors = errors(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains(fragment)), "an error must contain <" + fragment + ">: " + errors);
    }

    @Test
    void theOrderKeysAreValidUnderSchema3AndReadAsDeclared() {
        String json = manifest(3, FLOW_RULE + ", " + SERVICES + ", " + STEP + ", " + FLOW_HOOK);
        assertTrue(errors(json).isEmpty(), errors(json).toString());
        Manifest m = Manifest.parse(json);
        Manifest.FlowRuleCapability rule = m.capabilities().flowRules().get(0);
        assertEquals("item-ready", rule.key());
        assertEquals("/m/kf-step/rules/ready", rule.path());
        assertNull(rule.timeoutSeconds());
        assertEquals(2, rule.effectiveTimeoutSeconds(), "absent = the default 2 s");
        assertEquals("order.flow-rule.v1", rule.projection());
        assertEquals("order.services.v1", m.capabilities().services().get(0).port());
        assertEquals("item-check", m.capabilities().surfaces().get(0).key());
        assertEquals("workflow.flow-step-entered", m.capabilities().hooks().get(0).point());
    }

    @Test
    void flowRulesAndServicesUnderSchema2AreRefusedEachNamed() {
        String json = manifest(2, FLOW_RULE + ", " + SERVICES);
        refused(json, "capabilities.flowRules requires \"schemaVersion\": 3 (this manifest is schemaVersion 2)");
        refused(json, "capabilities.services requires \"schemaVersion\": 3 (this manifest is schemaVersion 2)");
    }

    @Test
    void aFlowRuleIsAClosedObjectWithItsGrammar() {
        refused(manifest(3, """
                "flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/missing", "projection": "order.flow-rule.v1" } ]"""),
                "capabilities.flowRules[0].path \"/m/kf-step/rules/missing\" is not a GET route this module declares");
        refused(manifest(3, """
                "flowRules": [ { "key": "item-ready", "path": "/m/kf-step/items", "projection": "order.flow-rule.v1" } ]"""),
                "is not a GET route this module declares");
        refused(manifest(3, """
                "flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/ready", "timeoutSeconds": 61, "projection": "order.flow-rule.v1" } ]"""),
                "capabilities.flowRules[0].timeoutSeconds 61 must be a whole number from 1 to 60");
        refused(manifest(3, """
                "flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/ready", "timeoutSeconds": 0, "projection": "order.flow-rule.v1" } ]"""),
                "timeoutSeconds 0 must be a whole number from 1 to 60");
        refused(manifest(3, """
                "flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/ready", "projection": "order.flow-rule.v2" } ]"""),
                "capabilities.flowRules[0].projection \"order.flow-rule.v2\" must be \"order.flow-rule.v1\"");
        refused(manifest(3, """
                "flowRules": [ { "key": "Item", "path": "/m/kf-step/rules/ready", "projection": "order.flow-rule.v1" } ]"""),
                "capabilities.flowRules[0].key \"Item\" must match ^[a-z][a-z0-9-]{1,40}$");
        refused(manifest(3, """
                "flowRules": [ { "key": "a-rule", "path": "/m/kf-step/rules/ready", "projection": "order.flow-rule.v1" },
                               { "key": "a-rule", "path": "/m/kf-step/rules/ready", "projection": "order.flow-rule.v1" } ]"""),
                "capabilities.flowRules[1].key \"a-rule\" is declared twice");
        refused(manifest(3, """
                "flowRules": [ { "key": "a-rule", "path": "/m/kf-step/rules/ready", "projection": "order.flow-rule.v1", "evaluate": "x" } ]"""),
                "\"evaluate\" is not a key of a flow rule");
        String withTimeout = manifest(3, """
                "flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/ready", "timeoutSeconds": 10, "projection": "order.flow-rule.v1" } ]""");
        assertTrue(errors(withTimeout).isEmpty(), errors(withTimeout).toString());
        assertEquals(10, Manifest.parse(withTimeout).capabilities().flowRules().get(0).effectiveTimeoutSeconds());
    }

    @Test
    void aServiceNamesOnlyOrderServicesV1Once() {
        refused(manifest(3, "\"services\": [ { \"port\": \"order.costs.v1\" } ]"),
                "capabilities.services[0].port \"order.costs.v1\" must be \"order.services.v1\"");
        refused(manifest(3, "\"services\": [ { \"port\": \"order.services.v1\" }, { \"port\": \"order.services.v1\" } ]"),
                "capabilities.services[1].port \"order.services.v1\" is declared twice");
        refused(manifest(3, "\"services\": [ { \"port\": \"order.services.v1\", \"write\": true } ]"),
                "\"write\" is not a key of a service");
    }

    @Test
    void theStepSurfaceNeedsTheKeyOfItsStep() {
        refused(manifest(3, "\"surfaces\": [ { \"slug\": \"orderflow.step\", \"kind\": \"react\" } ]"),
                "capabilities.surfaces[0] on \"orderflow.step\" needs a \"key\": the key of the step it renders");
        refused(manifest(3, "\"surfaces\": [ { \"slug\": \"orderflow.step\", \"kind\": \"react\", \"key\": \"Bad Key\" } ]"),
                "capabilities.surfaces[0].key \"Bad Key\" must match");
    }

    @Test
    void aFlowEventTakesNoOnFailure() {
        refused(manifest(3, "\"hooks\": [ { \"point\": \"workflow.flow-step-completed\", \"onFailure\": \"allow\" } ]"),
                "capabilities.hooks[0].onFailure is not allowed on the flow event \"workflow.flow-step-completed\": it runs "
                        + "after the step and cannot block it");
    }

    @Test
    void theOrderHooksAreOrdinaryPoints() {
        String hooks = manifest(2, """
                "hooks": [ { "point": "order.before.update", "onFailure": "block" }, { "point": "order.field.changed" } ]""");
        assertTrue(errors(hooks).isEmpty(), errors(hooks).toString());
        // capabilities.flows is a capability of protocol 2.1 (ManifestFlowsTest), no longer unknown.
        assertTrue(errors(manifest(3, "\"flows\": []")).isEmpty(), errors(manifest(3, "\"flows\": []")).toString());
    }

    @Test
    void theRegistrationNamesADeclaredStep() {
        String json = manifest(3, STEP);
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("slug", "orderflow.step");
        declared.put("kind", "react");
        declared.put("key", "item-check");
        Map<String, Object> other = new LinkedHashMap<>(declared);
        other.put("key", "another-step");
        Map<String, Object> keyless = new LinkedHashMap<>(declared);
        keyless.remove("key");
        assertTrue(ManifestCheck.check(json, Map.of("hooks", List.of(), "fields", List.of(), "surfaces", List.of(declared)),
                java.util.Set.of()).errors().isEmpty());
        List<String> errors = ManifestCheck.check(json, Map.of("hooks", List.of(), "fields", List.of(),
                "surfaces", List.of(other, keyless)), java.util.Set.of()).errors();
        assertTrue(errors.stream().anyMatch(e -> e.contains("the step \"another-step\" of \"orderflow.step\" is not declared")),
                errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("registration: surfaces[1] on \"orderflow.step\" needs a \"key\"")),
                errors.toString());
    }

    @Test
    void aFlowRulePathWithAParamSegmentIsRefusedWithThePlatformsText() {
        String json = """
                { "schemaVersion": 3, "slug": "kf-step", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
                  "i18n": { "en": { "module.name": "Step" } },
                  "capabilities": { "routes": [ { "method": "GET", "path": "/m/kf-step/rules/{id}", "right": "api.v1.m.kf-step.rules.id.get" } ],
                    "flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/{id}", "projection": "order.flow-rule.v1" } ] } }""";
        refused(json, "kf-module-conf.json: capabilities.flowRules[0].path \"/m/kf-step/rules/{id}\" must be a route with "
                + "literal segments only (no {param} segment)");
    }
}
