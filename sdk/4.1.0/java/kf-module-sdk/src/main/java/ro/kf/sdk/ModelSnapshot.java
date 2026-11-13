package ro.kf.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A model as the platform sends it: {@code {id, name, materialList, extensions: {slug: {field: value}}}}.
 * {@code id} is null in {@code model.before.create}.
 *
 * <p>{@code versionExtensions}: a version's values in the model's envelope, {@code {versionId: {slug: {field: value}}}}
 * read from {@code versions.{versionId}.extensions} (protocol 1.1, §5.1: a module's write on {@code model_version} is an
 * update of the version's model and carries only the fields written); empty when the snapshot has no {@code versions}.
 */
public record ModelSnapshot(String id, String name, List<Material> materialList, Map<String, Map<String, Object>> extensions,
                            Map<String, Map<String, Map<String, Object>>> versionExtensions) {

    /** The snapshot without version values (the form before SDK 0.7.4). */
    public ModelSnapshot(String id, String name, List<Material> materialList, Map<String, Map<String, Object>> extensions) {
        this(id, name, materialList, extensions, Map.of());
    }

    /** Value of {@code extensions.{slug}.{field}}, or null. */
    public Object extension(String slug, String field) {
        Map<String, Object> fields = extensions.get(slug);
        return fields == null ? null : fields.get(field);
    }

    /**
     * Value of {@code versions.{versionId}.extensions.{slug}.{field}}, or null when the snapshot does not carry that
     * version, module or field (§5.1; SDK 0.7.4).
     */
    public Object versionExtension(String versionId, String slug, String field) {
        Map<String, Map<String, Object>> modules = versionExtensions.get(versionId);
        Map<String, Object> fields = modules == null ? null : modules.get(slug);
        return fields == null ? null : fields.get(field);
    }

    static ModelSnapshot fromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        List<Material> materials = new ArrayList<>();
        if (m.get("materialList") instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map<?, ?> item) materials.add(Material.fromMap(item));
        }
        Map<String, Map<String, Map<String, Object>>> versions = new LinkedHashMap<>();
        if (m.get("versions") instanceof Map<?, ?> byId) {
            byId.forEach((versionId, version) -> {
                if (version instanceof Map<?, ?> v) {
                    Map<String, Map<String, Object>> ext = extensionsOf(v.get("extensions"));
                    if (!ext.isEmpty()) versions.put(String.valueOf(versionId), ext);
                }
            });
        }
        return new ModelSnapshot(m.get("id") == null ? null : String.valueOf(m.get("id")),
                m.get("name") == null ? null : String.valueOf(m.get("name")),
                List.copyOf(materials), extensionsOf(m.get("extensions")), Collections.unmodifiableMap(versions));
    }

    /** {@code {slug: {field: value}}}; an entry that is not an object is skipped. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> extensionsOf(Object raw) {
        Map<String, Map<String, Object>> ext = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> e) {
            e.forEach((slug, fields) -> {
                if (fields instanceof Map<?, ?> f) ext.put(String.valueOf(slug), Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) f)));
            });
        }
        return Collections.unmodifiableMap(ext);
    }
}
