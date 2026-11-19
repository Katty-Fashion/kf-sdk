package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A version's values in the model's envelope (protocol 1.1, sdk-protocol.md §5.1): a
 * module's write on {@code model_version} is an update of the version's model, with
 * {@code versions.{versionId}.extensions.{slug}.{field}} in {@code changed} and the values under
 * {@code before/after.versions} -- read through {@link ModelSnapshot#versionExtension} and the changed version paths
 * (SDK 0.7.4).
 */
class VersionExtensionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The data of the example of §5.1, with a second field and a second version. */
    private static final String DATA = """
            { "entity": "model", "id": "42",
              "before": { "id": 42, "name": "Rochie Ana", "materialList": [],
                          "extensions": { "kf-aas-r3": { "aas_shell": "model-shell" } },
                          "versions": { "17": { "extensions": { "kf-aas-r3": { "aas_shell": null, "aas_pushed": false } } },
                                        "18": { "extensions": { "kf-aas-r3": { "aas_shell": "old" } } } } },
              "after":  { "id": 42, "name": "Rochie Ana", "materialList": [],
                          "extensions": { "kf-aas-r3": { "aas_shell": "model-shell" } },
                          "versions": { "17": { "extensions": { "kf-aas-r3": { "aas_shell": "TechnicalPack-17", "aas_pushed": true } } },
                                        "18": { "extensions": { "kf-aas-r3": { "aas_shell": "new" } } } } },
              "changed": [ "versions.17.extensions.kf-aas-r3.aas_shell", "versions.17.extensions.kf-aas-r3.aas_pushed",
                           "versions.18.extensions.kf-aas-r3.aas_shell" ] }
            """;

    private static final Map<String, Object> ENVELOPE = Map.of("type", "kf.model.before.update", "id", "d-1",
            "kfeventid", "e-1", "kftenant", "t-1", "kfenvelopeversion", "1", "kfmodule", "conformance");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(String json) {
        return JSON.readValue(json, Map.class);
    }

    @Test
    @DisplayName("versionExtension reads versions.{id}.extensions.{slug}.{field} on before and after; the model's own extensions stay as stored")
    void snapshotReadsTheVersionValues() {
        Map<String, Object> data = data(DATA);
        ModelSnapshot before = ModelSnapshot.fromMap(data.get("before"));
        ModelSnapshot after = ModelSnapshot.fromMap(data.get("after"));

        assertNull(before.versionExtension("17", "kf-aas-r3", "aas_shell"));
        assertEquals("TechnicalPack-17", after.versionExtension("17", "kf-aas-r3", "aas_shell"));
        assertEquals(false, before.versionExtension("17", "kf-aas-r3", "aas_pushed"));
        assertEquals(true, after.versionExtension("17", "kf-aas-r3", "aas_pushed"));
        assertEquals("old", before.versionExtension("18", "kf-aas-r3", "aas_shell"));
        assertEquals("new", after.versionExtension("18", "kf-aas-r3", "aas_shell"));
        assertEquals("model-shell", after.extension("kf-aas-r3", "aas_shell"));
        assertEquals(List.of("17", "18"), List.copyOf(after.versionExtensions().keySet()));
    }

    @Test
    @DisplayName("versionExtension is null for a version, a module or a field the snapshot does not carry, and an envelope without versions reads empty")
    void absentIsNull() {
        ModelSnapshot after = ModelSnapshot.fromMap(data(DATA).get("after"));
        assertNull(after.versionExtension("19", "kf-aas-r3", "aas_shell"));
        assertNull(after.versionExtension("17", "other-module", "aas_shell"));
        assertNull(after.versionExtension("17", "kf-aas-r3", "missing"));

        ModelSnapshot plain = ModelSnapshot.fromMap(data("""
                { "after": { "id": 42, "name": "Rochie", "materialList": [], "extensions": {} } }
                """).get("after"));
        assertTrue(plain.versionExtensions().isEmpty());
        assertNull(plain.versionExtension("17", "kf-aas-r3", "aas_shell"));

        ModelSnapshot odd = ModelSnapshot.fromMap(data("""
                { "after": { "versions": { "17": "not-an-object", "18": { "extensions": "not-an-object" },
                                           "19": { "extensions": { "kf-aas-r3": "not-an-object" } } } } }
                """).get("after"));
        assertTrue(odd.versionExtensions().isEmpty(), "shapes that are not objects are skipped: " + odd.versionExtensions());
    }

    @Test
    @DisplayName("VersionField.parse reads versions.{versionId}.extensions.{slug}.{field} and nothing else; path() writes it back")
    void parsesOnlyVersionPaths() {
        HookEvents.VersionField f = HookEvents.VersionField.parse("versions.17.extensions.kf-aas-r3.aas_shell");
        assertEquals(new HookEvents.VersionField("17", "kf-aas-r3", "aas_shell"), f);
        assertEquals("versions.17.extensions.kf-aas-r3.aas_shell", f.path());

        for (String notOne : new String[]{"name", "materialList", "extensions.kf-aas-r3.aas_shell",
                "versions.17.extensions.kf-aas-r3", "versions.17.extensions.kf-aas-r3.aas_shell.more",
                "versions..extensions.kf-aas-r3.aas_shell", "versions.17.other.kf-aas-r3.aas_shell",
                "versions.17.extensions..aas_shell", "versions.17.extensions.kf-aas-r3.", "colors.17.extensions.a.b", ""}) {
            assertNull(HookEvents.VersionField.parse(notOne), notOne);
        }
        assertNull(HookEvents.VersionField.parse(null));
    }

    @Test
    @DisplayName("a before hook sees the changed version paths and the versions they touch, in order, without the model's own paths")
    void beforeContextChangedVersions() {
        Map<String, Object> data = data(DATA);
        BeforeContext ctx = new BeforeContext("conformance", HookEvents.BEFORE_UPDATE, HookEvents.Envelope.of(ENVELOPE), data);

        assertEquals(List.of(new HookEvents.VersionField("17", "kf-aas-r3", "aas_shell"),
                new HookEvents.VersionField("17", "kf-aas-r3", "aas_pushed"),
                new HookEvents.VersionField("18", "kf-aas-r3", "aas_shell")), ctx.changedVersionFields());
        assertEquals(List.of("17", "18"), ctx.changedVersionIds());
        assertEquals("TechnicalPack-17", ctx.after().versionExtension("17", "kf-aas-r3", "aas_shell"));

        BeforeContext model = new BeforeContext("conformance", HookEvents.BEFORE_UPDATE, HookEvents.Envelope.of(ENVELOPE),
                data("""
                        { "id": "42", "changed": ["name", "extensions.conformance.author"] }
                        """));
        assertEquals(List.of(), model.changedVersionFields());
        assertEquals(List.of(), model.changedVersionIds());
    }

    @Test
    @DisplayName("an after event sees the same, and a field change names its version path (null for a model path)")
    void afterAndFieldChange() {
        Map<String, Object> data = data(DATA);
        HookEvents.AfterEvent after = HookEvents.AfterEvent.of(HookEvents.AFTER_UPDATE, HookEvents.Envelope.of(ENVELOPE), data);
        assertEquals(List.of("17", "18"), after.changedVersionIds());
        assertEquals(3, after.changedVersionFields().size());
        assertEquals("new", after.after().versionExtension("18", "kf-aas-r3", "aas_shell"));

        data.put("field", "versions.18.extensions.kf-aas-r3.aas_shell");
        HookEvents.FieldChange change = HookEvents.FieldChange.of(HookEvents.Envelope.of(ENVELOPE), data);
        assertEquals(new HookEvents.VersionField("18", "kf-aas-r3", "aas_shell"), change.versionField());

        data.put("field", "name");
        assertNull(HookEvents.FieldChange.of(HookEvents.Envelope.of(ENVELOPE), data).versionField());
    }
}
