package ro.kf.sdk;

import ro.kf.sdk.HookEvents.AfterHandler;
import ro.kf.sdk.HookEvents.BeforeHandler;
import ro.kf.sdk.HookEvents.FieldChangeHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hooks on the platform's models ({@code kf.models()}) and, from protocol 2.0, on its orders ({@code kf.orders()}: the
 * same shape on the points {@code order.*}, public, sdk-protocol.md §16.2; a patch there carries only the
 * module's own {@code extensions}, the SDK refuses one on a core field). Several handlers for the same event run in declaration order inside the module;
 * the platform sees one hook per event:
 * <ul>
 *   <li>{@code priority}: the lowest declared (default {@link #DEFAULT_PRIORITY}, lower runs first);</li>
 *   <li>{@code onFailure} (before hooks only): {@link OnFailure#BLOCK} if any handler of the event declares
 *       it, else {@link OnFailure#ALLOW}; always sent explicitly. {@code block} also needs
 *       {@code capabilities.hooks[].onFailure = "block"} in {@code kf-module-conf.json} (checked locally by
 *       {@link ManifestCheck} and by the platform).</li>
 * </ul>
 * The hook points are hand-written constants of {@link HookEvents} until the catalog generates them.
 */
public final class ModelHooks {

    public static final int DEFAULT_PRIORITY = 100;

    /**
     * What the platform does when this module's before hook fails (timeout, error, unreadable answer): {@code allow}
     * lets the mutation through (the default: a module that is down must not stop every tenant from saving),
     * {@code block} refuses it.
     */
    public enum OnFailure {
        ALLOW("allow"),
        BLOCK("block");

        private final String wire;

        OnFailure(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    record Before(int priority, OnFailure onFailure, BeforeHandler handler) {
    }

    record FieldWatch(List<String> paths, FieldChangeHandler handler) {
        boolean matches(String path) {
            return paths.isEmpty() || paths.contains(path);
        }
    }

    final Map<String, List<Before>> before = new LinkedHashMap<>();
    final Map<String, List<AfterHandler>> after = new LinkedHashMap<>();
    final List<FieldWatch> fieldWatches = new ArrayList<>();

    /** The entity of the points: {@code model} ({@code kf.models()}) or {@code order} ({@code kf.orders()}, §16.2). */
    private final String entity;

    ModelHooks() {
        this("model");
    }

    /** The same hooks on the points {@code {entity}.before|after.*} and {@code {entity}.field.changed}. */
    ModelHooks(String entity) {
        this.entity = Objects.requireNonNull(entity, "entity");
    }

    /** The entity of these hooks' points ({@code model} or {@code order}). */
    public String entity() {
        return entity;
    }

    /** {@code {entity}.field.changed}. */
    String fieldChanged() {
        return entity + ".field.changed";
    }

    // ---- before -----------------------------------------------------------------------------

    public ModelHooks beforeCreate(BeforeHandler handler) {
        return beforeCreate(DEFAULT_PRIORITY, OnFailure.ALLOW, handler);
    }

    public ModelHooks beforeCreate(int priority, BeforeHandler handler) {
        return beforeCreate(priority, OnFailure.ALLOW, handler);
    }

    public ModelHooks beforeCreate(int priority, OnFailure onFailure, BeforeHandler handler) {
        return addBefore(entity + ".before.create", priority, onFailure, handler);
    }

    public ModelHooks beforeUpdate(BeforeHandler handler) {
        return beforeUpdate(DEFAULT_PRIORITY, OnFailure.ALLOW, handler);
    }

    public ModelHooks beforeUpdate(int priority, BeforeHandler handler) {
        return beforeUpdate(priority, OnFailure.ALLOW, handler);
    }

    public ModelHooks beforeUpdate(int priority, OnFailure onFailure, BeforeHandler handler) {
        return addBefore(entity + ".before.update", priority, onFailure, handler);
    }

    /** Patches are not allowed on delete: return allow or reject. */
    public ModelHooks beforeDelete(BeforeHandler handler) {
        return beforeDelete(DEFAULT_PRIORITY, OnFailure.ALLOW, handler);
    }

    public ModelHooks beforeDelete(int priority, BeforeHandler handler) {
        return beforeDelete(priority, OnFailure.ALLOW, handler);
    }

    public ModelHooks beforeDelete(int priority, OnFailure onFailure, BeforeHandler handler) {
        return addBefore(entity + ".before.delete", priority, onFailure, handler);
    }

    // ---- after (asynchronous, retried by the platform until a 2xx) ---------------------------

    public ModelHooks afterCreate(AfterHandler handler) {
        return addAfter(entity + ".after.create", handler);
    }

    public ModelHooks afterUpdate(AfterHandler handler) {
        return addAfter(entity + ".after.update", handler);
    }

    public ModelHooks afterDelete(AfterHandler handler) {
        return addAfter(entity + ".after.delete", handler);
    }

    // ---- field changes ----------------------------------------------------------------------

    /**
     * Called once per changed field among {@code paths} ({@code name}, {@code materialList},
     * {@code extensions.{slug}.{field}}); an empty list means every field.
     */
    public ModelHooks onFieldChange(List<String> paths, FieldChangeHandler handler) {
        fieldWatches.add(new FieldWatch(List.copyOf(paths), handler));
        return this;
    }

    public ModelHooks onFieldChange(String path, FieldChangeHandler handler) {
        return onFieldChange(List.of(path), handler);
    }

    public ModelHooks onFieldChange(FieldChangeHandler handler, String... paths) {
        return onFieldChange(Arrays.asList(paths), handler);
    }

    // ---- internals --------------------------------------------------------------------------

    private ModelHooks addBefore(String event, int priority, OnFailure onFailure, BeforeHandler handler) {
        before.computeIfAbsent(event, e -> new ArrayList<>())
                .add(new Before(priority, Objects.requireNonNull(onFailure, "onFailure"), Objects.requireNonNull(handler, "handler")));
        return this;
    }

    private ModelHooks addAfter(String event, AfterHandler handler) {
        after.computeIfAbsent(event, e -> new ArrayList<>()).add(Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** The {@code hooks} part of the registration body. */
    List<Map<String, Object>> registration() {
        List<Map<String, Object>> hooks = new ArrayList<>();
        before.forEach((event, handlers) -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("event", event);
            h.put("priority", handlers.stream().mapToInt(Before::priority).min().orElse(DEFAULT_PRIORITY));
            boolean block = handlers.stream().anyMatch(b -> b.onFailure() == OnFailure.BLOCK);
            h.put("onFailure", (block ? OnFailure.BLOCK : OnFailure.ALLOW).wire());
            hooks.add(h);
        });
        after.keySet().forEach(event -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("event", event);
            hooks.add(h);
        });
        if (!fieldWatches.isEmpty()) {
            boolean all = fieldWatches.stream().anyMatch(w -> w.paths().isEmpty());
            List<String> union = all ? List.of()
                    : fieldWatches.stream().flatMap(w -> w.paths().stream()).distinct().toList();
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("event", fieldChanged());
            h.put("fields", union);
            hooks.add(h);
        }
        return hooks;
    }
}
