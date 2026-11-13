package ro.kf.sdk;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The module's effective feature flags (declared in {@code kf-module-conf.json}, toggled by the platform admin).
 * Fetched once after the registration ({@code GET /api/v1/platform/modules/{slug}/flags} with the module token) and
 * refreshed from the {@code kfflags} attribute of every callback, so a handler always sees the values the platform had
 * when it sent the event.
 *
 * <pre>{@code
 * kf.models().beforeUpdate(ctx -> kf.flags().isEnabled("autocalc") ? ctx.patch().extension("x", 1) : ctx.allow());
 * }</pre>
 */
public final class FeatureFlags {

    private volatile Map<String, Boolean> values = Map.of();
    private volatile boolean known;

    FeatureFlags() {
    }

    /** Effective value; an unknown key (or flags not received yet) is off. */
    public boolean isEnabled(String key) {
        return Boolean.TRUE.equals(values.get(key));
    }

    /** Every flag, sorted by key (read-only). */
    public Map<String, Boolean> all() {
        return values;
    }

    /** True once the flags were fetched or received with a callback. */
    public boolean isKnown() {
        return known;
    }

    void replace(Map<?, ?> raw) {
        Map<String, Boolean> next = new TreeMap<>();
        raw.forEach((k, v) -> next.put(String.valueOf(k), Boolean.TRUE.equals(v)));
        values = Collections.unmodifiableMap(next);
        known = true;
    }
}
