package ro.kf.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol 2.1 (sdk-protocol.md §17): {@code capabilities.flows}, the module's baseline flows, checked locally
 * with the platform's rules and messages (ModuleManifestValidator / ModulePackValidator) -- only the grammar
 * and, on a pack, the files: the BPMN is compiled by the platform, and whether it delivers {@code MODULE_FLOWS} is its
 * gate, never the SDK's.
 */
class ManifestFlowsTest {

    private static String manifest(int schemaVersion, String flows) {
        return """
                { "schemaVersion": %d, "slug": "kf-3d", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
                  "i18n": { "en": { "module.name": "3D", "flow.twin": "Digital twin sample" } },
                  "capabilities": { "flows": [ %s ] } }""".formatted(schemaVersion, flows);
    }

    private static final String TWIN =
            "{ \"key\": \"kf-3d-digital-twin\", \"file\": \"flows/kf-3d-digital-twin.bpmn\", \"name\": \"flow.twin\" }";

    private static List<String> errors(String json) {
        return ManifestCheck.validate(json).errors();
    }

    private static void refused(List<String> errors, String fragment) {
        assertTrue(errors.stream().anyMatch(e -> e.contains(fragment)), "an error must contain <" + fragment + ">: " + errors);
    }

    @Test
    void aModuleFlowIsValidUnderSchema3AndReadAsDeclared() {
        String json = manifest(3, TWIN);
        assertTrue(errors(json).isEmpty(), errors(json).toString());
        Manifest.FlowCapability flow = Manifest.parse(json).capabilities().flows().get(0);
        assertEquals("kf-3d-digital-twin", flow.key());
        assertEquals("flows/kf-3d-digital-twin.bpmn", flow.file());
        assertEquals("flow.twin", flow.name());
        assertTrue(Manifest.CAPABILITY_KEYS.contains("flows"), "flows is a capability of protocol 2.1");
    }

    @Test
    void absentFlowsReadAsAnEmptyList() {
        String json = manifest(3, "").replace("\"flows\": [  ]", "\"routes\": []");
        assertTrue(Manifest.parse(json).capabilities().flows().isEmpty());
    }

    @Test
    void flowsUnderSchema2AreRefusedNamed() {
        refused(errors(manifest(2, TWIN)),
                "capabilities.flows requires \"schemaVersion\": 3 (this manifest is schemaVersion 2)");
    }

    @Test
    void theKeyHasItsGrammarAndIsUnique() {
        refused(errors(manifest(3, "{ \"key\": \"Twin\", \"file\": \"flows/twin.bpmn\", \"name\": \"flow.twin\" }")),
                "capabilities.flows[0].key \"Twin\" must match ^[a-z][a-z0-9-]{2,62}$");
        refused(errors(manifest(3, "{ \"key\": \"ab\", \"file\": \"flows/ab.bpmn\", \"name\": \"flow.twin\" }")),
                "capabilities.flows[0].key \"ab\" must match");
        refused(errors(manifest(3, TWIN + ", "
                        + "{ \"key\": \"kf-3d-digital-twin\", \"file\": \"flows/other.bpmn\", \"name\": \"flow.twin\" }")),
                "capabilities.flows[1].key \"kf-3d-digital-twin\" is declared twice");
    }

