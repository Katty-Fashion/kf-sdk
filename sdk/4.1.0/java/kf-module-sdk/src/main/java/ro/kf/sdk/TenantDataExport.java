package ro.kf.sdk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The tenant-data bundle a module answers on {@code GET /m/{slug}/admin/export?tenantId=&part=} (protocol 1.3 §15.8): the shape its own {@code POST /m/{slug}/admin/import?mode=dry-run|apply} takes
 * back unchanged. The platform calls the route (with its own context, {@code system} via {@code platform}) at the
 * module's uninstall and before a tenant's hard delete, and keeps the bundles in its bucket.
 *
 * <pre>{@code
 * Map<String, Object> bundle = TenantDataExport.bundle("kf-feedback/tenant-data/v1", tenantId, 1, 1,
 *         Map.of("tickets", tickets.size()), Map.of("tickets", tickets));
 * }</pre>
 *
 * <p>The common keys ({@link #COMMON_KEYS}): {@code schema} ({@code <slug>/tenant-data/v<N>}, the value the manifest
 * declares in {@code tenantData.format}), {@code tenantId}, {@code exportedAt} (ISO-8601), {@code part} and
 * {@code parts} ({@code 1 <= part <= parts}), {@code counts} (the module's tallies, which its import checks) and
 * {@code contentSha256}. The module's own content sits at the top level beside them, as in the
 * {@code kf-<slug>/tenant-data/v1} bundles of today; it may also carry {@code source} and {@code warnings}, which are
 * informative.
 *
 * <p>{@code contentSha256} is the SHA-256 (lowercase hex) of the <strong>canonical JSON</strong> of the bundle without
 * {@link #NOT_HASHED} ({@code exportedAt}, {@code source}, {@code warnings}, {@code contentSha256}): object keys sorted
 * at every level, compact, UTF-8 -- the form the modules' {@code TenantDataImport} recompute (a different hash is their
 * {@code 422 bundle-altered}). A number keeps the form Jackson gives it (an integer as digits); a bundle that must hash
 * the same in another language carries its decimal numbers as strings.
 */
public final class TenantDataExport {

    /** The module's route the platform calls, under {@code /m/{slug}} ({@code admin: platform}, a {@code GET}). */
    public static final String PATH = "/admin/export";
    /** The keys of every bundle, in the order {@link #bundle} writes them (the module's content goes before counts). */
    public static final List<String> COMMON_KEYS = List.of("schema", "tenantId", "exportedAt", "part", "parts", "counts",
            "contentSha256");
    /** The keys left out of {@code contentSha256}. */
    public static final List<String> NOT_HASHED = List.of("exportedAt", "source", "warnings", "contentSha256");
    /** {@code <slug>/tenant-data/v<N>}: the module's slug, then the major version of its bundle. */
    public static final Pattern SCHEMA = Pattern.compile("^[a-z0-9][a-z0-9-]{1,40}/tenant-data/v[1-9][0-9]{0,3}$");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private TenantDataExport() {
    }

    /** {@link #bundle(String, UUID, Instant, int, int, Map, Map)} exported now. */
    public static Map<String, Object> bundle(String schema, UUID tenantId, int part, int parts, Map<String, ?> counts,
                                             Map<String, ?> content) {
        return bundle(schema, tenantId, Instant.now(), part, parts, counts, content);
    }

    /**
     * One part of a module's export for {@code tenantId}: the common keys, the module's {@code content} at the top
     * level, {@code contentSha256} computed last.
     *
     * @throws IllegalArgumentException a schema outside {@link #SCHEMA}, no tenant, no counts, {@code part} outside
     *                                  {@code 1..parts}, or content that carries a common key
     */
    public static Map<String, Object> bundle(String schema, UUID tenantId, Instant exportedAt, int part, int parts,
                                             Map<String, ?> counts, Map<String, ?> content) {
        List<String> errors = new ArrayList<>();
        if (schema == null || !SCHEMA.matcher(schema).matches()) {
            errors.add("schema must be <slug>/tenant-data/v<N> (got " + schema + ")");
        }
        if (tenantId == null) errors.add("tenantId is required");
        if (exportedAt == null) errors.add("exportedAt is required");
        if (parts < 1 || part < 1 || part > parts) {
            errors.add("part and parts must satisfy 1 <= part <= parts (got " + part + "/" + parts + ")");
        }
        if (counts == null) errors.add("counts is required (the tallies the module's import checks)");
        Map<String, ?> own = content == null ? Map.of() : content;
        for (String key : own.keySet()) {
            if (COMMON_KEYS.contains(key)) errors.add("the content cannot carry the common key " + key);
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("invalid tenant-data bundle: " + String.join("; ", errors));
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("schema", schema);
        bundle.put("tenantId", tenantId.toString());
        bundle.put("exportedAt", exportedAt.toString());
        bundle.put("part", part);
        bundle.put("parts", parts);
        bundle.putAll(own);
        bundle.put("counts", counts);
        bundle.put("contentSha256", contentSha256(bundle));
        return bundle;
    }

    /** The {@code contentSha256} of {@code bundle}: its canonical JSON without {@link #NOT_HASHED}, SHA-256, hex. */
    public static String contentSha256(Map<String, ?> bundle) {
        Map<String, Object> content = new LinkedHashMap<>(bundle);
        NOT_HASHED.forEach(content::remove);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(content).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The canonical JSON of {@code value}: object keys sorted at every level, compact, arrays in order. */
    public static String canonicalJson(Object value) {
        JsonNode tree = JSON.valueToTree(value);
        return JSON.writeValueAsString(sorted(tree));
    }

    private static JsonNode sorted(JsonNode node) {
        if (node == null) return NODES.nullNode();
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            for (var e : node.properties()) names.add(e.getKey());
            Collections.sort(names);
            ObjectNode out = NODES.objectNode();
            for (String name : names) out.set(name, sorted(node.get(name)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = NODES.arrayNode();
            for (JsonNode item : node) out.add(sorted(item));
            return out;
        }
        return node;
    }
}
