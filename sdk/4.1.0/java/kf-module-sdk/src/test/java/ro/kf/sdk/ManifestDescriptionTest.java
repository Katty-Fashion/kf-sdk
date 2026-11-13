package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional top-level {@code description} of {@code kf-module-conf.json}: the
 * same shape as {@code name} — under {@code schemaVersion} 2 a translation key defined in {@code i18n.en}, absent (or
 * {@code null}) when the module has none. The SDK reads it on the typed manifest and the local check refuses a key
 * that is malformed, blank or not defined in {@code en}, like the platform at upload.
 */
class ManifestDescriptionTest {

    private static String manifest(String description) {
        return """
                {"schemaVersion":2,"slug":"desc-mod","version":"1.0.0","platform":">=4.1","name":"module.name"%s,
                 "i18n":{"en":{"module.name":"Description","module.description":"Adds an author tab"},
                         "ro":{"module.description":"Adaugă un tab de autor"}}}
                """.formatted(description == null ? "" : ",\"description\":" + description);
    }

    @Test
    @DisplayName("a description key defined in en is read and accepted")
    void descriptionIsRead() {
        Manifest m = Manifest.parse(manifest("\"module.description\""));
        assertEquals("module.description", m.description());
        assertEquals("Adds an author tab", m.enText(m.description()));
        assertTrue(ManifestCheck.validate(manifest("\"module.description\"")).ok());
    }

    @Test
    @DisplayName("an absent or null description is legal and reads null")
    void absentAndNullAreLegal() {
        assertTrue(ManifestCheck.validate(manifest(null)).ok());
        assertTrue(ManifestCheck.validate(manifest("null")).ok());
        assertNull(Manifest.parse(manifest(null)).description());
        assertNull(Manifest.parse(manifest("null")).description());
    }

    @Test
    @DisplayName("a description that is not a key of en is refused, naming the description")
    void notAKeyOfEnIsRefused() {
        String[][] cases = {
                {"\"module.missing\"", "\"description\" \"module.missing\" is not defined in i18n.en"},
                {"\"Adds an author tab\"", "\"description\" \"Adds an author tab\" is not a translation key"},
                {"\"\"", "\"description\" is required: a translation key defined in i18n.en"},
        };
        for (String[] c : cases) {
            ManifestCheck.Result result = ManifestCheck.validate(manifest(c[0]));
            assertFalse(result.ok(), c[0]);
            assertEquals(1, result.errors().size(), c[0] + ": " + result.errors());
            assertTrue(result.errors().get(0).contains(c[1]), result.errors().get(0));
        }
    }
}
