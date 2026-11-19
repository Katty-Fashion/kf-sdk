package ro.kf.sdk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Answer of a before-hook: {@link Allow}, {@link Reject} (vetoes the operation, 422 on the platform, translated on the
 * user's language) or {@link Patch} (implies allow; own fields and the module's own extensions; not on delete).
 * Get them from {@link BeforeContext#allow()}, {@link BeforeContext#reject(String, Map)}, {@link BeforeContext#patch()}.
 */
public sealed interface Decision permits Decision.Allow, Decision.Reject, Decision.Patch {

    /** The key grammar of the manifest's {@code i18n} block (the platform's {@code HookDecision.KEY}). */
    Pattern KEY = Pattern.compile("^[a-z][a-z0-9_.-]{0,119}$");

    Map<String, Object> toJson();

    /**
     * A veto with a translation key and its parameters. A key or parameters the platform considers malformed are
     * dropped there, but the veto stands: the SDK logs a warning and sends them unchanged.
     */
    static Decision reject(String key, Map<String, Object> params) {
        Objects.requireNonNull(key, "key");
        if (!KEY.matcher(key).matches()) {
            System.getLogger("ro.kf.sdk").log(System.Logger.Level.WARNING, "KF SDK: veto key \"" + key + "\" does not "
                    + "match " + KEY.pattern() + "; the platform keeps the veto but drops the key");
        }
        return new Reject(key, params == null ? Map.of() : params);
    }

    record Allow() implements Decision {
        @Override
        public Map<String, Object> toJson() {
            return Map.of("decision", "allow");
        }
    }

    /** {@code {"decision":"reject","key":…,"params":{…}}}; {@code params} is always sent ({@code {}}). */
    record Reject(String key, Map<String, Object> params) implements Decision {

        public Reject {
            params = params == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decision", "reject");
            m.put("key", key);
            m.put("params", params);
            return m;
        }
    }

    /** Builder-style patch; every setter returns this, so it can be returned directly from a handler. */
    final class Patch implements Decision {
        private final String slug;
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final Map<String, Object> ownExtensions = new LinkedHashMap<>();

        Patch(String slug) {
            this.slug = slug;
        }

        public Patch name(String name) {
            values.put("name", name);
            return this;
        }

        public Patch materialList(List<Material> materials) {
            values.put("materialList", materials.stream().map(Material::toMap).toList());
            return this;
        }

        /**
         * Sets one own field of the entity (the platform's patch guard decides which are patchable).
         * {@code extensions} is not set here: use {@link #extension}.
         */
        public Patch set(String field, Object value) {
            if ("extensions".equals(field)) throw new IllegalArgumentException("use extension(field, value)");
            values.put(Objects.requireNonNull(field, "field"), value);
            return this;
        }

        /** Sets {@code extensions.{own slug}.{field}}. */
        public Patch extension(String field, Object value) {
            ownExtensions.put(field, value);
            return this;
        }

        public boolean isEmpty() {
            return values.isEmpty() && ownExtensions.isEmpty();
        }

        /** Merges another patch into this one (later wins). */
        Patch merge(Patch other) {
            values.putAll(other.values);
            ownExtensions.putAll(other.ownExtensions);
            return this;
        }

        public Map<String, Object> values() {
            Map<String, Object> patch = new LinkedHashMap<>(values);
            if (!ownExtensions.isEmpty()) patch.put("extensions", Map.of(slug, new LinkedHashMap<>(ownExtensions)));
            return patch;
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decision", "patch");
            m.put("patch", values());
            return m;
        }
    }
}
