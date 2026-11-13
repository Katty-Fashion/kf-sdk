package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol 2.0, manifest {@code schemaVersion} 3: the module slots
 * {@code capabilities.slots} (sdk-protocol.md §7.12, called "§7.11" by the plans). Schema 3 is a strict
 * superset of 2; {@code slots} is refused under 1–2; every rule of the contract has an accepted case and a refused one.
 */
class ManifestSlotsTest {

    private static final String I18N = """
            "i18n":{"en":{"module.name":"Slots","slot.layers":"Viewport layers"}}""";

    private static String manifest(int schemaVersion, String slots) {
        return """
                {"schemaVersion":%d,"slug":"kf-3d","version":"1.0.0","platform":">=4.1","name":"module.name",%s
                 %s}
                """.formatted(schemaVersion, I18N, slots == null ? "" : ",\"capabilities\":{\"slots\":[" + slots + "]}");
    }

    private static final String LAYERS =
            "{\"key\":\"viewport.layers.v1\",\"kind\":\"B\",\"type\":\"layer\",\"visibility\":\"public\","
                    + "\"label\":\"slot.layers\",\"layerKinds\":[\"pin\",\"highlight\"]}";
    private static final String INSPECTOR =
            "{\"key\":\"studio.inspector.sections.v1\",\"kind\":\"A\",\"type\":\"extension\",\"visibility\":\"internal\","
                    + "\"publicMembers\":[\"model\",\"version\"],\"actions\":[\"select-piece\"]}";
    private static final String EVENTS =
            "{\"key\":\"viewport.events.v1\",\"kind\":\"D\",\"type\":\"events\",\"visibility\":\"public\","
                    + "\"events\":[\"piece-selected\"]}";
    private static final String MATERIALS =
            "{\"key\":\"materials.v1\",\"kind\":\"R\",\"type\":\"resource\",\"visibility\":\"public\","
                    + "\"dataSchema\":\"m.kf-3d.material.v1\"}";

    private static List<String> errors(String json) {
        return ManifestCheck.validate(json).errors();
    }

    private static void refused(String json, String fragment) {
        List<String> errors = errors(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains(fragment)), "an error must contain <" + fragment + ">: " + errors);
    }

    @Test
    @DisplayName("schema 3 is a strict superset of 2: a valid schema 2 manifest is valid with only the number changed")
    void schema3Superset() {
        assertTrue(errors(manifest(2, null)).isEmpty(), errors(manifest(2, null)).toString());
        assertTrue(errors(manifest(3, null)).isEmpty(), errors(manifest(3, null)).toString());
    }

    @Test
    @DisplayName("schemaVersion 4 is refused locally, naming the interval 1..3")
    void schema4Refused() {
        refused(manifest(4, null), "supported: 1..3");
    }

    @Test
    @DisplayName("every kind of slot, used correctly, is accepted under schema 3")
    void allKindsAccepted() {
        String json = manifest(3, String.join(",", LAYERS, INSPECTOR, EVENTS, MATERIALS));
        assertTrue(errors(json).isEmpty(), errors(json).toString());
    }

    @Test
    @DisplayName("capabilities.slots under schema 2 is refused: it requires schemaVersion 3")
    void slotsUnderSchema2() {
        refused(manifest(2, LAYERS), "requires \"schemaVersion\": 3");
    }

    @Test
    @DisplayName("a key without its major as last segment is refused")
    void keyWithoutMajor() {
        refused(manifest(3, LAYERS.replace("viewport.layers.v1", "viewport.layers")), "capabilities.slots[0].key");
    }

    @Test
    @DisplayName("a key declared twice is refused")
    void duplicateKey() {
        refused(manifest(3, LAYERS + "," + LAYERS), "is declared twice");
    }

    @Test
    @DisplayName("a type that is not one of the kind's is refused")
    void kindTypeMismatch() {
        refused(manifest(3, LAYERS.replace("\"type\":\"layer\"", "\"type\":\"tab\"")), "capabilities.slots[0].type");
    }

    @Test
    @DisplayName("visibility is mandatory, without a default")
    void visibilityMissing() {
        refused(manifest(3, LAYERS.replace("\"visibility\":\"public\",", "")), "capabilities.slots[0].visibility");
    }

    @Test
    @DisplayName("a slot of kind B without layerKinds is refused")
    void layerWithoutLayerKinds() {
        refused(manifest(3, LAYERS.replace(",\"layerKinds\":[\"pin\",\"highlight\"]", "")),
                "capabilities.slots[0].layerKinds");
    }

    @Test
    @DisplayName("a slot of kind R without dataSchema is refused (data only)")
    void resourceWithoutSchema() {
        refused(manifest(3, MATERIALS.replace(",\"dataSchema\":\"m.kf-3d.material.v1\"", "")),
                "capabilities.slots[0].dataSchema");
    }

    @Test
    @DisplayName("a key outside the closed object of a slot is refused")
    void unknownField() {
        refused(manifest(3, LAYERS.replace("\"kind\":\"B\"", "\"kind\":\"B\",\"code\":\"x.js\"")),
                "\"code\" is not a key of a slot");
    }

    @Test
    @DisplayName("a key of another kind is refused (layerKinds only on kind B)")
    void keyOfAnotherKind() {
        refused(manifest(3, INSPECTOR.replace("\"actions\"", "\"layerKinds\":[\"pin\"],\"actions\"")), "only on kind B");
    }

    @Test
    @DisplayName("events on a commands slot and a dataSchema of another owner are refused")
    void eventsOnCommandsAndForeignSchema() {
        refused(manifest(3, EVENTS.replace("\"type\":\"events\"", "\"type\":\"commands\"")), "capabilities.slots[0].commands");
        refused(manifest(3, MATERIALS.replace("m.kf-3d.material.v1", "m.other.material.v1")),
                "capabilities.slots[0].dataSchema");
    }

    @Test
    @DisplayName("a label that is not a key of i18n.en is refused")
    void labelNotAKey() {
        refused(manifest(3, LAYERS.replace("slot.layers", "slot.missing")), "capabilities.slots[0].label");
    }
}
