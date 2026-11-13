package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional top-level {@code rollbackFloor} of {@code kf-module-conf.json}: the oldest
 * version of the module that still runs on the data (the module's own database) this version leaves behind. A semver
 * at or below {@code version}; absent (or {@code null}) when the module declares none. The SDK reads it on the typed
 * manifest and the local check refuses a value that is not semver or is above {@code version}, naming the key.
 */
class ManifestRollbackFloorTest {

    private static String manifest(String version, String rollbackFloor) {
        return """
                {"schemaVersion":2,"slug":"floor-mod","version":"%s","platform":">=4.1","name":"module.name"%s,
                 "i18n":{"en":{"module.name":"Rollback floor"}}}
                """.formatted(version, rollbackFloor == null ? "" : ",\"rollbackFloor\":" + rollbackFloor);
    }

    @Test
    @DisplayName("a rollbackFloor below version is read and accepted")
    void floorBelowVersionIsAccepted() {
        String json = manifest("1.2.0", "\"1.1.0\"");
        ManifestCheck.Result result = ManifestCheck.validate(json);
        assertTrue(result.ok(), result.errors().toString());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        assertEquals("1.1.0", Manifest.parse(json).rollbackFloor());
    }

    @Test
    @DisplayName("a rollbackFloor equal to version is accepted: no older version runs on this data")
    void floorEqualToVersionIsAccepted() {
        String json = manifest("1.2.0", "\"1.2.0\"");
        assertTrue(ManifestCheck.validate(json).ok());
        assertEquals("1.2.0", Manifest.parse(json).rollbackFloor());
    }

    @Test
    @DisplayName("an absent or null rollbackFloor is legal and reads null")
    void absentAndNullAreLegal() {
        assertTrue(ManifestCheck.validate(manifest("1.2.0", null)).ok());
        assertTrue(ManifestCheck.validate(manifest("1.2.0", "null")).ok());
        assertNull(Manifest.parse(manifest("1.2.0", null)).rollbackFloor());
        assertNull(Manifest.parse(manifest("1.2.0", "null")).rollbackFloor());
    }

    @Test
    @DisplayName("a rollbackFloor above version is refused, naming the key and both versions")
    void floorAboveVersionIsRefused() {
        String[][] cases = {
                {"1.2.0", "\"1.3.0\"", "\"rollbackFloor\" \"1.3.0\" is above \"version\" \"1.2.0\""},
                {"1.9.0", "\"1.10.0\"", "\"rollbackFloor\" \"1.10.0\" is above \"version\" \"1.9.0\""},
                {"1.2.0-rc.1", "\"1.2.0\"", "\"rollbackFloor\" \"1.2.0\" is above \"version\" \"1.2.0-rc.1\""},
        };
        for (String[] c : cases) {
            ManifestCheck.Result result = ManifestCheck.validate(manifest(c[0], c[1]));
            assertFalse(result.ok(), c[1]);
            assertEquals(1, result.errors().size(), c[1] + ": " + result.errors());
            assertTrue(result.errors().get(0).contains(c[2]), result.errors().get(0));
        }
        // semver order, not text order: 1.10.0 is above 1.9.0, so 1.9.0 is a legal floor of 1.10.0
        assertTrue(ManifestCheck.validate(manifest("1.10.0", "\"1.9.0\"")).ok());
    }

    @Test
    @DisplayName("a rollbackFloor that is not a semver 2.0 version is refused, naming the key")
    void notSemverIsRefused() {
        String[][] cases = {
                {"\"one\"", "\"rollbackFloor\" must be a semver 2.0 version, e.g. \"1.1.0\" (got \"one\")"},
                {"\"1.1\"", "\"rollbackFloor\" must be a semver 2.0 version, e.g. \"1.1.0\" (got \"1.1\")"},
                {"\"\"", "\"rollbackFloor\" must be a semver 2.0 version, e.g. \"1.1.0\" (got \"\")"},
                {"110", "\"rollbackFloor\" must be a semver 2.0 version, e.g. \"1.1.0\" (got 110)"},
        };
        for (String[] c : cases) {
            ManifestCheck.Result result = ManifestCheck.validate(manifest("1.2.0", c[0]));
            assertFalse(result.ok(), c[0]);
            assertEquals(1, result.errors().size(), c[0] + ": " + result.errors());
            assertTrue(result.errors().get(0).contains(c[1]), result.errors().get(0));
        }
        assertNull(Manifest.parse(manifest("1.2.0", "110")).rollbackFloor());
    }
}
