package ro.kf.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A UI contribution to a frontend contact surface of the host (docs/sdk-protocol.md §7).
 *
 * <pre>{@code
 * kf.ui().contribute("models.form.edit.fields.after",
 *         Surface.react("/m/api-test/ui/surfaces.mjs", "AuthorSeasonFields")
 *                 .css("/m/api-test/ui/surfaces.css").order(10).labelKey("surface.author.label").peer("react", "^19.2"));
 * kf.ui().contribute("models.form.edit.fields.after",
 *         Surface.wc("/m/my-mod/ui/surfaces.mjs", "kf-my-card").flag("my-card"));
 * kf.ui().contribute("models.detail.tabs",
 *         Surface.react("/m/api-test/ui/surfaces.mjs", "AuthorSeasonTab").key("author").labelKey("surface.author.label"));
 * }</pre>
 *
 * {@code entry} and {@code css} must be served by the module itself, under {@code /m/{slug}/}; with the Spring adapter,
 * files in {@code classpath:static/ui/} are served at {@code /ui/**} with the right MIME types.
 */
public final class Surface {

    private final String kind;
    private final String entry;
    private final String exportName;
    private final String tag;
    private String css;
    private Integer order;
    private String label;
    private String flag;
    private String key;
    private final Map<String, String> peer = new LinkedHashMap<>();
    private final String layer;
    private final String source;
    private final Map<String, Object> data;

    /** The largest {@code data} of a kind R contribution, serialized as JSON (sdk-protocol.md §7.12). */
    public static final int MAX_DATA_BYTES = 64 * 1024;

    private Surface(String kind, String entry, String exportName, String tag) {
        this.kind = kind;
        this.entry = Objects.requireNonNull(entry, "entry");
        this.exportName = exportName;
        this.tag = tag;
        this.layer = null;
        this.source = null;
        this.data = null;
    }

    private Surface(String kind, String layer, String source, Map<String, Object> data, boolean noCode) {
        this.kind = kind;
        this.entry = null;
        this.exportName = null;
        this.tag = null;
        this.layer = layer;
        this.source = source;
        this.data = data;
    }

    /**
     * A data layer for a kind B slot of another module ({@code m.{owner}.{key}}, protocol 2.0, §7.12): no code — the
     * layer kind (one of the slot's {@code layerKinds}) and the path of a {@code GET} route this module declares; the
     * host fetches it and hands the data to the owner.
     *
     * @throws IllegalArgumentException when the layer kind does not match {@code ^[a-z][a-z0-9-]{1,30}$} or the source
     *                                  is not a path {@code /m/...}
     */
    public static Surface layer(String layerKind, String sourcePath) {
        if (layerKind == null || !LAYER_KIND.matcher(layerKind).matches()) {
            throw new IllegalArgumentException("layer kind must match " + LAYER_KIND.pattern() + " but was " + layerKind);
        }
        if (sourcePath == null || !sourcePath.startsWith("/m/") || sourcePath.contains("..") || sourcePath.contains("//")) {
            throw new IllegalArgumentException("source must be the path /m/{slug}/... of a GET route of this module but was "
                    + sourcePath);
        }
        return new Surface("layer", layerKind, sourcePath, null, true);
    }

    /**
     * A catalog resource for a kind R slot of another module (§7.12): a JSON object of at most
     * {@value #MAX_DATA_BYTES} bytes, no code; the owner validates it against the slot's {@code dataSchema}.
     *
     * @throws IllegalArgumentException when {@code json} is not an object or is larger than {@value #MAX_DATA_BYTES} bytes
     */
    public static Surface data(Map<String, ?> json) {
        if (json == null) throw new IllegalArgumentException("data must be a JSON object");
        int size;
        try {
            size = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(json).length;
        } catch (Exception e) {
            throw new IllegalArgumentException("data must be a JSON object: " + e.getMessage(), e);
        }
        if (size > MAX_DATA_BYTES) {
            throw new IllegalArgumentException("data must be at most " + MAX_DATA_BYTES + " bytes but was " + size);
        }
        return new Surface("data", null, null, new LinkedHashMap<>(json), true);
    }

    /** The layer kind of a B slot (§7.12). */
    static final java.util.regex.Pattern LAYER_KIND = java.util.regex.Pattern.compile("^[a-z][a-z0-9-]{1,30}$");

    /** A React component: the named export {@code exportName} of the ES module {@code entry}, rendered in the host tree. */
    public static Surface react(String entry, String exportName) {
        return new Surface("react", entry, Objects.requireNonNull(exportName, "exportName"), null);
    }

    /** A Web Component: {@code entry} defines the custom element {@code tag} (prefix it, e.g. {@code kf-...}). */
    public static Surface wc(String entry, String tag) {
        return new Surface("wc", entry, null, Objects.requireNonNull(tag, "tag"));
    }

    /** Stylesheet injected once in the host page (React contributions style the light DOM). */
    public Surface css(String css) {
        this.css = css;
        return this;
    }

    /** Lower renders first (default 100). */
    public Surface order(int order) {
        this.order = order;
        return this;
    }

    /**
     * Translation KEY of the text shown discreetly next to the contribution (defaults to the module name). Under
     * manifest {@code schemaVersion} 2 it must be a key of {@code i18n.en} (checked locally by
     * {@link ManifestCheck}); the platform serves the text on the user's language.
     */
    public Surface labelKey(String labelKey) {
        this.label = labelKey;
        return this;
    }

    /**
     * Gates the contribution with one of the module's feature flags (declared in {@code kf-module-conf.json}): the host
     * only renders it while the flag is effectively on. An undeclared key is accepted with a warning and counts as off.
     */
    public Surface flag(String flagKey) {
        this.flag = Objects.requireNonNull(flagKey, "flagKey");
        return this;
    }

    /**
     * Stable id of the contribution within its surface ({@code ^[a-z][a-z0-9-]{0,40}$}); tabs use it in the URL
     * ({@code ?tab={module}.{key}}). Defaults to the tag without {@code kf-} or the export in kebab case.
     */
    public Surface key(String key) {
        this.key = Objects.requireNonNull(key, "key");
        return this;
    }

    /** Peer range checked by the host before rendering, e.g. {@code peer("react", "^19.2")}. */
    public Surface peer(String name, String range) {
        peer.put(Objects.requireNonNull(name), Objects.requireNonNull(range));
        return this;
    }

    /** One entry of the {@code surfaces} part of the registration body. */
    Map<String, Object> toJson(String slug) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("slug", slug);
        s.put("kind", kind);
        if (entry != null) s.put("entry", entry);
        if (layer != null) s.put("layer", layer);
        if (source != null) s.put("source", source);
        if (data != null) s.put("data", new LinkedHashMap<>(data));
        if (exportName != null) s.put("export", exportName);
        if (tag != null) s.put("tag", tag);
        if (css != null) s.put("css", css);
        if (order != null) s.put("order", order);
        if (label != null) s.put("label", label);
        if (flag != null) s.put("flag", flag);
        if (key != null) s.put("key", key);
        if (!peer.isEmpty()) s.put("peer", Map.copyOf(peer));
        return s;
    }
}
