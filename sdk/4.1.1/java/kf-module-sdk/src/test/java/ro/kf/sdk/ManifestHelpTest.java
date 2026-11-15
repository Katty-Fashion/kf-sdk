package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capability {@code help} of {@code kf-module-conf.json} (protocol 2.2, additive, sdk-protocol.md §7.13): the
 * module's help notes, each a link to a {@code module.pages} page or a {@code setup.sections} section the same
 * manifest declares. Known only under {@code schemaVersion} 3. The SDK reads them on the typed manifest and refuses
 * locally, with the texts of §7.13 (the platform's validator uses the same), everything but whether a platform code
 * named in {@code right} is declared.
 */
class ManifestHelpTest {

    private static final String SURFACES = """
            "routes":[{"method":"GET","path":"/m/help-mod/shells","right":"api.v1.m.help-mod.shells.get"}],
            "surfaces":[{"slug":"module.pages","kind":"react","page":"utils-help"},
                        {"slug":"module.pages","kind":"react","page":"utils-shells"},
                        {"slug":"module.pages","kind":"react","page":"mapping"},
                        {"slug":"setup.sections","kind":"react","page":"platforms"}]""";

    static String manifest(int schemaVersion, String help) {
        return """
                {"schemaVersion":%d,"slug":"help-mod","version":"1.0.0","platform":">=4.1","name":"module.name",
                 "i18n":{"en":{"module.name":"Help module","page.utils-help":"AAS help",
                               "help.aas-overview.summary":"What the AAS pages do","help.platforms":"Platforms"}},
                 "capabilities":{%s%s}}
                """.formatted(schemaVersion, SURFACES, help == null ? "" : ",\"help\":" + help);
    }

    static final String NOTE = """
            {"key":"aas-overview","title":"page.utils-help","summary":"help.aas-overview.summary","page":"utils-help",
             "context":["utils-shells","mapping"],"order":10}""";

    static List<String> errors(String help) {
        return ManifestCheck.validate(manifest(3, help)).errors();
    }

    static void refused(String help, String expected) {
        List<String> errors = errors(help);
        assertFalse(errors.isEmpty(), help);
        assertTrue(errors.stream().anyMatch(e -> e.contains(expected)), expected + " not in " + errors);
    }

    @Test
    @DisplayName("a valid page note and a valid section note are read in the declared order, order 100 by default")
    void aValidNoteIsReadAndAccepted() {
        String json = manifest(3, "[" + NOTE + ",{\"key\":\"platforms\",\"title\":\"help.platforms\","
                + "\"section\":\"platforms\"}]");
        ManifestCheck.Result result = ManifestCheck.validate(json);
        assertTrue(result.ok(), result.errors().toString());
        List<Manifest.HelpCapability> help = Manifest.parse(json).capabilities().help();
        assertEquals(2, help.size());
        Manifest.HelpCapability note = help.get(0);
        assertEquals("aas-overview", note.key());
        assertEquals("page.utils-help", note.title());
        assertEquals("help.aas-overview.summary", note.summary());
        assertEquals("utils-help", note.page());
        assertNull(note.section());
        assertEquals(List.of("utils-shells", "mapping"), note.context());
        assertNull(note.right());
        assertEquals(10, note.order());
        Manifest.HelpCapability section = help.get(1);
        assertEquals("platforms", section.section());
        assertNull(section.page());
        assertNull(section.summary());
        assertTrue(section.context().isEmpty());
        assertEquals(Manifest.HELP_DEFAULT_ORDER, section.order());
    }

    @Test
    @DisplayName("help absent reads as []")
    void absentReadsEmpty() {
        assertTrue(ManifestCheck.validate(manifest(3, null)).ok());
        assertTrue(Manifest.parse(manifest(3, null)).capabilities().help().isEmpty());
    }

    @Test
    @DisplayName("help under schemaVersion 2 is refused with the schema guard of §7.13")
    void underSchema2IsRefused() {
        List<String> errors = ManifestCheck.validate(manifest(2, "[" + NOTE + "]")).errors();
        assertTrue(errors.contains("kf-module-conf.json: capabilities.help requires \"schemaVersion\": 3 "
                + "(this manifest is schemaVersion 2)"), errors.toString());
    }

    private static String note(String extra) {
        return "{\"key\":\"aas-overview\",\"title\":\"page.utils-help\"" + extra + "}";
    }

    @Test
    @DisplayName("a page that is not declared on module.pages is refused (a setup section is not a page)")
    void pageUndeclaredIsRefused() {
        refused("[" + note(",\"page\":\"nowhere\"") + "]", "kf-module-conf.json: capabilities.help[0].page \"nowhere\" is not a page this module "
                + "declares on module.pages (capabilities.surfaces[].page)");
        refused("[" + note(",\"page\":\"platforms\"") + "]", "kf-module-conf.json: capabilities.help[0].page \"platforms\" is not a page");
    }

    @Test
    @DisplayName("a section that is not declared on setup.sections is refused (a module page is not a section)")
    void sectionUndeclaredIsRefused() {
        refused("[" + note(",\"section\":\"utils-help\"") + "]", "kf-module-conf.json: capabilities.help[0].section \"utils-help\" is not a section "
                + "this module declares on setup.sections (capabilities.surfaces[].page)");
    }

    @Test
    @DisplayName("no target, or both targets, is refused")
    void exactlyOneTarget() {
        String text = "kf-module-conf.json: capabilities.help[0] must declare exactly one target: \"page\" (a module.pages page) or \"section\" "
                + "(a setup.sections section)";
        refused("[" + note("") + "]", text);
        refused("[" + note(",\"page\":\"utils-help\",\"section\":\"platforms\"") + "]", text);
    }

    @Test
    @DisplayName("title and summary must be keys of i18n.en; title is required")
    void titleAndSummaryAreKeys() {
        refused("[{\"key\":\"a\",\"title\":\"help.missing\",\"page\":\"utils-help\"}]",
                "kf-module-conf.json: capabilities.help[0].title \"help.missing\" is not defined in i18n.en");
        refused("[" + note(",\"page\":\"utils-help\",\"summary\":\"help.missing\"") + "]",
                "kf-module-conf.json: capabilities.help[0].summary \"help.missing\" is not defined in i18n.en");
        refused("[{\"key\":\"a\",\"page\":\"utils-help\"}]",
                "kf-module-conf.json: capabilities.help[0].title is required: a translation key defined in i18n.en");
        refused("[" + note(",\"page\":\"utils-help\",\"summary\":\"Not A Key\"") + "]",
                "kf-module-conf.json: capabilities.help[0].summary \"Not A Key\" is not a translation key");
    }

    @Test
    @DisplayName("a repeated key, or one outside the grammar, is refused")
    void keyRules() {
        String one = note(",\"page\":\"utils-help\"");
        refused("[" + one + "," + one + "]",
                "kf-module-conf.json: capabilities.help[1].key \"aas-overview\" is declared twice");
        refused("[{\"key\":\"AasOverview\",\"title\":\"page.utils-help\",\"page\":\"utils-help\"}]",
                "kf-module-conf.json: capabilities.help[0].key must match ^[a-z][a-z0-9-]{0,40}$ (got \"AasOverview\")");
        refused("[{\"title\":\"page.utils-help\",\"page\":\"utils-help\"}]",
                "kf-module-conf.json: capabilities.help[0].key must match ^[a-z][a-z0-9-]{0,40}$ (got null)");
    }

    @Test
    @DisplayName("a context page that is not declared, repeated, or a context that is not an array is refused")
    void contextRules() {
        refused("[" + note(",\"page\":\"utils-help\",\"context\":[\"utils-shells\",\"nowhere\"]") + "]",
                "kf-module-conf.json: capabilities.help[0].context[1] \"nowhere\" is not a page this module declares on module.pages "
                        + "(capabilities.surfaces[].page)");
        refused("[" + note(",\"page\":\"utils-help\",\"context\":[\"mapping\",\"mapping\"]") + "]",
                "kf-module-conf.json: capabilities.help[0].context[1] \"mapping\" is listed twice");
        refused("[" + note(",\"page\":\"utils-help\",\"context\":\"mapping\"") + "]",
                "kf-module-conf.json: capabilities.help[0].context must be an array of module.pages pages (got \"mapping\")");
    }

    @Test
    @DisplayName("url and body are not keys of a help note (a closed object)")
    void unknownFieldsAreRefused() {
        refused("[" + note(",\"page\":\"utils-help\",\"url\":\"https://wiki.example/aas\"") + "]",
                "kf-module-conf.json: capabilities.help[0]: \"url\" is not a key of a help note (known: key, title, summary, page, section, context, right, order)");
        refused("[" + note(",\"page\":\"utils-help\",\"body\":\"help.body\"") + "]",
                "kf-module-conf.json: capabilities.help[0]: \"body\" is not a key of a help note");
        refused("[\"aas-overview\"]", "kf-module-conf.json: capabilities.help[0] must be an object");
    }

    @Test
    @DisplayName("more than 20 notes, an order outside 0-10000, a right that is not an own route's are refused")
    void limitsOrderAndRight() {
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 21; i++) {
            if (i > 0) many.append(',');
            many.append("{\"key\":\"n").append(i).append("\",\"title\":\"page.utils-help\",\"page\":\"utils-help\"}");
        }
        refused(many.append(']').toString(), "kf-module-conf.json: capabilities.help holds at most 20 notes (got 21)");
        refused("[" + note(",\"page\":\"utils-help\",\"order\":10001") + "]",
                "kf-module-conf.json: capabilities.help[0].order must be an integer from 0 to 10000 (got 10001)");
        refused("[" + note(",\"page\":\"utils-help\",\"order\":-1") + "]",
                "kf-module-conf.json: capabilities.help[0].order must be an integer from 0 to 10000 (got -1)");
        refused("[" + note(",\"page\":\"utils-help\",\"right\":\"api.v1.m.help-mod.secret.get\"") + "]",
                "kf-module-conf.json: capabilities.help[0].right \"api.v1.m.help-mod.secret.get\" is not the right of a route this module declares "
                        + "(capabilities.routes[].right of api.v1.m.help-mod.*)");
        refused("[" + note(",\"page\":\"utils-help\",\"right\":\"Not a right\"") + "]",
                "kf-module-conf.json: capabilities.help[0].right \"Not a right\" is not a right code: the right of one of the module's routes or a code "
                        + "the platform declares");
        assertTrue(errors("[" + note(",\"page\":\"utils-help\",\"right\":\"api.v1.customers.get\",\"order\":0") + "]")
                .isEmpty(), "a platform code is the platform's check at upload");
    }
}
