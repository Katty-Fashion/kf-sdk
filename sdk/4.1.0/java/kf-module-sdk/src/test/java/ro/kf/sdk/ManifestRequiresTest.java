package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code requires} and {@code featureFlags[].requiresModules} checked locally (protocol 2.0), with the platform's messages ({@code ModulePackValidator.validateRequires}, {@code ModuleManifestValidator}):
 * the shape only -- whether the required module exists, a cycle or stay the platform's.
 */
class ManifestRequiresTest {

    private static String manifest(int schemaVersion, String requires, String flags) {
        return """
                {"schemaVersion":%d,"slug":"req-mod","version":"1.0.0","platform":">=4.1","name":"module.name",
                 "i18n":{"en":{"module.name":"Requires","flag.l":"Flag"}},
                 "requires":[%s],"featureFlags":[%s]}
                """.formatted(schemaVersion, requires, flags == null ? "" : flags);
    }

    private static List<String> errors(String json) {
        return ManifestCheck.validate(json).errors();
    }

    private static void refused(String json, String fragment) {
        List<String> errors = errors(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains(fragment)), "an error must contain <" + fragment + ">: " + errors);
    }

    private static final String BASE = "{\"slug\":\"base-mod\",\"versionRange\":\">=1.0.0 <2.0.0\"}";
    private static final String OPTIONAL = "{\"slug\":\"extra-mod\",\"versionRange\":\">=0.5.0\",\"optional\":true}";

    @Test
    @DisplayName("two modules, one optional, under schema 3: valid")
    void validWithOptional() {
        assertTrue(errors(manifest(3, BASE + "," + OPTIONAL, null)).isEmpty(), errors(manifest(3, BASE + "," + OPTIONAL, null)).toString());
    }

    @Test
    @DisplayName("a slug that is not one, the module itself, a duplicate: refused")
    void slugRules() {
        refused(manifest(2, "{\"slug\":\"Bad Slug\",\"versionRange\":\">=1.0.0\"}", null), "\"requires\" slug must match");
        refused(manifest(2, "{\"slug\":\"req-mod\",\"versionRange\":\">=1.0.0\"}", null), "a module cannot require itself");
        refused(manifest(2, BASE + "," + BASE, null), "duplicate \"requires\" entry for \"base-mod\"");
    }

    @Test
    @DisplayName("a range the platform cannot parse, or that no version can satisfy: refused with the expected form")
    void rangeRules() {
        refused(manifest(2, "{\"slug\":\"base-mod\",\"versionRange\":\"~1.2\"}", null), "could not be parsed");
        refused(manifest(2, "{\"slug\":\"base-mod\",\"versionRange\":\">=2.0.0 <1.0.0\"}", null), "version range is impossible");
        refused(manifest(2, "{\"slug\":\"base-mod\",\"versionRange\":\"\"}", null), "needs a non-empty \"versionRange\"");
    }

    @Test
    @DisplayName("optional under schema 2 is refused; under 3 it must be a boolean")
    void optionalRules() {
        refused(manifest(2, OPTIONAL, null), "\"requires[0].optional\" requires \"schemaVersion\": 3");
        refused(manifest(3, "{\"slug\":\"base-mod\",\"versionRange\":\">=1.0.0\",\"optional\":\"yes\"}", null),
                "\"requires[0].optional\" must be a boolean");
    }

    @Test
    @DisplayName("requiresModules: valid when declared in requires under schema 3, refused otherwise")
    void requiresModulesRules() {
        String flag = "{\"key\":\"needs\",\"label\":\"flag.l\",\"requiresModules\":[\"extra-mod\"]}";
        assertTrue(errors(manifest(3, BASE + "," + OPTIONAL, flag)).isEmpty(), errors(manifest(3, BASE + "," + OPTIONAL, flag)).toString());
        refused(manifest(3, BASE, flag), "\"featureFlags[0].requiresModules\": \"extra-mod\" is not in requires");
        refused(manifest(2, BASE, flag.replace("extra-mod", "base-mod")),
                "\"featureFlags[0].requiresModules\" requires \"schemaVersion\": 3");
    }
}
