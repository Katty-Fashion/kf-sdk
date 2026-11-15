package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code capabilities.fields[].editable}: a boolean, {@code false}
 * when absent, a rule of the host's generic rendering, not of writing. The SDK reads it on the typed manifest and the
 * local check refuses a value that is not a boolean ({@code null} included), like the platform at
 * upload. The generic-field rule itself is fixed only in sdk-protocol.md: no conformance fixture.
 */
class ManifestEditableTest {

    private static String manifest(String editable) {
        return """
                {"schemaVersion":2,"slug":"edit-mod","version":"1.0.0","platform":">=4.1","name":"module.name",
                 "i18n":{"en":{"module.name":"Editable","field.author":"Author","field.note":"Note"}},
                 "capabilities":{"fields":[
                   {"entity":"model","name":"author","type":"STRING","table":"models_extra","label":"field.author"%s},
                   {"entity":"model","name":"note","type":"STRING","table":"models_extra","label":"field.note"}]}}
                """.formatted(editable == null ? "" : ",\"editable\":" + editable);
    }

    @Test
    @DisplayName("editable true is read; a field without it reads false")
    void editableIsRead() {
        Manifest m = Manifest.parse(manifest("true"));
        assertTrue(m.capabilities().fields().get(0).editable());
        assertFalse(m.capabilities().fields().get(1).editable());
        assertTrue(ManifestCheck.validate(manifest("true")).ok());
    }

    @Test
    @DisplayName("editable false and absent are legal")
    void falseAndAbsentAreLegal() {
        assertTrue(ManifestCheck.validate(manifest("false")).ok());
        assertTrue(ManifestCheck.validate(manifest(null)).ok());
        assertFalse(Manifest.parse(manifest(null)).capabilities().fields().get(0).editable());
    }

    @Test
    @DisplayName("editable that is not a boolean is refused, naming the field (null included)")
    void notABooleanIsRefused() {
        for (String value : new String[] {"\"yes\"", "1", "null"}) {
            ManifestCheck.Result result = ManifestCheck.validate(manifest(value));
            assertFalse(result.ok(), value);
            assertEquals(1, result.errors().size(), value + ": " + result.errors());
            assertTrue(result.errors().get(0).contains("capabilities.fields[0].editable must be a boolean"),
                    result.errors().get(0));
            assertFalse(Manifest.parse(manifest(value)).capabilities().fields().get(0).editable(), value);
        }
    }
}
