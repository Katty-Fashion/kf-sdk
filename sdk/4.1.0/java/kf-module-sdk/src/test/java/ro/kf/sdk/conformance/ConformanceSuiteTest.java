package ro.kf.sdk.conformance;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Java runner of the shared conformance suite: every JSON file under {@code sdk/conformance/} (relative to
 * this Maven module: {@code ../../conformance}) becomes one dynamic test.
 *
 * <p>Each fixture is checked against {@code fixture.schema.json} with explicit key checks (the {@code required} list,
 * {@code additionalProperties: false}, the {@code area} enum, {@code id} = {@code <area>/<file>}, a non-blank
 * {@code source}) and handed to {@link FixtureRunner} by area. An area the runner does not know fails, a failing
 * fixture fails, and zero fixtures fail the suite: there is no skip.
 */
class ConformanceSuiteTest {

    static final Path ROOT = Path.of("..", "..", "conformance");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TestFactory
    Stream<DynamicTest> conformanceSuite() throws IOException {
        assertTrue(Files.isDirectory(ROOT), "the conformance suite is not at " + ROOT.toAbsolutePath().normalize());
        Map<String, Object> schema = read(ROOT.resolve("fixture.schema.json"));
        Schema shape = Schema.of(schema);

        List<Path> fixtures = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getParent().equals(ROOT)) // fixture.schema.json and other root files
                    .sorted()
                    .forEach(fixtures::add);
        }
        assertFalse(fixtures.isEmpty(), "0 fixtures found under " + ROOT.toAbsolutePath().normalize());

        Set<String> ids = new HashSet<>();
        return fixtures.stream().map(path -> {
            String rel = ROOT.relativize(path).toString().replace('\\', '/');
            return DynamicTest.dynamicTest(rel, () -> {
                Map<String, Object> fixture = read(path);
                shape.check(rel, fixture);
                String id = (String) fixture.get("id");
                assertTrue(ids.add(id), rel + ": id " + id + " is not unique");
                FixtureRunner.run(fixture);
            });
        });
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path path) throws IOException {
        Object parsed = JSON.readValue(Files.readString(path), Object.class);
        assertInstanceOf(Map.class, parsed, path + " must be a JSON object");
        return (Map<String, Object>) parsed;
    }

    /** The parts of fixture.schema.json a runner enforces, read from the schema itself. */
    record Schema(Set<String> required, Set<String> properties, Set<String> areas) {

        @SuppressWarnings("unchecked")
        static Schema of(Map<String, Object> schema) {
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            Map<String, Object> area = (Map<String, Object>) properties.get("area");
            assertEquals(Boolean.FALSE, schema.get("additionalProperties"), "the schema must close the fixture keys");
            return new Schema(new LinkedHashSet<>((List<String>) schema.get("required")), properties.keySet(),
                    new LinkedHashSet<>((List<String>) area.get("enum")));
        }

        void check(String rel, Map<String, Object> fixture) {
            for (String key : required) {
                if (!fixture.containsKey(key)) fail(rel + ": missing key \"" + key + "\"");
            }
            for (String key : fixture.keySet()) {
                if (!properties.contains(key)) fail(rel + ": key \"" + key + "\" is not allowed by fixture.schema.json");
            }
            for (String key : List.of("id", "area", "description", "source")) {
                if (!(fixture.get(key) instanceof String s) || s.isBlank()) fail(rel + ": \"" + key + "\" must be a non-blank string");
            }
            for (String key : List.of("input", "expect")) {
                if (!(fixture.get(key) instanceof Map)) fail(rel + ": \"" + key + "\" must be an object");
            }
            String area = (String) fixture.get("area");
            if (!areas.contains(area)) fail(rel + ": area \"" + area + "\" is not in fixture.schema.json " + areas);
            String dir = rel.substring(0, rel.indexOf('/'));
            assertEquals(dir, area, rel + ": area must equal the directory name");
            String expectedId = rel.substring(0, rel.length() - ".json".length());
            assertEquals(expectedId, fixture.get("id"), rel + ": id must be <area>/<file name>");
        }
    }
}
