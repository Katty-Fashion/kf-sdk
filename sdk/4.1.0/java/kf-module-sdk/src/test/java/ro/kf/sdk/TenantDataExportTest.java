package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TenantDataExport} (protocol 1.3 §15.8): the bundle a module answers on
 * {@code GET /m/{slug}/admin/export} is the one its {@code admin/import} takes back unchanged. The fixture is
 * {@code kf-feedback}'s own import fixture ({@code the module's import fixture},
 * copied): its {@code contentSha256} was computed by another implementation (Node) and is accepted by
 * {@code kf-feedback}'s {@code TenantDataImport}, so the same hash here proves the SDK's canonical form is the
 * importers' one.
 */
class TenantDataExportTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String FIXTURE_SHA = "a55243f587fc62451c3a3df811101b2d0e9a2f5089eb38c9ac2488309a658bec";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fixture() throws IOException {
        try (InputStream in = TenantDataExportTest.class.getResourceAsStream("tenant-data/tenant-data-sample.json")) {
            return JSON.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class);
        }
    }

    @Test
    @DisplayName("the hash of kf-feedback's import fixture is the one it carries (the importers' canonical form)")
    void hashOfTheImportFixture() throws Exception {
        Map<String, Object> bundle = fixture();

        assertEquals(FIXTURE_SHA, bundle.get("contentSha256"));
        assertEquals(FIXTURE_SHA, TenantDataExport.contentSha256(bundle));
    }

    @Test
    @DisplayName("a bundle built from the same content has every common field and the same hash, whatever exportedAt and source")
    void builtBundleHasTheCommonFieldsAndTheSameHash() throws Exception {
        Map<String, Object> fixture = fixture();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("tickets", fixture.get("tickets"));
        content.put("warnings", fixture.get("warnings"));
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) fixture.get("counts");

        Map<String, Object> bundle = TenantDataExport.bundle("kf-feedback/tenant-data/v1", TENANT,
                Instant.parse("2026-10-30T18:00:00Z"), 1, 1, counts, content);

        assertEquals(List.of("schema", "tenantId", "exportedAt", "part", "parts", "tickets", "warnings", "counts",
                "contentSha256"), List.copyOf(bundle.keySet()));
        assertEquals("kf-feedback/tenant-data/v1", bundle.get("schema"));
        assertEquals(TENANT.toString(), bundle.get("tenantId"));
        assertEquals("2026-10-30T18:00:00Z", bundle.get("exportedAt"));
        assertEquals(1, bundle.get("part"));
        assertEquals(1, bundle.get("parts"));
        assertEquals(FIXTURE_SHA, bundle.get("contentSha256"), "exportedAt, source and warnings are not hashed");
        assertEquals(FIXTURE_SHA, TenantDataExport.contentSha256(bundle));
    }

    @Test
    @DisplayName("a changed ticket changes the hash: the importer would refuse it (bundle-altered)")
    void changedContentChangesTheHash() throws Exception {
        Map<String, Object> bundle = fixture();
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) bundle.get("tickets")).get(0);
        first.put("message", "changed");

        assertNotEquals(FIXTURE_SHA, TenantDataExport.contentSha256(bundle));
    }

    @Test
    @DisplayName("the canonical form: keys sorted at every level, compact, arrays kept in order, null kept")
    void canonicalForm() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("d", true);
        inner.put("c", List.of(3, "x", Map.of("z", 1)));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", 1);
        value.put("a", inner);
        value.put("n", null);
        value.put("s", "ă/\"");

        assertEquals("{\"a\":{\"c\":[3,\"x\",{\"z\":1}],\"d\":true},\"b\":1,\"n\":null,\"s\":\"ă/\\\"\"}",
                TenantDataExport.canonicalJson(value));
    }

    @Test
    @DisplayName("part and parts: 1 ≤ part ≤ parts; the schema, the tenant and the counts are required")
    void envelopeRules() {
        Map<String, Object> counts = Map.of("tickets", 0);
        Map<String, Object> content = Map.of("tickets", List.of());
        String schema = "kf-feedback/tenant-data/v1";

        assertThrows(IllegalArgumentException.class, () -> TenantDataExport.bundle(schema, TENANT, 0, 1, counts, content));
        assertThrows(IllegalArgumentException.class, () -> TenantDataExport.bundle(schema, TENANT, 3, 2, counts, content));
        assertThrows(IllegalArgumentException.class, () -> TenantDataExport.bundle(schema, TENANT, 1, 0, counts, content));
        assertThrows(IllegalArgumentException.class, () -> TenantDataExport.bundle(null, TENANT, 1, 1, counts, content));
        assertThrows(IllegalArgumentException.class,
                () -> TenantDataExport.bundle("feedback/v1", TENANT, 1, 1, counts, content));
        assertThrows(IllegalArgumentException.class, () -> TenantDataExport.bundle(schema, null, 1, 1, counts, content));
        assertThrows(IllegalArgumentException.class, () -> TenantDataExport.bundle(schema, TENANT, 1, 1, null, content));
        Map<String, Object> second = TenantDataExport.bundle(schema, TENANT, 2, 2, counts, content);
        assertEquals(2, second.get("part"));
        assertFalse(((String) second.get("exportedAt")).isBlank());
    }

    @Test
    @DisplayName("the content cannot carry a common key: it would overwrite the envelope")
    void contentCannotCarryACommonKey() {
        for (String key : TenantDataExport.COMMON_KEYS) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TenantDataExport.bundle(
                    "kf-qc/tenant-data/v1", TENANT, 1, 1, Map.of(), Map.of(key, "x")), key);
            assertTrue(e.getMessage().contains(key), e.getMessage());
        }
    }
}