    @Test
    void theFileIsABpmnDirectlyUnderFlowsWithoutDotDotAndUnique() {
        refused(errors(manifest(3, "{ \"key\": \"kf-3d-a\", \"file\": \"flows/../x.bpmn\", \"name\": \"flow.twin\" }")),
                "capabilities.flows[0].file \"flows/../x.bpmn\" must not contain \"..\"");
        refused(errors(manifest(3, "{ \"key\": \"kf-3d-a\", \"file\": \"bpmn/a.bpmn\", \"name\": \"flow.twin\" }")),
                "capabilities.flows[0].file \"bpmn/a.bpmn\" must be a .bpmn file directly under flows/");
        refused(errors(manifest(3, "{ \"key\": \"kf-3d-a\", \"file\": \"flows/sub/a.bpmn\", \"name\": \"flow.twin\" }")),
                "must be a .bpmn file directly under flows/");
        refused(errors(manifest(3, "{ \"key\": \"kf-3d-a\", \"file\": \"flows/a.xml\", \"name\": \"flow.twin\" }")),
                "capabilities.flows[0].file \"flows/a.xml\" must be a .bpmn file directly under flows/");
        refused(errors(manifest(3, "{ \"key\": \"kf-3d-a\", \"name\": \"flow.twin\" }")),
                "capabilities.flows[0].file null must be a .bpmn file directly under flows/");
        refused(errors(manifest(3, TWIN + ", "
                        + "{ \"key\": \"kf-3d-other\", \"file\": \"flows/kf-3d-digital-twin.bpmn\", \"name\": \"flow.twin\" }")),
                "capabilities.flows[1].file \"flows/kf-3d-digital-twin.bpmn\" is declared twice");
    }

    @Test
    void theNameIsAKeyOfI18nEnAndTheObjectIsClosed() {
        refused(errors(manifest(3, "{ \"key\": \"kf-3d-a\", \"file\": \"flows/a.bpmn\", \"name\": \"flow.missing\" }")),
                "capabilities.flows[0].name \"flow.missing\" is not defined in i18n.en");
        refused(errors(manifest(3, "{ \"key\": \"kf-3d-a\", \"file\": \"flows/a.bpmn\" }")),
                "capabilities.flows[0].name is required: a translation key defined in i18n.en");
        refused(errors(manifest(3,
                        "{ \"key\": \"kf-3d-a\", \"file\": \"flows/a.bpmn\", \"name\": \"flow.twin\", \"bpmn\": \"<x/>\" }")),
                "capabilities.flows[0]: \"bpmn\" is not a key of a module flow (known: key, file, name)");
    }

    @Test
    void atMostTwentyFlows() {
        StringBuilder flows = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            if (i > 0) flows.append(", ");
            flows.append("{ \"key\": \"kf-3d-f").append(i).append("\", \"file\": \"flows/f").append(i)
                    .append(".bpmn\", \"name\": \"flow.twin\" }");
        }
        refused(errors(manifest(3, flows.toString())), "capabilities.flows holds at most 20 flows (got 21)");
    }

    @Test
    void checkPackFindsAMissingOrTooLargeFile() {
        String json = manifest(3, TWIN);
        assertTrue(ManifestCheck.checkPack(json, Map.of("flows/kf-3d-digital-twin.bpmn", 1024L)).ok());
        assertTrue(ManifestCheck.checkPack(json, Map.of("flows/kf-3d-digital-twin.bpmn", 262_144L)).ok(),
                "256 KiB is the cap, inclusive");
        refused(ManifestCheck.checkPack(json, Map.of()).errors(),
                "capabilities.flows[0].file \"flows/kf-3d-digital-twin.bpmn\" is not in the pack");
        refused(ManifestCheck.checkPack(json, null).errors(), "is not in the pack");
        refused(ManifestCheck.checkPack(json, Map.of("flows/kf-3d-digital-twin.bpmn", 262_145L)).errors(),
                "capabilities.flows[0].file \"flows/kf-3d-digital-twin.bpmn\" is larger than 262144 bytes (got 262145)");
    }

    @Test
    void checkPackKeepsTheManifestRulesAndReportsABadFileOnce() {
        List<String> errors = ManifestCheck.checkPack(
                manifest(3, "{ \"key\": \"kf-3d-a\", \"file\": \"flows/../a.bpmn\", \"name\": \"flow.twin\" }"), Map.of())
                .errors();
        assertEquals(1, errors.size(), errors.toString());
        refused(errors, "must not contain \"..\"");
    }
}
