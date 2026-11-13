package ro.kf.sdk;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The local check of {@code kf-module-conf.json} and of the registration against it, BEFORE the first request.
 *
 * <p>The same rules the platform applies at upload ({@code ModuleManifestValidator}) and at registration
 * ({@code ManifestCapabilityGuard}), minus everything that needs the platform's catalogs (the extensible
 * entities, the hook points, the surfaces, the global roles, the configured languages): those stay the platform's.
 * A violation found here is a <strong>permanent</strong> error: {@link KfModule#start()} sends nothing and does not
 * retry, so a module author sees the cause in the module's own log instead of a 422 in the platform's journal.
 *
 * <p>Messages are in English, start with the path of the element and name the rule, like the platform's.
 *
 * <h2>Declared in protocol 1.0 (operator decisions of 2026-10-19)</h2>
 * <ul>
 *   <li>{@code routes[].hookable}: a boolean, only on {@code POST}/{@code PUT}/{@code PATCH}; a
 *       non-boolean value or {@code true} on another method is refused, naming the route. The platform's strict
 *       check, the approval badge, the catalog and the dispatch are delivered by the platform.</li>
 *   <li>{@code audit}: a list of {@code {"access": "write" | "read-own"}}, each value at most once. Delivered
 *       by the platform.</li>
 * </ul>
 *
 * <p>The optional top-level {@code description} is checked like {@code name}: a key defined in
 * {@code i18n.en}; absent or {@code null} means none.
 *
 * <p>The optional top-level {@code rollbackFloor} is a semver 2.0 version at or below
 * {@code version} (semver precedence, not text order); absent or {@code null} means none. It is checked under every
 * {@code schemaVersion}: it does not depend on {@code i18n} or {@code capabilities}.
 *
 * <p>The capability {@code nav} (protocol 1.1) is checked as far as the manifest alone allows: the
 * surface, the keys, the label key, the url, the icon name, the order, the flag, and the right (never on
 * {@code customer}; under {@code api.v1.m.} only the right of one of the module's routes). The groups of a surface
 * and the platform's declared codes are the platform's catalogs: checked at upload.
 *
 * <p>The keys added to protocol 1.1 are checked as far as the manifest alone allows:
 * {@code settings} (closed objects; {@code BOOLEAN}, {@code NUMBER} or {@code ENUM} only -- "settings carry no data";
 * the default coherent with the type, {@code values} and {@code min..max}; unique keys; at most
 * {@value Manifest#MAX_SETTINGS}), {@code routes[].admin} ({@code tenant} | {@code platform}; no roles on
 * {@code platform}; never on a {@code hookable} route), {@code routes[].timeoutSeconds} (SDK 0.7.3: a whole number,
 * 1 to {@value Manifest#MAX_ROUTE_TIMEOUT_SECONDS}), {@code secrets} (closed objects, never a value; unique names;
 * the scope; {@code writeRight} = the right of a declared route, of an {@code admin: platform} route for a platform
 * secret), {@code provides} (closed objects; the port grammar; {@code path} = a declared GET route) and
 * {@code surfaces[].page} (its grammar). A port id, in {@code reads} and {@code provides}, matches
 * {@link Manifest#PORT_ID}. Which ports the platform publishes or consumes and the extensible entities
 * ({@code model_version}) stay the platform's catalogs: checked at upload.
 *
 * <p>The type of a surface (SDK 0.7.1) is checked against the release list this SDK pins
 * ({@link Surfaces#TYPES}, generated from {@code sdk/catalog/surfaces.public.json}), with the platform's rule and
 * messages ({@code ModuleManifestValidator}): {@code page} only on a surface of type {@code page}, always on
 * one, and each page of a surface once. A slug the pinned list does not know is not judged here (a platform newer than
 * this SDK may declare it): the platform decides at upload.
 *
 * <p>The keys protocol 1.2 adds (delivered by the platform) are checked with the texts the
 * platform uses at upload: {@code routes[].audience} ({@code "member"}; never with a non-empty {@code roles},
 * an {@code admin} or {@code hookable}), {@code routes[].portal} (a boolean, {@code true} only on
 * {@code audience: member}), the top-level {@code activation} ({@code "tenant"} | {@code "platform"}, under every
 * {@code schemaVersion}) and {@code storage} (at most one closed element, its content types and its byte cap). The hook
 * point {@code tenant.after.delete} is an after point like the others ({@link HookEvents#TENANT_AFTER_DELETE}).
 *
 * <p>Protocol 2.0: {@code schemaVersion} 3 is accepted, a strict superset of 2. The key it adds
 * here, {@code capabilities.slots} (sdk-protocol.md §7.12), is refused under 1–2
 * ({@code requires "schemaVersion": 3}) and checked as far as the manifest alone allows.
 *
 * <p>The order flow (protocol 2.0, sdk-protocol.md §16), with the platform's texts:
 * {@code capabilities.flowRules} and {@code capabilities.services} (schema 3 only; closed objects; the key, the declared
 * GET route, {@code timeoutSeconds} 1..60, the projection {@code order.flow-rule.v1}; the port
 * {@code order.services.v1}), the surface {@code orderflow.step} (its {@code key}, also at registration) and the
 * subscription to the events of the flow engine (never with {@code onFailure}). Only the grammar: the platform
 * delivers these names ({@code ORDER_SERVICES}; {@code FLOW_RULES}, {@code FLOW_EVENTS}, {@code ORDERFLOW_STEP}
 *), and whether a given platform delivers them is its gate -- the SDK never refuses a manifest for
 * it (as {@code storage} in 1.2).
 *
 * <p>Protocol 2.1 (sdk-protocol.md §17): {@code capabilities.flows} (schema 3 only; closed objects; the
 * key, the file directly under {@code flows/} ending in {@code .bpmn}, the name a key of {@code i18n.en}; at most
 * {@value Manifest#MAX_FLOWS}). {@link #checkPack} adds what only the pack shows: each file present and at most
 * {@value Manifest#MAX_FLOW_FILE_BYTES} bytes. The BPMN itself is the platform's (it compiles it at upload and approval);
 * whether a platform delivers {@code MODULE_FLOWS} is its gate, never the SDK's.
 */
public final class ManifestCheck {

    /** Translation key grammar (the platform's {@code ModuleManifestValidator.KEY}). */
    public static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_.-]{0,119}$");
    static final Pattern LANGUAGE_CODE = Pattern.compile("^[a-z]{2,3}([-_][A-Za-z0-9]{2,8})?$");
    static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,40}$");
    static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,40}$");
    static final Pattern NOTIFICATION_KEY = Pattern.compile("^[a-z][a-z0-9_-]{0,40}$");
    static final Pattern RIGHT_SEGMENT = Pattern.compile("^[a-z][a-z0-9-]{0,40}$");
    /** Semver 2.0 (the platform's {@code SemVer}): {@code major.minor.patch[-pre][+build]}, no leading zeros. */
    static final Pattern SEMVER = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
            + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
            + "(?:\\+[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*)?$");
    static final List<String> HTTP_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");
    static final List<String> FIELD_TYPES = List.of("STRING", "NUMBER", "DATE", "BOOLEAN", "LIST");
    static final List<String> SURFACE_KINDS = List.of("react", "wc");
    static final List<String> CHANNELS = List.of("in_app", "email");
    static final List<String> ON_FAILURE = List.of("allow", "block");
    static final Set<String> FORBIDDEN_ROLES = Set.of("TENANT_ADMIN", "SUPER_ADMIN");
    static final Set<String> DDL_KEYS = Set.of("migrations", "ddl", "sql", "schema");
    static final Set<String> DICTIONARY_KEYS = Set.of("dictionary", "translations", "writes");
    static final int MAX_ROLES_PER_ROUTE = 10;
    static final int MAX_ROLE_NAME_LENGTH = 100;
    static final int MAX_TEXT_LENGTH = 4000;
    /** The key of a {@code nav} entry and of its group (protocol 1.1). */
    static final Pattern NAV_KEY = Pattern.compile("^[a-z][a-z0-9-]{0,40}$");
    /** An icon name of the host's registry (Lucide, PascalCase). */
    static final Pattern NAV_ICON = Pattern.compile("^[A-Z][A-Za-z0-9]{0,60}$");
    /** The shape of a platform right code ({@code api.v1.customers.get}, {@code api.customer.v1.get}). */
    static final Pattern PLATFORM_RIGHT = Pattern.compile("^api(\\.[a-z0-9][a-z0-9-]*){2,10}$");
    static final int MAX_NAV_ORDER = 10000;
    static final int MAX_NAV_URL_LENGTH = 255;
    /** A port id (protocol 1.1). */
    static final Pattern PORT = Pattern.compile(Manifest.PORT_ID);
    /** The key of a setting and the name of a secret (protocol 1.1). */
    static final Pattern SETTING_KEY = Pattern.compile("^[a-z][a-z0-9-]{1,40}$");
    /** One prefix of {@code secrets[].external} (SDK 0.7.2). */
    static final Pattern SECRET_EXTERNAL_PREFIX = Pattern.compile(Manifest.SECRET_EXTERNAL_PREFIX);
    /** A value of an {@code ENUM} setting: a code, not a text. */
    static final Pattern ENUM_VALUE = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,40}$");
    /** The page of a {@code page} surface, under {@code /m/{slug}/}. */
    static final Pattern PAGE = Pattern.compile("^[a-z][a-z0-9-]{0,40}$");
    /** The surface type that takes a {@code page} (the platform's {@code ModuleManifestValidator.PAGE_SURFACE_TYPE}). */
    static final String PAGE_SURFACE_TYPE = "page";
    static final String SETTINGS_RULE = "settings carry no data: BOOLEAN, NUMBER or ENUM";
    /** One content type of {@code storage[].contentTypes} (protocol 1.2). */
    static final Pattern STORAGE_CONTENT_TYPE = Pattern.compile(Manifest.STORAGE_CONTENT_TYPE);

    /** The key of a slot (protocol 2.0, §7.12): the major as the last segment. */
    static final Pattern SLOT_KEY = Pattern.compile(Manifest.SLOT_KEY);
    /** A layer kind of a B slot (protocol 2.0). */
    static final Pattern SLOT_LAYER_KIND = Pattern.compile("^[a-z][a-z0-9-]{1,30}$");
    /** An action, an event or a command of a slot (protocol 2.0). */
    static final Pattern SLOT_NAME = Pattern.compile("^[a-z][a-z0-9-]{1,40}$");
    /** A public {@code ctx} member of an A slot (protocol 2.0). */
    static final Pattern SLOT_MEMBER = Pattern.compile("^[a-z][A-Za-z0-9]{0,40}$");
    /** What follows {@code m.{owner}.} in the {@code dataSchema} of an R slot: {@code {name}.v{N}}. */
    static final Pattern SLOT_SCHEMA_TAIL = Pattern.compile("^[a-z][a-z0-9-]{0,40}\\.v[1-9][0-9]*$");

    static final String FILE = "kf-module-conf.json";
    static final String HOOKABLE_RULE = "hookable only on POST, PUT or PATCH";

    private ManifestCheck() {
    }

    /**
     * What the check found.
     *
     * @param errors   blocking findings: nothing is sent
     * @param warnings non-blocking findings, logged
     */
    public record Result(List<String> errors, List<String> warnings) {

        public Result {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * The manifest and the files of its pack (protocol 2.1, sdk-protocol.md §17.2): the manifest's own rules
     * ({@link #validate(String)}), then each {@code capabilities.flows[].file} present in the pack and at most
     * {@value Manifest#MAX_FLOW_FILE_BYTES} bytes -- the platform's upload rule, so a module author sees it before the
     * upload ({@code check-pack}).
     *
     * @param packFiles the pack's files, by their path from the pack's root ({@code flows/x.bpmn}), with their size in
     *                  bytes; null = an empty pack
     */
    public static Result checkPack(String manifestJson, Map<String, Long> packFiles) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> root = parseOrReport(manifestJson, errors);
        if (root == null) return new Result(errors, warnings);
        validateTree(root, Set.of(), errors, warnings);
        packFlowFiles(root, packFiles == null ? Map.of() : packFiles, errors);
        return new Result(errors, warnings);
    }

    /** The manifest alone. The platform's configured languages are unknown to the SDK, so none is warned about. */
    public static Result validate(String manifestJson) {
        return validate(manifestJson, Set.of());
    }

    /**
     * The manifest alone.
     *
     * @param knownLanguages the languages the platform serves, when the caller knows them; empty = unknown (no
     *                       language warning). A language outside a non-empty set is a WARNING, never an error
     */
    public static Result validate(String manifestJson, Set<String> knownLanguages) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> root = parseOrReport(manifestJson, errors);
        if (root != null) validateTree(root, knownLanguages, errors, warnings);
        return new Result(errors, warnings);
    }

    /**
     * The manifest and the registration body ({@link KfModule#registrationBody()}): the manifest's own rules, then
     * registration &sube; manifest (the platform's rule without its catalog) and every label key with an
     * {@code en} text ({@code en} mandatory).
     */
    public static Result check(String manifestJson, Map<String, Object> registrationBody, Set<String> knownLanguages) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> root = parseOrReport(manifestJson, errors);
        if (root == null) return new Result(errors, warnings);
        validateTree(root, knownLanguages, errors, warnings);
        checkRegistration(Manifest.of(root), registrationBody == null ? Map.of() : registrationBody, errors);
        return new Result(errors, warnings);
    }

    private static Map<String, Object> parseOrReport(String json, List<String> errors) {
        try {
            return Manifest.tree(json);
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
            return null;
        }
    }

    // ---- the manifest ----------------------------------------------------------------------------------------

    private static void validateTree(Map<String, Object> root, Set<String> knownLanguages, List<String> errors,
                                     List<String> warnings) {
        Object rawVersion = root.get("schemaVersion");
        int schemaVersion;
        if (rawVersion == null) {
            schemaVersion = 1;
        } else if (rawVersion instanceof Integer || rawVersion instanceof Long) {
            schemaVersion = ((Number) rawVersion).intValue();
        } else {
            errors.add(FILE + ": \"schemaVersion\" must be an integer");
            return;
        }
        if (schemaVersion > SdkVersion.PROTOCOL_SCHEMA_VERSION || schemaVersion < 1) {
            errors.add(FILE + ": \"schemaVersion\" " + schemaVersion + " is not supported by kf-module-sdk "
                    + SdkVersion.SDK_VERSION + " (supported: 1.." + SdkVersion.PROTOCOL_SCHEMA_VERSION + ")");
            return;
        }
        rollbackFloor(root, errors);
        activation(root, errors);
        // Protocol 2.0: requires and featureFlags[].requiresModules, under every schemaVersion.
        requires(root, schemaVersion, errors);
        flagRequiresModules(root, schemaVersion, errors);
        if (schemaVersion < 2) {
            for (String block : List.of("i18n", "capabilities")) {
                if (root.containsKey(block)) {
                    errors.add(FILE + ": \"" + block + "\" requires \"schemaVersion\": 2 (this manifest is schemaVersion 1)");
                }
            }
            warnings.add(FILE + ": schemaVersion 1 declares no capabilities; only the platform catalog limits the "
                    + "registration (upgrade to \"schemaVersion\": 2)");
            return;
        }

        String slug = Manifest.text(root.get("slug"));
        if (slug == null || !SLUG.matcher(slug).matches()) {
            errors.add(FILE + ": \"slug\" must match " + SLUG.pattern() + " (got " + quoted(root.get("slug")) + ")");
        }
        if (!(root.get("platform") instanceof String p) || p.isBlank()) {
            errors.add(FILE + ": \"platform\" is required for \"schemaVersion\": 2 (e.g. \">=4.1\")");
        }
        forbiddenKeys(root, "", errors);
        Set<String> enKeys = i18n(root.get("i18n"), knownLanguages, errors, warnings);

        if (root.get("name") instanceof String name && !name.isBlank()) {
            requireKey(FILE + ": \"name\"", name, enKeys, errors);
        }
        // description: optional, the same shape as name; absent or null = none
        if (root.containsKey("description") && root.get("description") != null) {
            requireKey(FILE + ": \"description\"", Manifest.text(root.get("description")), enKeys, errors);
        }
        for (Map<String, Object> flag : Manifest.objects(root.get("featureFlags"))) {
            String key = Manifest.text(flag.get("key"));
            String at = FILE + ": featureFlags[\"" + key + "\"]";
            if (flag.get("label") instanceof String label && !label.isBlank()) requireKey(at + ".label", label, enKeys, errors);
            if (flag.containsKey("description") && flag.get("description") != null) {
                requireKey(at + ".description", Manifest.text(flag.get("description")), enKeys, errors);
            }
        }

        if (!root.containsKey("capabilities") || root.get("capabilities") == null) return;
        if (!(root.get("capabilities") instanceof Map<?, ?>)) {
            errors.add(FILE + ": \"capabilities\" must be an object");
            return;
        }
        Map<String, Object> caps = Manifest.map(root.get("capabilities"));
        for (Map.Entry<String, Object> entry : caps.entrySet()) {
            String key = entry.getKey();
            String rule = forbiddenRule(key);
            if (rule != null) {
                errors.add(FILE + ": capabilities." + key + " is not allowed: " + rule);
            } else if (!Manifest.CAPABILITY_KEYS.contains(key)) {
                errors.add(FILE + ": capabilities." + key + " is not a capability this SDK knows (known: "
                        + String.join(", ", Manifest.CAPABILITY_KEYS) + ")");
            } else if (entry.getValue() != null && !(entry.getValue() instanceof List<?>)) {
                errors.add(FILE + ": capabilities." + key + " must be an array");
            }
        }
        fields(list(caps.get("fields")), enKeys, errors);
        hooks(root, schemaVersion, list(caps.get("hooks")), errors);
        routes(slug, list(caps.get("routes")), errors);
        surfaces(slug, root, schemaVersion, list(caps.get("surfaces")), enKeys, errors);
        notifications(list(caps.get("notifications")), enKeys, errors);
        reads(list(caps.get("reads")), errors);
        audit(list(caps.get("audit")), errors);
        nav(slug, root, list(caps.get("nav")), enKeys, errors);
        settings(list(caps.get("settings")), enKeys, errors);
        secrets(root, list(caps.get("secrets")), errors);
        provides(root, list(caps.get("provides")), errors);
        storage(caps.get("storage"), errors);
        if (caps.get("slots") != null) {
            if (schemaVersion < 3) {
                errors.add(FILE + ": capabilities.slots requires \"schemaVersion\": 3 (this manifest is schemaVersion "
                        + schemaVersion + ")");
            } else {
                slots(slug, list(caps.get("slots")), enKeys, errors);
            }
        }
        // Protocol 2.0 (the platform's rules and messages): between modules, schema 3 only.
        boolean betweenModules = false;
        for (String key : List.of("peerReads", "commands", "events", "peers")) {
            if (caps.get(key) == null) continue;
            if (schemaVersion < 3) {
                errors.add(FILE + ": capabilities." + key + " requires \"schemaVersion\": 3 (this manifest is schemaVersion "
                        + schemaVersion + ")");
            } else {
                betweenModules = true;
            }
        }
        if (betweenModules) {
            peerReads(slug, root, list(caps.get("peerReads")), errors);
            commands(root, list(caps.get("commands")), errors);
            events(list(caps.get("events")), errors);
            peers(slug, root, list(caps.get("peers")), errors);
        }
        // Protocol 2.0 (§16): the keys of the order flow, schema 3 only, the platform's rules and messages. Only the grammar: whether this platform delivers them is the platform's gate, never the SDK's.
        if (caps.get("flowRules") != null) {
            if (schemaVersion < 3) {
                errors.add(FILE + ": capabilities.flowRules requires \"schemaVersion\": 3 (this manifest is schemaVersion "
                        + schemaVersion + ")");
            } else {
                flowRules(root, list(caps.get("flowRules")), errors);
            }
        }
        if (caps.get("services") != null) {
            if (schemaVersion < 3) {
                errors.add(FILE + ": capabilities.services requires \"schemaVersion\": 3 (this manifest is schemaVersion "
                        + schemaVersion + ")");
            } else {
                services(list(caps.get("services")), errors);
            }
        }
        // Protocol 2.1 (§17): the module flows, schema 3 only, the platform's rules and messages.
        if (caps.get("flows") != null) {
            if (schemaVersion < 3) {
                errors.add(FILE + ": capabilities.flows requires \"schemaVersion\": 3 (this manifest is schemaVersion "
                        + schemaVersion + ")");
            } else {
                flows(list(caps.get("flows")), enKeys, errors);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The module flows (protocol 2.1, sdk-protocol.md §17; the platform's ModuleManifestValidator)
    // ---------------------------------------------------------------------------------------------------------------

    /** The key of a module flow (§17.1). */
    static final Pattern FLOW_KEY = Pattern.compile(Manifest.FLOW_KEY);
    /** The file of a module flow (§17.1). */
    static final Pattern FLOW_FILE = Pattern.compile(Manifest.FLOW_FILE);

    /**
     * {@code capabilities.flows[]} (§17.1): closed objects; {@code key} unique, {@value Manifest#FLOW_KEY}; {@code file}
     * unique, without {@code ..}, directly under {@code flows/} and ending in {@code .bpmn}; {@code name} a key of
     * {@code i18n.en}; at most {@value Manifest#MAX_FLOWS}.
     */
    private static void flows(List<Object> list, Set<String> enKeys, List<String> errors) {
        if (list.size() > Manifest.MAX_FLOWS) {
            errors.add(FILE + ": capabilities.flows holds at most " + Manifest.MAX_FLOWS + " flows (got " + list.size() + ")");
        }
        Set<String> keys = new HashSet<>();
        Set<String> files = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String at = FILE + ": capabilities.flows[" + i + "]";
            Map<String, Object> f = closedObject(at, list.get(i), Manifest.FLOW_KEYS, "a module flow", errors);
            if (f == null) continue;
            String key = Manifest.text(f.get("key"));
            if (key == null || !FLOW_KEY.matcher(key).matches()) {
                errors.add(at + ".key " + quoted(f.get("key")) + " must match " + FLOW_KEY.pattern());
            } else if (!keys.add(key)) {
                errors.add(at + ".key \"" + key + "\" is declared twice");
            }
            String file = Manifest.text(f.get("file"));
            if (file != null && file.contains("..")) {
                errors.add(at + ".file \"" + file + "\" must not contain \"..\"");
            } else if (file == null || !FLOW_FILE.matcher(file).matches()) {
                errors.add(at + ".file " + quoted(f.get("file")) + " must be a .bpmn file directly under "
                        + Manifest.FLOWS_DIRECTORY + " (" + FLOW_FILE.pattern() + ")");
            } else if (!files.add(file)) {
                errors.add(at + ".file \"" + file + "\" is declared twice");
            }
            requireKey(at + ".name", Manifest.text(f.get("name")), enKeys, errors);
        }
    }

    /** {@link #checkPack}: each well-formed {@code flows[].file} present in the pack and not over the cap (§17.2). */
    private static void packFlowFiles(Map<String, Object> root, Map<String, Long> packFiles, List<String> errors) {
        if (!(root.get("capabilities") instanceof Map<?, ?> caps)) return;
        List<Object> list = list(Manifest.map(caps).get("flows"));
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> raw)) continue;
            String file = Manifest.text(Manifest.map(raw).get("file"));
            if (file == null || file.contains("..") || !FLOW_FILE.matcher(file).matches()) continue; // reported above
            String at = FILE + ": capabilities.flows[" + i + "].file \"" + file + "\"";
            Long size = packFiles.get(file);
            if (size == null) {
                errors.add(at + " is not in the pack");
            } else if (size > Manifest.MAX_FLOW_FILE_BYTES) {
                errors.add(at + " is larger than " + Manifest.MAX_FLOW_FILE_BYTES + " bytes (got " + size + ")");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The order flow (protocol 2.0, sdk-protocol.md §16; the platform's ModuleManifestValidator)
    // ---------------------------------------------------------------------------------------------------------------

    /** The four events of the flow engine (§16.3): subscribed without {@code onFailure}. */
    static final List<String> FLOW_EVENT_POINTS = FlowEvents.TYPES;

    /** The key of a step rendered by {@code orderflow.step} (§16.5) and of a flow rule (§16.4). */
    static final Pattern STEP_KEY = Pattern.compile("^[a-z][a-z0-9-]{1,40}$");

    /**
     * {@code capabilities.flowRules[]} (§16.4): closed objects; {@code key} a name, unique; {@code path} a declared GET
     * route; {@code timeoutSeconds} 1..60 when present; {@code projection} = {@code order.flow-rule.v1}.
     */
    /** The platform's refusal of a declared path with a {@code {param}} segment. */
    private static void literalPath(String at, String path, List<String> errors) {
        errors.add(at + ".path " + quoted(path) + " must be a route with literal segments only (no {param} segment)");
    }

    private static void flowRules(Map<String, Object> root, List<Object> list, List<String> errors) {
        Set<String> getPaths = declaredPaths(root, "GET");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String at = FILE + ": capabilities.flowRules[" + i + "]";
            Map<String, Object> r = closedObject(at, list.get(i), Manifest.FLOW_RULE_KEYS, "a flow rule", errors);
            if (r == null) continue;
            String key = Manifest.text(r.get("key"));
            if (key == null || !PEER_NAME.matcher(key).matches()) {
                errors.add(at + ".key " + quoted(r.get("key")) + " must match " + PEER_NAME.pattern());
            } else if (!seen.add(key)) {
                errors.add(at + ".key \"" + key + "\" is declared twice");
            }
            String path = Manifest.text(r.get("path"));
            if (path == null || !getPaths.contains(path)) {
                errors.add(at + ".path " + quoted(r.get("path")) + " is not a GET route this module declares "
                        + "(capabilities.routes[] with method GET)");
            } else if (path.contains("{")) {
                literalPath(at, path, errors); //
            }
            Object timeout = r.get("timeoutSeconds");
            if (timeout != null) {
                Integer seconds = Manifest.wholeSeconds(timeout);
                if (seconds == null || seconds < 1 || seconds > Manifest.MAX_FLOW_RULE_TIMEOUT_SECONDS) {
                    errors.add(at + ".timeoutSeconds " + quoted(timeout) + " must be a whole number from 1 to "
                            + Manifest.MAX_FLOW_RULE_TIMEOUT_SECONDS);
                }
            }
            if (!Manifest.FLOW_RULE_PROJECTION.equals(r.get("projection"))) {
                errors.add(at + ".projection " + quoted(r.get("projection")) + " must be \""
                        + Manifest.FLOW_RULE_PROJECTION + "\"");
            }
        }
    }

    /** {@code capabilities.services[]} (§16.6): closed objects; {@code port} = {@code order.services.v1}, once. */
    private static void services(List<Object> list, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String at = FILE + ": capabilities.services[" + i + "]";
            Map<String, Object> sv = closedObject(at, list.get(i), Manifest.SERVICE_KEYS, "a service", errors);
            if (sv == null) continue;
            String port = Manifest.text(sv.get("port"));
            if (!Manifest.SERVICES_PORT.equals(port)) {
                errors.add(at + ".port " + quoted(sv.get("port")) + " must be \"" + Manifest.SERVICES_PORT + "\"");
            } else if (!seen.add(port)) {
                errors.add(at + ".port \"" + port + "\" is declared twice");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Between modules (protocol 2.0, sdk-protocol.md §15.9 / §6.3; the platform's ModuleManifestValidator)
    // ---------------------------------------------------------------------------------------------------------------

    /** The name of a command or an event (and of a peer's command): {@code ^[a-z][a-z0-9-]{1,40}$}. */
    static final Pattern PEER_NAME = Pattern.compile("^[a-z][a-z0-9-]{1,40}$");
    private static final String PEER_PORT_TAIL = "[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*\\.v[1-9][0-9]*";

    /** The grammar of a port offered by {@code owner}: {@code m.{owner}.{name}.v{N}}. */
    static Pattern peerPort(String owner) {
        return Pattern.compile("^m\\." + Pattern.quote(owner == null ? "" : owner) + "\\." + PEER_PORT_TAIL + "$");
    }

    private static Map<String, Object> closedObject(String at, Object raw, List<String> keys, String what,
                                                    List<String> errors) {
        if (!(raw instanceof Map<?, ?>)) {
            errors.add(at + " must be an object");
            return null;
        }
        Map<String, Object> object = Manifest.map(raw);
        for (String key : object.keySet()) {
            if (!keys.contains(key)) {
                errors.add(at + ": \"" + key + "\" is not a key of " + what + " (known: " + String.join(", ", keys) + ")");
            }
        }
        return object;
    }

    private static void visibility(String at, Map<String, Object> object, List<String> errors) {
        if (!(object.get("visibility") instanceof String v) || !Manifest.PEER_VISIBILITIES.contains(v)) {
            errors.add(at + ".visibility " + quoted(object.get("visibility")) + " must be one of "
                    + String.join(", ", Manifest.PEER_VISIBILITIES) + " (required, no default)");
        }
    }

    /** The paths of the routes this manifest declares with {@code method}. */
    private static Set<String> declaredPaths(Map<String, Object> root, String method) {
        Set<String> paths = new HashSet<>();
        Object caps = root.get("capabilities");
        if (!(caps instanceof Map<?, ?>)) return paths;
        for (Map<String, Object> route : Manifest.objects(Manifest.map(caps).get("routes"))) {
            if (method.equals(Manifest.text(route.get("method"))) && Manifest.text(route.get("path")) != null) {
                paths.add(Manifest.text(route.get("path")));
            }
        }
        return paths;
    }

    private static void peerReads(String slug, Map<String, Object> root, List<Object> list, List<String> errors) {
        Pattern own = peerPort(slug);
        Set<String> getPaths = declaredPaths(root, "GET");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String at = FILE + ": capabilities.peerReads[" + i + "]";
            Map<String, Object> p = closedObject(at, list.get(i), Manifest.PEER_READ_KEYS, "a peer read", errors);
            if (p == null) continue;
            String port = Manifest.text(p.get("port"));
            if (port == null || !own.matcher(port).matches()) {
                errors.add(at + ".port " + quoted(p.get("port")) + " must be m." + slug + ".{name}.v{N} (must match "
                        + own.pattern() + ")");
            } else if (!seen.add(port)) {
                errors.add(at + ".port \"" + port + "\" is declared twice");
            }
            String path = Manifest.text(p.get("path"));
            if (path == null || !getPaths.contains(path)) {
                errors.add(at + ".path " + quoted(p.get("path")) + " is not a GET route this module declares "
                        + "(capabilities.routes[] with method GET)");
            } else if (path.contains("{")) {
                literalPath(at, path, errors); //
            }
            visibility(at, p, errors);
        }
    }

    private static void commands(Map<String, Object> root, List<Object> list, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String at = FILE + ": capabilities.commands[" + i + "]";
            Map<String, Object> c = closedObject(at, list.get(i), Manifest.COMMAND_KEYS, "a command", errors);
            if (c == null) continue;
            String name = Manifest.text(c.get("name"));
            if (name == null || !PEER_NAME.matcher(name).matches()) {
                errors.add(at + ".name " + quoted(c.get("name")) + " must match " + PEER_NAME.pattern());
            } else if (!seen.add(name)) {
                errors.add(at + ".name \"" + name + "\" is declared twice");
            }
            String method = Manifest.text(c.get("method"));
            if (method == null || !Manifest.COMMAND_METHODS.contains(method)) {
                errors.add(at + ".method " + quoted(c.get("method")) + " must be one of "
                        + String.join(", ", Manifest.COMMAND_METHODS));
            } else {
                String path = Manifest.text(c.get("path"));
                if (path == null || !declaredPaths(root, method).contains(path)) {
                    errors.add(at + ".path " + quoted(c.get("path")) + " is not a " + method + " route this module "
                            + "declares (capabilities.routes[] with method " + method + ")");
                } else if (path.contains("{")) {
                    literalPath(at, path, errors); //
                }
            }
            visibility(at, c, errors);
        }
    }

    private static void events(List<Object> list, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String at = FILE + ": capabilities.events[" + i + "]";
            Map<String, Object> e = closedObject(at, list.get(i), Manifest.EVENT_KEYS, "an event", errors);
            if (e == null) continue;
            String name = Manifest.text(e.get("name"));
            if (name == null || !PEER_NAME.matcher(name).matches()) {
                errors.add(at + ".name " + quoted(e.get("name")) + " must match " + PEER_NAME.pattern());
            } else if (!seen.add(name)) {
                errors.add(at + ".name \"" + name + "\" is declared twice");
            }
            visibility(at, e, errors);
        }
    }

    private static void peers(String slug, Map<String, Object> root, List<Object> list, List<String> errors) {
        Set<String> required = new HashSet<>();
        for (Map<String, Object> entry : Manifest.objects(root.get("requires"))) {
            if (entry.get("slug") instanceof String s) required.add(s);
        }
        Set<String> owners = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String at = FILE + ": capabilities.peers[" + i + "]";
            Map<String, Object> use = closedObject(at, list.get(i), Manifest.PEER_USE_KEYS, "a use of another module",
                    errors);
            if (use == null) continue;
            String owner = Manifest.text(use.get("owner"));
            if (owner == null || !SLUG.matcher(owner).matches()) {
                errors.add(at + ".owner " + quoted(use.get("owner")) + " is not a module slug (must match "
                        + SLUG.pattern() + ")");
                owner = null;
            } else if (owner.equals(slug)) {
                errors.add(at + ".owner \"" + owner + "\" is this module itself");
            } else if (!owners.add(owner)) {
                errors.add(at + ".owner \"" + owner + "\" is declared twice");
            } else if (!required.contains(owner)) {
                errors.add(at + ".owner \"" + owner + "\" is not in requires (declare it there, mandatory or optional)");
            }
            int reads = peerNames(at + ".reads", use.get("reads"), owner == null ? null : peerPort(owner), errors);
            int commands = peerNames(at + ".commands", use.get("commands"), PEER_NAME, errors);
            if (reads == 0 && commands == 0) {
                errors.add(at + " must name at least one of reads, commands");
            }
        }
    }

    private static int peerNames(String at, Object names, Pattern grammar, List<String> errors) {
        if (names == null) return 0;
        if (!(names instanceof List<?> items)) {
            errors.add(at + " must be an array");
            return 0;
        }
        Set<String> seen = new HashSet<>();
        int valid = 0;
        for (int i = 0; i < items.size(); i++) {
            Object name = items.get(i);
            if (!(name instanceof String value) || (grammar != null && !grammar.matcher(value).matches())) {
                errors.add(at + "[" + i + "] " + quoted(name)
                        + (grammar == null ? " must be a string" : " must match " + grammar.pattern()));
            } else if (!seen.add(value)) {
                errors.add(at + "[" + i + "] \"" + value + "\" is named twice");
            } else {
                valid++;
            }
        }
        return valid;
    }

    /**
     * {@code activation} (protocol 1.2): {@code "tenant"} or {@code "platform"}; absent or
     * {@code null} = {@code tenant}. Checked under every {@code schemaVersion}, like {@code rollbackFloor}. The
     * platform's text ({@code ModuleManifestValidator}).
     */
    private static void activation(Map<String, Object> root, List<String> errors) {
        Object value = root.get("activation");
        if (value == null || Manifest.Activation.of(value) != null) return;
        errors.add(FILE + ": activation must be \"tenant\" or \"platform\" (got " + quoted(value) + ")");
    }

    /**
     * {@code capabilities.storage} (protocol 1.2): at most
     * {@value Manifest#MAX_STORAGE_ELEMENTS} element, a closed object {@code {contentTypes, maxBytes}};
     * {@code contentTypes} 1 to {@value Manifest#MAX_STORAGE_CONTENT_TYPES} distinct {@code type/subtype} without a
     * wildcard ({@link Manifest#STORAGE_CONTENT_TYPE}); {@code maxBytes} a whole number from 1 to
     * {@value Manifest#MAX_STORAGE_BYTES}. The platform's texts ({@code ModuleManifestValidator}).
     */
    private static void storage(Object raw, List<String> errors) {
        if (!(raw instanceof List<?> storage)) return; // absent = []; a non-array is reported with the capabilities
        if (storage.size() > Manifest.MAX_STORAGE_ELEMENTS) {
            errors.add(FILE + ": capabilities.storage holds at most one element");
        }
        for (int i = 0; i < storage.size(); i++) {
            String at = FILE + ": capabilities.storage[" + i + "]";
            if (!(storage.get(i) instanceof Map<?, ?>)) {
                errors.add(at + " must be an object");
                continue;
            }
            Map<String, Object> s = Manifest.map(storage.get(i));
            for (String key : s.keySet()) {
                if (!Manifest.STORAGE_KEYS.contains(key)) {
                    errors.add(at + ": \"" + key + "\" is not a key of a storage element (known: "
                            + String.join(", ", Manifest.STORAGE_KEYS) + ")");
                }
            }
            if (!contentTypes(s.get("contentTypes"))) {
                errors.add(at + ".contentTypes must be 1 to " + Manifest.MAX_STORAGE_CONTENT_TYPES
                        + " distinct type/subtype values, without wildcards");
            }
            Integer bytes = Manifest.wholeSeconds(s.get("maxBytes"));
            if (bytes == null || bytes < 1 || bytes > Manifest.MAX_STORAGE_BYTES) {
                errors.add(at + ".maxBytes must be a whole number of bytes between 1 and " + Manifest.MAX_STORAGE_BYTES
                        + " (got " + quoted(s.get("maxBytes")) + ")");
            }
        }
    }

    private static boolean contentTypes(Object raw) {
        if (!(raw instanceof List<?> types) || types.isEmpty() || types.size() > Manifest.MAX_STORAGE_CONTENT_TYPES) {
            return false;
        }
        Set<Object> seen = new HashSet<>();
        for (Object type : types) {
            if (!(type instanceof String t) || !STORAGE_CONTENT_TYPE.matcher(t).matches() || !seen.add(t)) return false;
        }
        return true;
    }

    /**
     * {@code rollbackFloor}: optional; a semver at or below {@code version}. When
     * {@code version} itself is not semver, only the floor's own shape is checked (the version is the platform's
     * check at upload).
     */
    private static void rollbackFloor(Map<String, Object> root, List<String> errors) {
        if (!root.containsKey("rollbackFloor") || root.get("rollbackFloor") == null) return;
        Object raw = root.get("rollbackFloor");
        if (!(raw instanceof String floor) || !SEMVER.matcher(floor).matches()) {
            errors.add(FILE + ": \"rollbackFloor\" must be a semver 2.0 version, e.g. \"1.1.0\" (got " + quoted(raw) + ")");
            return;
        }
        String version = Manifest.text(root.get("version"));
        if (version != null && SEMVER.matcher(version).matches() && compareSemver(floor, version) > 0) {
            errors.add(FILE + ": \"rollbackFloor\" \"" + floor + "\" is above \"version\" \"" + version
                    + "\": the floor is the oldest version that runs on this version's data, so at most the version itself");
        }
    }

    /**
     * Semver 2.0 precedence of two versions that match {@link #SEMVER} (the platform's {@code SemVer.compareTo}): the
     * numeric core first, then a pre-release below its release, then the pre-release identifiers one by one (numeric
     * below alphanumeric, numeric by value, alphanumeric by text, a shorter list below a longer one). The build part is
     * ignored. Numbers are compared by length, then by text: semver forbids leading zeros, so no size limit applies.
     */
    static int compareSemver(String a, String b) {
        Matcher ma = SEMVER.matcher(a);
        Matcher mb = SEMVER.matcher(b);
        if (!ma.matches() || !mb.matches()) throw new IllegalArgumentException("not semver: " + a + ", " + b);
        for (int i = 1; i <= 3; i++) {
            int c = compareNumeric(ma.group(i), mb.group(i));
            if (c != 0) return c;
        }
        String preA = ma.group(4);
        String preB = mb.group(4);
        if (preA == null || preB == null) {
            if (preA == null && preB == null) return 0;
            return preA == null ? 1 : -1;
        }
        String[] idsA = preA.split("\\.");
        String[] idsB = preB.split("\\.");
        for (int i = 0; i < Math.min(idsA.length, idsB.length); i++) {
            boolean numA = idsA[i].matches("\\d+");
            boolean numB = idsB[i].matches("\\d+");
            int c;
            if (numA && numB) c = compareNumeric(idsA[i], idsB[i]);
            else if (numA) c = -1;
            else if (numB) c = 1;
            else c = idsA[i].compareTo(idsB[i]);
            if (c != 0) return c;
        }
        return Integer.compare(idsA.length, idsB.length);
    }

    private static int compareNumeric(String a, String b) {
        return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : Integer.signum(a.compareTo(b));
    }

    private static void forbiddenKeys(Map<String, Object> object, String prefix, List<String> errors) {
        for (String key : object.keySet()) {
            String rule = forbiddenRule(key);
            if (rule != null) errors.add(FILE + ": \"" + prefix + key + "\" is not allowed: " + rule);
        }
    }

    private static String forbiddenRule(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (DDL_KEYS.contains(lower)) {
            return "a module cannot run DDL outside kf_modules; extension tables are declared in capabilities.fields "
                    + "and created by the platform";
        }
        if (DICTIONARY_KEYS.contains(lower)) {
            return "a module cannot write the platform dictionary; texts are declared in \"i18n\" and the platform "
                    + "writes them at install";
        }
        return null;
    }

    /** The {@code en} keys with a non-blank text. */
    private static Set<String> i18n(Object raw, Set<String> knownLanguages, List<String> errors, List<String> warnings) {
        Set<String> enKeys = new HashSet<>();
        if (raw == null) {
            errors.add(FILE + ": \"i18n\" is required for \"schemaVersion\": 2 and must contain \"en\"");
            return enKeys;
        }
        if (!(raw instanceof Map<?, ?>)) {
            errors.add(FILE + ": \"i18n\" must be an object of language -> (key -> text)");
            return enKeys;
        }
        Map<String, Object> languages = Manifest.map(raw);
        if (!languages.containsKey("en")) {
            errors.add(FILE + ": \"i18n\" must contain \"en\": every text needs an English version");
        }
        Set<String> unknown = new TreeSet<>();
        for (Map.Entry<String, Object> language : languages.entrySet()) {
            String lang = language.getKey();
            String at = FILE + ": i18n." + lang;
            if (!LANGUAGE_CODE.matcher(lang).matches()) {
                errors.add(at + " is not a language code (expected e.g. \"en\", \"ro\", \"pt-BR\")");
                continue;
            }
            if (!(language.getValue() instanceof Map<?, ?>)) {
                errors.add(at + " must be an object of key -> text");
                continue;
            }
            if (!knownLanguages.isEmpty() && !knownLanguages.contains(lang)) unknown.add(lang);
            for (Map.Entry<String, Object> entry : Manifest.map(language.getValue()).entrySet()) {
                String key = entry.getKey();
                if (!KEY.matcher(key).matches()) {
                    errors.add(at + "." + key + ": a key must match " + KEY.pattern());
                } else if (!(entry.getValue() instanceof String text)) {
                    errors.add(at + "." + key + " must be a string");
                } else if (text.isBlank()) {
                    errors.add(at + "." + key + " must not be blank");
                } else if (text.length() > MAX_TEXT_LENGTH) {
                    errors.add(at + "." + key + " exceeds " + MAX_TEXT_LENGTH + " characters");
                } else if ("en".equals(lang)) {
                    enKeys.add(key);
                }
            }
        }
        for (Map.Entry<String, Object> language : languages.entrySet()) {
            if ("en".equals(language.getKey()) || !LANGUAGE_CODE.matcher(language.getKey()).matches()) continue;
            for (String key : Manifest.map(language.getValue()).keySet()) {
                if (KEY.matcher(key).matches() && !enKeys.contains(key)) {
                    errors.add(FILE + ": i18n." + language.getKey() + "." + key
                            + " has no \"en\" text; every key must be defined in \"en\"");
                }
            }
        }
        if (!unknown.isEmpty()) {
            warnings.add(FILE + ": i18n declares " + String.join(", ", unknown) + ", not served by this platform "
                    + "(served: " + String.join(", ", new TreeSet<>(knownLanguages)) + "); those texts stay in the manifest");
        }
        return enKeys;
    }

    private static void requireKey(String at, String key, Set<String> enKeys, List<String> errors) {
        if (key == null || key.isBlank()) {
            errors.add(at + " is required: a translation key defined in i18n.en");
        } else if (!KEY.matcher(key).matches()) {
            errors.add(at + " \"" + key + "\" is not a translation key (must match " + KEY.pattern()
                    + "); in schemaVersion 2 texts live in \"i18n\"");
        } else if (!enKeys.contains(key)) {
            errors.add(at + " \"" + key + "\" is not defined in i18n.en");
        }
    }

    private static void fields(List<Object> fields, Set<String> enKeys, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            String at = FILE + ": capabilities.fields[" + i + "]";
            if (!(fields.get(i) instanceof Map<?, ?>)) {
                errors.add(at + " must be an object");
                continue;
            }
            Map<String, Object> f = Manifest.map(fields.get(i));
            String entity = Manifest.text(f.get("entity"));
            String name = Manifest.text(f.get("name"));
            if (entity == null || entity.isBlank()) {
                errors.add(at + ".entity is required (model, order, customer, ...: an extensible entity of the platform)");
            }
            if (name == null || !NAME.matcher(name).matches()) {
                errors.add(at + ".name must match " + NAME.pattern() + " (got " + quoted(f.get("name")) + ")");
            } else if (entity != null && !seen.add(entity + "." + name)) {
                errors.add(at + ": field \"" + entity + "." + name + "\" is declared twice");
            }
            String table = Manifest.text(f.get("table"));
            if (table != null && table.contains(".")) {
                errors.add(at + ".table " + quoted(table) + " is not allowed: a module cannot run DDL outside kf_modules");
            } else if (table == null || !NAME.matcher(table).matches()) {
                errors.add(at + ".table must match " + NAME.pattern() + " (got " + quoted(f.get("table")) + ")");
            }
            if (!FIELD_TYPES.contains(Manifest.text(f.get("type")))) {
                errors.add(at + ".type must be one of " + String.join(", ", FIELD_TYPES) + " (got " + quoted(f.get("type")) + ")");
            }
            requireKey(at + ".label", Manifest.text(f.get("label")), enKeys, errors);
            // editable, when present, is a JSON boolean (null is refused too)
            if (f.containsKey("editable") && !(f.get("editable") instanceof Boolean)) {
                errors.add(at + ".editable must be a boolean (got " + quoted(f.get("editable")) + ")");
            }
        }
    }

    private static void hooks(Map<String, Object> root, int schemaVersion, List<Object> hooks, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < hooks.size(); i++) {
            String at = FILE + ": capabilities.hooks[" + i + "]";
            if (!(hooks.get(i) instanceof Map<?, ?>)) {
                errors.add(at + " must be an object");
                continue;
            }
            Map<String, Object> h = Manifest.map(hooks.get(i));
            String point = Manifest.text(h.get("point"));
            if (point != null && point.startsWith(HookEvents.MODULE_EVENT_PREFIX)) {
                moduleEventHook(root, schemaVersion, at, point, h, seen, errors); //
                continue;
            }
            if (point == null || point.isBlank()) {
                errors.add(at + ".point is required (a hook point id, e.g. model.before.update)");
            } else if (!seen.add(point)) {
                errors.add(at + ".point \"" + point + "\" is declared twice");
            }
            if (h.get("onFailure") != null && point != null && FLOW_EVENT_POINTS.contains(point)) {
                // protocol 2.0 (§16.3, the platform's text): an event of the flow engine blocks nothing
                errors.add(at + ".onFailure is not allowed on the flow event \"" + point + "\": it runs after the step "
                        + "and cannot block it");
            } else if (h.get("onFailure") != null) {
                String onFailure = Manifest.text(h.get("onFailure"));
                if (!ON_FAILURE.contains(onFailure)) {
                    errors.add(at + ".onFailure must be one of allow, block (got " + quoted(h.get("onFailure")) + ")");
                } else if (point != null && !point.contains(".before.")) {
                    errors.add(at + ".onFailure only on before points: \"" + point + "\" runs after the mutation and "
                            + "cannot block it");
                }
            }
        }
    }

    /**
     * {@code capabilities.hooks[i]} on {@code m.{owner}.after.{name}} (the platform's
     * {@code validateModuleEventHook}): schema 3, the grammar, the owner in {@code requires} (mandatory or optional),
     * no {@code onFailure}; the point declared once.
     */
    private static void moduleEventHook(Map<String, Object> root, int schemaVersion, String at, String point,
                                        Map<String, Object> hook, Set<String> seen, List<String> errors) {
        if (schemaVersion < 3) {
            errors.add(at + ".point " + quoted(point) + ": an event of a module requires \"schemaVersion\": 3");
            return;
        }
        java.util.regex.Matcher parsed = HookEvents.MODULE_EVENT_POINT.matcher(point);
        if (!parsed.matches()) {
            errors.add(at + ".point " + quoted(point) + " is not an event of a module (m.{owner}.after.{name})");
            return;
        }
        String owner = parsed.group(1);
        if (!seen.add(point)) errors.add(at + ".point \"" + point + "\" is declared twice");
        boolean required = root.get("requires") instanceof List<?> requires && requires.stream()
                .anyMatch(r -> r instanceof Map<?, ?> && owner.equals(Manifest.text(Manifest.map(r).get("slug"))));
        if (!required) {
            errors.add(at + ".point \"" + point + "\" is an event of " + owner + ", which this manifest does not "
                    + "require (requires, mandatory or optional)");
        }
        if (hook.get("onFailure") != null) {
            errors.add(at + ".onFailure is not allowed on an event of a module: it runs after the fact");
        }
    }

    private static void routes(String slug, List<Object> routes, List<String> errors) {
        Set<String> seen = new HashSet<>();
        String prefix = "/m/" + slug + "/";
        String rightPrefix = "api.v1.m." + slug + ".";
        for (int i = 0; i < routes.size(); i++) {
            String at = FILE + ": capabilities.routes[" + i + "]";
            if (!(routes.get(i) instanceof Map<?, ?>)) {
                errors.add(at + " must be an object");
                continue;
            }
            Map<String, Object> r = Manifest.map(routes.get(i));
            String method = Manifest.text(r.get("method"));
            String path = Manifest.text(r.get("path"));
            String route = at + " (" + method + " " + path + ")";
            if (method == null || !HTTP_METHODS.contains(method)) {
                errors.add(route + ".method must be one of " + String.join(", ", HTTP_METHODS));
            }
            if (path == null || !path.startsWith(prefix) || path.length() == prefix.length() || path.endsWith("/")
                    || path.contains("..") || path.contains("//") || path.contains("?") || path.contains("#")
                    || path.contains("*") || path.contains("%")) {
                errors.add(route + ".path must be under " + prefix + " without \"..\", \"//\", \"%\", \"*\", a query, "
                        + "a fragment or a trailing \"/\"");
            }
            String right = Manifest.text(r.get("right"));
            if (right == null || !right.startsWith(rightPrefix)) {
                errors.add(route + ".right must be in " + rightPrefix + "* (got " + quoted(r.get("right")) + ")");
            } else {
                String[] segments = right.substring(rightPrefix.length()).split("\\.", -1);
                boolean ok = segments.length >= 1 && segments.length <= 4;
                for (String s : segments) ok &= RIGHT_SEGMENT.matcher(s).matches();
                if (!ok) {
                    errors.add(route + ".right " + quoted(right) + " must be " + rightPrefix + " followed by 1 to 4 "
                            + "segments matching " + RIGHT_SEGMENT.pattern());
                }
            }
            if (method != null && path != null && !seen.add(method + " " + path)) {
                errors.add(route + " is declared twice");
            }
            roles(route, r, errors);
            admin(route, r, errors);
            timeoutSeconds(route, r, errors);
            audience(route, r, errors);
            portal(route, r, errors);
            // hookable is a boolean, false when absent, and only on the methods that write
            if (r.containsKey("hookable")) {
                Object hookable = r.get("hookable");
                if (!(hookable instanceof Boolean)) {
                    errors.add(route + ".hookable must be a boolean (got " + quoted(hookable) + ")");
                } else if ((Boolean) hookable && !Manifest.HOOKABLE_METHODS.contains(method)) {
                    errors.add(route + ".hookable: " + HOOKABLE_RULE + " (a hook calls a route that writes; "
                            + method + " cannot be hookable)");
                }
            }
        }
    }

    /**
     * {@code routes[].admin} (protocol 1.1): {@code tenant} or {@code platform}; a {@code platform} route
     * declares no roles (only the platform administrator holds its right); never on a {@code hookable} route (a hook
     * calls as the system, an admin route is a person's).
     */
    private static void admin(String route, Map<String, Object> r, List<String> errors) {
        if (!r.containsKey("admin") || r.get("admin") == null) return;
        Object admin = r.get("admin");
        if (!Manifest.ROUTE_ADMIN.contains(Manifest.text(admin))) {
            errors.add(route + ".admin must be one of tenant, platform (got " + quoted(admin) + ")");
            return;
        }
        if ("platform".equals(admin) && r.get("roles") instanceof List<?> roles && !roles.isEmpty()) {
            errors.add(route + ".admin \"platform\" declares no roles: only the platform administrator holds the right");
        }
        if (Boolean.TRUE.equals(r.get("hookable"))) {
            errors.add(route + ": admin and hookable exclude each other (a hook calls a route as the system; an admin "
                    + "route is a person's)");
        }
    }

    /**
     * {@code routes[].audience} (protocol 1.2): {@code "member"} -- any member of the active
     * tenant calls the route; absent or {@code null} = an ordinary route. A member route declares no {@code roles}
     * (a non-empty list), no {@code admin} and is not {@code hookable}: no role is proposed for it, it is not an
     * administration and a hook does not call it. The platform's texts.
     */
    private static void audience(String route, Map<String, Object> r, List<String> errors) {
        Object audience = r.get("audience");
        if (audience == null) return;
        if (!Manifest.ROUTE_AUDIENCE_MEMBER.equals(audience)) {
            errors.add(route + ".audience must be \"member\" (got " + quoted(audience) + ")");
            return;
        }
        boolean roles = r.get("roles") instanceof List<?> list && !list.isEmpty();
        if (roles || r.get("admin") != null || Boolean.TRUE.equals(r.get("hookable"))) {
            errors.add(route + ": audience member excludes roles, admin and hookable (any member of the tenant calls it)");
        }
    }

    /**
     * {@code routes[].portal} (protocol 1.2): when present, a JSON boolean ({@code null} is
     * refused), and {@code true} only on {@code audience: member}; absent = {@code false}. The platform's text.
     */
    private static void portal(String route, Map<String, Object> r, List<String> errors) {
        if (!r.containsKey("portal")) return;
        Object portal = r.get("portal");
        if (!(portal instanceof Boolean on)) {
            errors.add(route + ".portal must be a boolean, and only on audience member (got " + quoted(portal) + ")");
        } else if (on && !Manifest.ROUTE_AUDIENCE_MEMBER.equals(r.get("audience"))) {
            errors.add(route + ".portal must be a boolean, and only on audience member (got true, audience "
                    + quoted(r.get("audience")) + ")");
        }
    }

    /**
     * {@code routes[].timeoutSeconds} (SDK 0.7.3): when present and not {@code null}, a JSON
     * whole number from 1 to {@value Manifest#MAX_ROUTE_TIMEOUT_SECONDS} -- how long the platform waits for the module's
     * answer on this route. The platform's rule and text ({@code ModuleManifestValidator}).
     */
    private static void timeoutSeconds(String route, Map<String, Object> r, List<String> errors) {
        Object value = r.get("timeoutSeconds");
        if (value == null) return;
        Integer seconds = Manifest.wholeSeconds(value);
        if (seconds == null || seconds < 1 || seconds > Manifest.MAX_ROUTE_TIMEOUT_SECONDS) {
            errors.add(route + ".timeoutSeconds must be a whole number of seconds between 1 and "
                    + Manifest.MAX_ROUTE_TIMEOUT_SECONDS + " (got " + quoted(value) + ")");
        }
    }

    private static void roles(String route, Map<String, Object> r, List<String> errors) {
        if (!r.containsKey("roles") || r.get("roles") == null) return; // absent = [] = no role
        if (!(r.get("roles") instanceof List<?> roles)) {
            errors.add(route + ".roles must be an array of role names");
            return;
        }
        String at = route + ".roles";
        if (roles.size() > MAX_ROLES_PER_ROUTE) {
            errors.add(at + " declares " + roles.size() + " roles; at most " + MAX_ROLES_PER_ROUTE + " are allowed per route");
        }
        Set<String> seen = new HashSet<>();
        for (Object o : roles) {
            if (!(o instanceof String role) || role.isBlank()) {
                errors.add(at + ": a role name must be a non-empty string");
            } else if (role.length() > MAX_ROLE_NAME_LENGTH) {
                errors.add(at + ": a role name exceeds " + MAX_ROLE_NAME_LENGTH + " characters");
            } else if (role.chars().anyMatch(Character::isISOControl)) {
                errors.add(at + ": a role name must not contain control characters");
            } else if (!seen.add(role)) {
                errors.add(at + ": \"" + role + "\" is declared twice");
            } else if (FORBIDDEN_ROLES.contains(role.toUpperCase(Locale.ROOT))) {
                errors.add(at + ": \"" + role + "\" cannot be declared: module routes are excluded from TENANT_ADMIN "
                        + "and never name SUPER_ADMIN");
            }
            // whether the name is an existing global role is the platform's check (a warning at upload)
        }
    }

    /** The kinds a contribution to a slot of a module may have (§7.12): A react|wc, B layer, R data. */
    static final List<String> SLOT_CONTRIBUTION_KINDS = List.of("react", "wc", "layer", "data");

    private static void surfaces(String moduleSlug, Map<String, Object> root, int schemaVersion, List<Object> surfaces,
                                 Set<String> enKeys, List<String> errors) {
        Set<String> pages = new HashSet<>();
        for (int i = 0; i < surfaces.size(); i++) {
            String at = FILE + ": capabilities.surfaces[" + i + "]";
            if (!(surfaces.get(i) instanceof Map<?, ?>)) {
                errors.add(at + " must be an object");
                continue;
            }
            Map<String, Object> s = Manifest.map(surfaces.get(i));
            if (s.get("slug") instanceof String slotSlug && slotSlug.startsWith("m.")) {
                // protocol 2.0 (§7.12): a contribution to the slot of another module, the platform's
                // rules and messages (ModuleManifestValidator); whether the owner declares it is, the
                // platform's at registration
                slotContribution(at, moduleSlug, root, schemaVersion, slotSlug, s.get("kind"), errors);
                if (s.get("label") != null) requireKey(at + ".label", Manifest.text(s.get("label")), enKeys, errors);
                continue;
            }
            if (!(s.get("slug") instanceof String slug) || slug.isBlank()) errors.add(at + ".slug is required");
            String kind = Manifest.text(s.get("kind"));
            if (SLOT_CONTRIBUTION_KINDS.contains(kind) && !SURFACE_KINDS.contains(kind)) {
                errors.add(at + ".kind \"" + kind + "\" is only for a slot of a module (m.{owner}.{key}); a host surface "
                        + "takes react or wc");
            } else if (!SURFACE_KINDS.contains(kind)) {
                errors.add(at + ".kind must be one of react, wc (got " + quoted(s.get("kind")) + ")");
            }
            if (s.get("label") != null) requireKey(at + ".label", Manifest.text(s.get("label")), enKeys, errors);
            if (Manifest.ORDERFLOW_STEP.equals(s.get("slug"))) {
                // protocol 2.0 (§16.5, the platform's text): the contribution names the step it renders
                if (s.get("key") == null || (s.get("key") instanceof String k && k.isBlank())) {
                    errors.add(at + " on \"" + Manifest.ORDERFLOW_STEP + "\" needs a \"key\": the key of the step it renders");
                } else if (!(s.get("key") instanceof String k && STEP_KEY.matcher(k).matches())) {
                    errors.add(at + ".key " + quoted(s.get("key")) + " must match " + STEP_KEY.pattern());
                }
            }
            // protocol 1.1: the page of a page surface — its grammar, then (0.7.1) the type
            // of the surface in the pinned release list
            if (s.get("page") != null && !(s.get("page") instanceof String page && PAGE.matcher(page).matches())) {
                errors.add(at + ".page " + quoted(s.get("page")) + " must match " + PAGE.pattern());
                continue;
            }
            pageType(at, s.get("slug") instanceof String slug ? slug : null, (String) s.get("page"), moduleSlug, pages,
                    errors);
        }
    }

    /**
     * A contribution to {@code m.{owner}.{key}} (§7.12): schema 3 only; a well-formed key whose owner is the
     * second segment; never the module itself; the owner in {@code requires} (mandatory or optional); a kind of
     * the protocol.
     */
    private static void slotContribution(String at, String moduleSlug, Map<String, Object> root, int schemaVersion,
                                         String slotSlug, Object kind, List<String> errors) {
        if (schemaVersion < 3) {
            errors.add(at + ".slug \"" + slotSlug + "\": a contribution to a slot of a module requires \"schemaVersion\": 3 "
                    + "(this manifest is schemaVersion " + schemaVersion + ")");
            return;
        }
        String rest = slotSlug.substring(2);
        int dot = rest.indexOf('.');
        String owner = dot > 0 ? rest.substring(0, dot) : null;
        String key = dot > 0 ? rest.substring(dot + 1) : null;
        if (owner == null || !SLUG.matcher(owner).matches() || key.length() > Manifest.MAX_SLOT_KEY_LENGTH
                || !SLOT_KEY.matcher(key).matches()) {
            errors.add(at + ".slug \"" + slotSlug + "\" is not a slot key m.{owner}.{key} (the owner is a module slug, the "
                    + "key ends with its major, e.g. m.kf-3d.viewport.layers.v1)");
        } else if (owner.equals(moduleSlug)) {
            errors.add(at + ".slug \"" + slotSlug + "\" is a slot of this module itself; a module does not contribute to its "
                    + "own slots");
        } else if (!requiredSlugs(root).contains(owner)) {
            errors.add(at + ".slug \"" + slotSlug + "\": the owner \"" + owner + "\" is not in requires (declare it there, "
                    + "mandatory or optional)");
        }
        if (!SLOT_CONTRIBUTION_KINDS.contains(Manifest.text(kind))) {
            errors.add(at + ".kind must be one of react, wc, layer, data (got " + quoted(kind) + ")");
        }
    }

    /** The slugs of {@code requires[]}, mandatory or optional. */
    private static Set<String> requiredSlugs(Map<String, Object> root) {
        Set<String> required = new HashSet<>();
        if (root.get("requires") instanceof List<?> requires) {
            for (Object entry : requires) {
                if (entry instanceof Map<?, ?> m && m.get("slug") instanceof String s) required.add(s);
            }
        }
        return required;
    }

    /**
     * {@code surfaces[].page} against the type of its surface (0.7.1; the platform's rule):
     * only a surface of type {@code page} takes one, it always does, and one module serves each page once (the host opens
     * {@code /m/{slug}/{page}}). A surface the pinned release list does not declare is left to the platform.
     */
    private static void pageType(String at, String surfaceSlug, String page, String moduleSlug, Set<String> pages,
                                 List<String> errors) {
        String type = surfaceSlug == null ? null : Surfaces.TYPES.get(surfaceSlug);
        if (type == null) {
            return;
        }
        if (!PAGE_SURFACE_TYPE.equals(type)) {
            if (page != null) {
                errors.add(at + ".page: page only on surfaces of type page (" + quoted(surfaceSlug) + " is of type "
                        + type + ")");
            }
            return;
        }
        if (page == null) {
            errors.add(at + ".page is required on a surface of type page (" + quoted(surfaceSlug) + "): the host "
                    + "opens it at /m/" + moduleSlug + "/<page>");
            return;
        }
        if (!pages.add(surfaceSlug + "|" + page)) {
            errors.add(at + ".page \"" + page + "\" is declared twice on " + quoted(surfaceSlug));
        }
    }

    private static void notifications(List<Object> notifications, Set<String> enKeys, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < notifications.size(); i++) {
            String at = FILE + ": capabilities.notifications[" + i + "]";
            if (!(notifications.get(i) instanceof Map<?, ?>)) {
                errors.add(at + " must be an object");
                continue;
            }
            Map<String, Object> n = Manifest.map(notifications.get(i));
            String key = Manifest.text(n.get("key"));
            if (key == null || !NOTIFICATION_KEY.matcher(key).matches()) {
                errors.add(at + ".key " + quoted(n.get("key")) + " must match " + NOTIFICATION_KEY.pattern());
            } else if (!seen.add(key)) {
                errors.add(at + ".key \"" + key + "\" is declared twice");
            }
            List<String> channels = Manifest.texts(n.get("channels"));
            if (channels.isEmpty()) errors.add(at + ".channels must not be empty (one or more of in_app, email)");
            for (String channel : channels) {
                if (!CHANNELS.contains(channel)) errors.add(at + ".channels: \"" + channel + "\" is not a channel (one of in_app, email)");
            }
            requireKey(at + ".subject", Manifest.text(n.get("subject")), enKeys, errors);
            requireKey(at + ".body", Manifest.text(n.get("body")), enKeys, errors);
        }
    }

    private static void reads(List<Object> reads, List<String> errors) {
        for (int i = 0; i < reads.size(); i++) {
            String at = FILE + ": capabilities.reads[" + i + "]";
            if (!(reads.get(i) instanceof Map<?, ?>)) {
                errors.add(at + " must be an object");
                continue;
            }
            Map<String, Object> r = Manifest.map(reads.get(i));
            if (!(r.get("port") instanceof String port) || port.isBlank()) {
                errors.add(at + ".port is required");
            } else if (!PORT.matcher(port).matches()) {
                errors.add(at + ".port \"" + port + "\" must match " + PORT.pattern() + " (a port id: entity, optional "
                        + "sub-segment, major version, e.g. model.v1)");
            }
            if (r.get("access") != null && !"read".equals(r.get("access"))) {
                errors.add(at + ".access must be absent or \"read\": no module writes the platform's data");
            }
        }
    }

    /** {@code [{"access": "write" | "read-own"}]}, each value at most once. */
    private static void audit(List<Object> audit, List<String> errors) {
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < audit.size(); i++) {
            String at = FILE + ": capabilities.audit[" + i + "]";
            if (!(audit.get(i) instanceof Map<?, ?>)) {
                errors.add(at + " must be an object {\"access\": \"write\" | \"read-own\"}");
                continue;
            }
            Map<String, Object> a = Manifest.map(audit.get(i));
            String access = Manifest.text(a.get("access"));
            if (!Manifest.AUDIT_ACCESS.contains(access)) {
                errors.add(at + ".access must be one of write, read-own (got " + quoted(a.get("access")) + ")");
            } else if (!seen.add(access)) {
                errors.add(at + ".access \"" + access + "\" is declared twice");
            }
        }
    }

    /**
     * {@code nav} (protocol 1.1): the module's menu entries, each a closed object. Checked here:
     * the surface, the shape of the group and of the key, a key once per surface, the label key, the url (an in-app
     * path), the icon name, the order, the flag (one of the module's), and the right -- never on {@code customer}, and
     * a right under {@code api.v1.m.} only when it is the right of one of the module's own routes. Whether the group
     * is a group of the surface and whether a platform code is declared stay the platform's check at upload.
     */
    private static void nav(String slug, Map<String, Object> root, List<Object> nav, Set<String> enKeys,
                            List<String> errors) {
        Set<String> routeRights = new HashSet<>();
        for (Map<String, Object> route : Manifest.objects(Manifest.map(root.get("capabilities")).get("routes"))) {
            String right = Manifest.text(route.get("right"));
            if (right != null) routeRights.add(right);
        }
        Set<String> flags = new HashSet<>();
        for (Map<String, Object> flag : Manifest.objects(root.get("featureFlags"))) {
            String key = Manifest.text(flag.get("key"));
            if (key != null) flags.add(key);
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < nav.size(); i++) {
            String at = FILE + ": capabilities.nav[" + i + "]";
            if (!(nav.get(i) instanceof Map<?, ?>)) {
                errors.add(at + " must be an object");
                continue;
            }
            Map<String, Object> n = Manifest.map(nav.get(i));
            for (String key : n.keySet()) {
                if (!Manifest.NAV_KEYS.contains(key)) {
                    errors.add(at + ": \"" + key + "\" is not a key of a nav entry (known: "
                            + String.join(", ", Manifest.NAV_KEYS) + ")");
                }
            }
            String surface = Manifest.text(n.get("surface"));
            if (!Manifest.NAV_SURFACES.contains(surface)) {
                errors.add(at + ".surface must be one of " + String.join(", ", Manifest.NAV_SURFACES) + " (got "
                        + quoted(n.get("surface")) + ")");
            }
            String group = Manifest.text(n.get("group"));
            if (group == null || !NAV_KEY.matcher(group).matches()) {
                errors.add(at + ".group must match " + NAV_KEY.pattern() + " (got " + quoted(n.get("group")) + ")");
            }
            String key = Manifest.text(n.get("key"));
            if (key == null || !NAV_KEY.matcher(key).matches()) {
                errors.add(at + ".key must match " + NAV_KEY.pattern() + " (got " + quoted(n.get("key")) + ")");
            } else if (surface != null && !seen.add(surface + " " + key)) {
                errors.add(at + ".key \"" + key + "\" is declared twice on the surface " + surface);
            }
            requireKey(at + ".label", Manifest.text(n.get("label")), enKeys, errors);
            if (!isInAppPath(n.get("url"))) {
                errors.add(at + ".url must be an in-app path: it starts with \"/\" and has no \"//\", \"..\", \"%\", "
                        + "backslash, whitespace, scheme, query or fragment (got " + quoted(n.get("url")) + ")");
            }
            if (n.get("icon") != null && !(n.get("icon") instanceof String icon && NAV_ICON.matcher(icon).matches())) {
                errors.add(at + ".icon must be the name of an icon of the host, " + NAV_ICON.pattern()
                        + " (e.g. \"Boxes\"; got " + quoted(n.get("icon")) + ")");
            }
            if (n.get("order") != null && !(n.get("order") instanceof Integer order && order >= 0
                    && order <= MAX_NAV_ORDER)) {
                errors.add(at + ".order must be an integer from 0 to " + MAX_NAV_ORDER + " (got "
                        + quoted(n.get("order")) + ")");
            }
            if (n.get("flag") != null) {
                String flag = Manifest.text(n.get("flag"));
                if (flag == null || !flags.contains(flag)) {
                    errors.add(at + ".flag " + quoted(n.get("flag")) + " is not a feature flag of this module "
                            + "(featureFlags[].key)");
                }
            }
            if (n.get("right") != null) {
                navRight(at, slug, surface, n.get("right"), routeRights, errors);
            }
        }
    }

    private static void navRight(String at, String slug, String surface, Object raw, Set<String> routeRights,
                                 List<String> errors) {
        if ("customer".equals(surface)) {
            errors.add(at + ".right is not allowed on the customer surface: the customer menu is filtered on the "
                    + "customer's identity, not on rights");
            return;
        }
        String right = Manifest.text(raw);
        if (right != null && right.startsWith("api.v1.m.")) {
            if (!routeRights.contains(right)) {
                errors.add(at + ".right \"" + right + "\" is not the right of a route this module declares "
                        + "(capabilities.routes[].right of api.v1.m." + slug + ".*)");
            }
        } else if (right == null || !PLATFORM_RIGHT.matcher(right).matches()) {
            errors.add(at + ".right " + quoted(raw) + " is not a right code: the right of one of the module's routes "
                    + "or a code the platform declares (" + PLATFORM_RIGHT.pattern() + ")");
        }
    }

    /**
     * {@code settings} (protocol 1.1): closed scalars only. Each a closed object
     * {@code {key, type, default, label, description?, values?, min?, max?}}; {@code type} {@code BOOLEAN},
     * {@code NUMBER} or {@code ENUM} ("settings carry no data": no free text, list, reference or secret); the default
     * coherent with the type; {@code values} only and always on {@code ENUM}; {@code min}/{@code max} only on
     * {@code NUMBER}; unique keys; at most {@value Manifest#MAX_SETTINGS}.
     */
    private static void settings(List<Object> settings, Set<String> enKeys, List<String> errors) {
        if (settings.size() > Manifest.MAX_SETTINGS) {
            errors.add(FILE + ": capabilities.settings declares " + settings.size() + " settings; at most "
                    + Manifest.MAX_SETTINGS + " settings are allowed");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < settings.size(); i++) {
            String at = FILE + ": capabilities.settings[" + i + "]";
            if (!(settings.get(i) instanceof Map<?, ?>)) {
                errors.add(at + " must be an object");
                continue;
            }
            Map<String, Object> s = Manifest.map(settings.get(i));
            for (String key : s.keySet()) {
                if (!Manifest.SETTING_KEYS.contains(key)) {
                    errors.add(at + ": \"" + key + "\" is not a key of a setting (known: "
                            + String.join(", ", Manifest.SETTING_KEYS) + ")");
                }
            }
            String key = Manifest.text(s.get("key"));
            if (key == null || !SETTING_KEY.matcher(key).matches()) {
                errors.add(at + ".key must match " + SETTING_KEY.pattern() + " (got " + quoted(s.get("key")) + ")");
            } else if (!seen.add(key)) {
                errors.add(at + ".key \"" + key + "\" is declared twice");
            }
            requireKey(at + ".label", Manifest.text(s.get("label")), enKeys, errors);
            if (s.containsKey("description") && s.get("description") != null) {
                requireKey(at + ".description", Manifest.text(s.get("description")), enKeys, errors);
            }
            String type = Manifest.text(s.get("type"));
            if (!Manifest.SETTING_TYPES.contains(type)) {
                errors.add(at + ".type " + quoted(s.get("type")) + " is not allowed: " + SETTINGS_RULE
                        + " (free text, lists, references and secrets are data: they live on surfaces or in secrets)");
                continue;
            }
            if (!"ENUM".equals(type) && s.get("values") != null) errors.add(at + ".values only on an ENUM setting");
            if (!"NUMBER".equals(type) && (s.get("min") != null || s.get("max") != null)) {
                errors.add(at + ".min and .max only on a NUMBER setting");
            }
            Object def = s.get("default");
            if (def == null) {
                errors.add(at + ".default is required (the value before the tenant administrator sets one)");
                continue;
            }
            switch (type) {
                case "BOOLEAN" -> {
                    if (!(def instanceof Boolean)) errors.add(at + ".default must be a boolean (got " + quoted(def) + ")");
                }
                case "NUMBER" -> numberSetting(at, s, def, errors);
                default -> enumSetting(at, s, def, errors);
            }
        }
    }

    private static void numberSetting(String at, Map<String, Object> s, Object def, List<String> errors) {
        BigDecimal min = null;
        BigDecimal max = null;
        if (s.get("min") != null) {
            if (s.get("min") instanceof Number n) min = new BigDecimal(n.toString());
            else errors.add(at + ".min must be a number (got " + quoted(s.get("min")) + ")");
        }
        if (s.get("max") != null) {
            if (s.get("max") instanceof Number n) max = new BigDecimal(n.toString());
            else errors.add(at + ".max must be a number (got " + quoted(s.get("max")) + ")");
        }
        if (min != null && max != null && min.compareTo(max) > 0) errors.add(at + ".min must not be above .max");
        if (!(def instanceof Number n)) {
            errors.add(at + ".default must be a number (got " + quoted(def) + ")");
            return;
        }
        BigDecimal value = new BigDecimal(n.toString());
        if ((min != null && value.compareTo(min) < 0) || (max != null && value.compareTo(max) > 0)) {
            errors.add(at + ".default must be between min and max (got " + n + ")");
        }
    }

    private static void enumSetting(String at, Map<String, Object> s, Object def, List<String> errors) {
        if (!(s.get("values") instanceof List<?> values) || values.isEmpty()) {
            errors.add(at + ".values must be a non-empty list of codes on an ENUM setting (a closed choice)");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Object v : values) {
            if (!(v instanceof String code) || !ENUM_VALUE.matcher(code).matches()) {
                errors.add(at + ".values: " + quoted(v) + " must match " + ENUM_VALUE.pattern());
            } else if (!seen.add(code)) {
                errors.add(at + ".values: \"" + code + "\" is declared twice");
            }
        }
        if (!(def instanceof String code) || !seen.contains(code)) {
            errors.add(at + ".default must be one of the values (got " + quoted(def) + ")");
        }
    }

    /**
     * {@code secrets} (protocol 1.1): closed objects {@code {name, scope, writeRight}} -- a
     * value never travels in the manifest; unique names; {@code scope} {@code platform} or {@code tenant};
     * {@code writeRight} the right of a declared route, and for a {@code platform} secret a route with
     * {@code admin: platform}; {@code external} (SDK 0.7.2): see {@link #secretExternal}.
     */
    private static void secrets(Map<String, Object> root, List<Object> secrets, List<String> errors) {
        Map<String, Object> adminByRight = new HashMap<>();
        for (Map<String, Object> route : Manifest.objects(Manifest.map(root.get("capabilities")).get("routes"))) {
            String right = Manifest.text(route.get("right"));
            if (right != null) adminByRight.put(right, route.get("admin") == null ? "" : route.get("admin"));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < secrets.size(); i++) {
            String at = FILE + ": capabilities.secrets[" + i + "]";
            if (!(secrets.get(i) instanceof Map<?, ?>)) {
                errors.add(at + " must be an object");
                continue;
            }
            Map<String, Object> s = Manifest.map(secrets.get(i));
            for (String key : s.keySet()) {
                if (!Manifest.SECRET_KEYS.contains(key)) {
                    errors.add(at + ": \"" + key + "\" is not a key of a secret (known: "
                            + String.join(", ", Manifest.SECRET_KEYS) + "; a value is written through the platform, "
                            + "never in the manifest)");
                }
            }
            String name = Manifest.text(s.get("name"));
            if (name == null || !SETTING_KEY.matcher(name).matches()) {
                errors.add(at + ".name must match " + SETTING_KEY.pattern() + " (got " + quoted(s.get("name")) + ")");
            } else if (!seen.add(name)) {
                errors.add(at + ".name \"" + name + "\" is declared twice");
            }
            String scope = Manifest.text(s.get("scope"));
            if (!Manifest.SECRET_SCOPES.contains(scope)) {
                errors.add(at + ".scope must be one of platform, tenant (got " + quoted(s.get("scope")) + ")");
            }
            String writeRight = Manifest.text(s.get("writeRight"));
            if (writeRight == null || !adminByRight.containsKey(writeRight)) {
                errors.add(at + ".writeRight " + quoted(s.get("writeRight")) + " is not the right of a route this module "
                        + "declares (capabilities.routes[].right)");
            } else if ("platform".equals(scope) && !"platform".equals(adminByRight.get(writeRight))) {
                errors.add(at + ".writeRight \"" + writeRight + "\": a platform secret is written only through a route "
                        + "with \"admin\": \"platform\"");
            }
            if (s.containsKey("external")) secretExternal(at, scope, s.get("external"), errors);
        }
    }

    /**
     * {@code secrets[].external} (SDK 0.7.2): a non-empty array of at most
     * {@value Manifest#SECRET_MAX_EXTERNAL} distinct prefixes of {@link Manifest#SECRET_EXTERNAL_PREFIX}, none reaching
     * the modules' secrets ({@code kf-modules}), only on a {@code platform} secret -- the platform's rules and texts.
     */
    private static void secretExternal(String at, String scope, Object external, List<String> errors) {
        int max = Manifest.SECRET_MAX_EXTERNAL;
        if (!(external instanceof List<?> list) || list.isEmpty() || list.size() > max) {
            errors.add(at + ".external must be a non-empty array of at most " + max + " prefixes");
            if (!(external instanceof List<?>)) return;
        }
        List<?> prefixes = (List<?>) external;
        if (!"platform".equals(scope)) errors.add(at + ".external is allowed only on a platform secret");
        Set<String> seen = new HashSet<>();
        for (int j = 0; j < prefixes.size(); j++) {
            String prefix = Manifest.text(prefixes.get(j));
            if (prefix == null || !SECRET_EXTERNAL_PREFIX.matcher(prefix).matches()) {
                errors.add(at + ".external[" + j + "] must match " + SECRET_EXTERNAL_PREFIX.pattern() + " (got "
                        + quoted(prefixes.get(j)) + ")");
            } else if (List.of(prefix.split("/")).contains("kf-modules")) {
                errors.add(at + ".external[" + j + "] \"" + prefix + "\" must not reach the modules' secrets (kf-modules)");
            } else if (!seen.add(prefix)) {
                errors.add(at + ".external[" + j + "] \"" + prefix + "\" is declared twice");
            }
        }
    }

    /**
     * {@code provides} (protocol 1.1): closed objects {@code {port, path}}; the port grammar
     * and once per port; {@code path} the path of a GET route this module declares. Whether the platform consumes the
     * port is its catalog.
     */
    private static void provides(Map<String, Object> root, List<Object> provides, List<String> errors) {
        Set<String> getPaths = new HashSet<>();
        for (Map<String, Object> route : Manifest.objects(Manifest.map(root.get("capabilities")).get("routes"))) {
            if ("GET".equals(route.get("method")) && route.get("path") instanceof String path) getPaths.add(path);
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < provides.size(); i++) {
            String at = FILE + ": capabilities.provides[" + i + "]";
            if (!(provides.get(i) instanceof Map<?, ?>)) {
                errors.add(at + " must be an object");
                continue;
            }
            Map<String, Object> p = Manifest.map(provides.get(i));
            for (String key : p.keySet()) {
                if (!Manifest.PROVIDE_KEYS.contains(key)) {
                    errors.add(at + ": \"" + key + "\" is not a key of a provided read (known: "
                            + String.join(", ", Manifest.PROVIDE_KEYS) + ")");
                }
            }
            String port = Manifest.text(p.get("port"));
            if (port == null || !PORT.matcher(port).matches()) {
                errors.add(at + ".port " + quoted(p.get("port")) + " must match " + PORT.pattern());
            } else if (!seen.add(port)) {
                errors.add(at + ".port \"" + port + "\" is declared twice");
            }
            String path = Manifest.text(p.get("path"));
            if (path == null || !getPaths.contains(path)) {
                errors.add(at + ".path " + quoted(p.get("path")) + " is not a GET route this module declares "
                        + "(capabilities.routes[] with method GET)");
            }
        }
    }

    /**
     * {@code capabilities.slots} (protocol 2.0, {@code schemaVersion} 3 only, sdk-protocol.md §7.12): closed
     * objects; the key grammar ({@link Manifest#SLOT_KEY}, at most {@value Manifest#MAX_SLOT_KEY_LENGTH} characters,
     * the major as the last segment), unique; {@code kind} A/B/D/R and a {@code type} of the kind; {@code visibility}
     * mandatory ({@code internal} | {@code public}); {@code label} a key of {@code i18n.en}; the keys of one kind
     * only on that kind; {@code layerKinds} on B, {@code events} / {@code commands} on D after its type,
     * {@code dataSchema} on R ({@code m.{owner}.{name}.v{N}}, the owner being this module).
     */
    private static void slots(String slug, List<Object> slots, Set<String> enKeys, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < slots.size(); i++) {
            String at = FILE + ": capabilities.slots[" + i + "]";
            if (!(slots.get(i) instanceof Map<?, ?>)) {
                errors.add(at + " must be an object");
                continue;
            }
            Map<String, Object> s = Manifest.map(slots.get(i));
            for (String key : s.keySet()) {
                if (!Manifest.SLOT_KEYS.contains(key)) {
                    errors.add(at + ": \"" + key + "\" is not a key of a slot (known: "
                            + String.join(", ", Manifest.SLOT_KEYS) + ")");
                }
            }
            String key = Manifest.text(s.get("key"));
            if (key == null || key.length() > Manifest.MAX_SLOT_KEY_LENGTH || !SLOT_KEY.matcher(key).matches()) {
                errors.add(at + ".key " + quoted(s.get("key")) + " must match " + SLOT_KEY.pattern() + " (at most "
                        + Manifest.MAX_SLOT_KEY_LENGTH + " characters; the major is the last segment, e.g. viewport.layers.v1)");
            } else if (!seen.add(key)) {
                errors.add(at + ".key \"" + key + "\" is declared twice");
            }
            Manifest.SlotKind kind = Manifest.SlotKind.of(s.get("kind"));
            if (kind == null) {
                errors.add(at + ".kind must be \"A\", \"B\", \"D\" or \"R\" (got " + quoted(s.get("kind")) + ")");
            } else if (!(s.get("type") instanceof String type) || !kind.types().contains(type)) {
                errors.add(at + ".type must be one of " + kind.types() + " on kind " + kind + " (got "
                        + quoted(s.get("type")) + ")");
            }
            if (Manifest.SlotVisibility.of(s.get("visibility")) == null) {
                errors.add(at + ".visibility must be \"internal\" or \"public\" (got " + quoted(s.get("visibility"))
                        + "): the owner chooses it, there is no default");
            }
            if (s.containsKey("label") && s.get("label") != null) {
                requireKey(at + ".label", Manifest.text(s.get("label")), enKeys, errors);
            }
            if (kind == null) continue;
            for (Manifest.SlotKind other : Manifest.SlotKind.values()) {
                if (other == kind) continue;
                for (String own : other.ownKeys()) {
                    if (s.containsKey(own) && !kind.ownKeys().contains(own)) {
                        errors.add(at + "." + own + " is only on kind " + other);
                    }
                }
            }
            switch (kind) {
                case A -> {
                    if (s.containsKey("publicMembers")) names(at + ".publicMembers", s.get("publicMembers"), SLOT_MEMBER, false, errors);
                    if (s.containsKey("actions")) names(at + ".actions", s.get("actions"), SLOT_NAME, false, errors);
                }
                case B -> names(at + ".layerKinds", s.get("layerKinds"), SLOT_LAYER_KIND, true, errors);
                case D -> {
                    boolean events = "events".equals(s.get("type"));
                    String own = events ? "events" : "commands";
                    String foreign = events ? "commands" : "events";
                    names(at + "." + own, s.get(own), SLOT_NAME, true, errors);
                    if (s.containsKey(foreign)) {
                        errors.add(at + "." + foreign + " is not allowed on a slot of type " + s.get("type"));
                    }
                }
                case R -> {
                    String schema = Manifest.text(s.get("dataSchema"));
                    if (schema == null || slug == null
                            || !schema.startsWith("m." + slug + ".")
                            || !SLOT_SCHEMA_TAIL.matcher(schema.substring(slug.length() + 3)).matches()) {
                        errors.add(at + ".dataSchema " + quoted(s.get("dataSchema")) + " is required on kind R: the id "
                                + "m.{owner}.{name}.v{N} of the schema the owner validates the data against, the owner "
                                + "being this module (\"" + slug + "\")");
                    }
                }
            }
        }
    }

    /** A list of distinct names, each matching {@code grammar}; {@code required} = present and non-empty. */
    private static void names(String at, Object raw, Pattern grammar, boolean required, List<String> errors) {
        if (raw == null && !required) return;
        if (!(raw instanceof List<?> list) || (required && list.isEmpty())) {
            errors.add(at + " is required: a non-empty array of names matching " + grammar.pattern());
            return;
        }
        Set<Object> seen = new HashSet<>();
        for (Object name : list) {
            if (!(name instanceof String n) || !grammar.matcher(n).matches()) {
                errors.add(at + ": " + quoted(name) + " must match " + grammar.pattern());
            } else if (!seen.add(n)) {
                errors.add(at + ": \"" + n + "\" is declared twice");
            }
        }
    }

    /**
     * {@code requires} (protocol 2.0): the shape the platform checks at upload
     * ({@code ModulePackValidator.validateRequires}), with its messages -- a slug, never the module itself, once,
     * a non-empty range in the platform's grammar ({@code VersionRange}); {@code requires[i].optional} only under
     * {@code schemaVersion} 3, and then a boolean. Whether the required module exists, its range is satisfied, a cycle
     * appears or holds are the platform's (they need the registry). Checked under every {@code schemaVersion}.
     */
    private static void requires(Map<String, Object> root, int schemaVersion, List<String> errors) {
        if (!(root.get("requires") instanceof List<?> requires)) return;
        String self = Manifest.text(root.get("slug"));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < requires.size(); i++) {
            if (!(requires.get(i) instanceof Map<?, ?>)) {
                errors.add(FILE + ": \"requires\" contains a non-object entry");
                continue;
            }
            Map<String, Object> entry = Manifest.map(requires.get(i));
            if (entry.containsKey("optional")) {
                if (schemaVersion < 3) {
                    errors.add(FILE + ": \"requires[" + i + "].optional\" requires \"schemaVersion\": 3 (this manifest is "
                            + "schemaVersion " + schemaVersion + ")");
                } else if (!(entry.get("optional") instanceof Boolean)) {
                    errors.add(FILE + ": \"requires[" + i + "].optional\" must be a boolean (got "
                            + jsonish(entry.get("optional")) + ")");
                }
            }
            String slug = Manifest.text(entry.get("slug"));
            if (slug == null || !SLUG.matcher(slug).matches()) {
                errors.add(FILE + ": \"requires\" slug must match " + SLUG.pattern() + " but was " + quoted(entry.get("slug")));
                continue;
            }
            if (slug.equals(self)) errors.add(FILE + ": a module cannot require itself (\"" + slug + "\")");
            if (!seen.add(slug)) errors.add(FILE + ": duplicate \"requires\" entry for \"" + slug + "\"");
            String range = Manifest.text(entry.get("versionRange"));
            if (range == null || range.isBlank()) {
                errors.add(FILE + ": \"requires\" entry for \"" + slug + "\" needs a non-empty \"versionRange\"");
                continue;
            }
            String problem = versionRangeProblem(range);
            if (problem != null) errors.add(FILE + ": \"requires\" entry for \"" + slug + "\": " + problem);
        }
    }

    /**
     * {@code featureFlags[i].requiresModules} (protocol 2.0): only under {@code schemaVersion} 3; an
     * array of module slugs, never the module itself, each one declared in {@code requires} (mandatory or optional). The
     * platform's messages ({@code ModuleManifestValidator}).
     */
    private static void flagRequiresModules(Map<String, Object> root, int schemaVersion, List<String> errors) {
        if (!(root.get("featureFlags") instanceof List<?> flags)) return;
        String self = Manifest.text(root.get("slug"));
        Set<String> required = new HashSet<>();
        for (Map<String, Object> entry : Manifest.objects(root.get("requires"))) {
            if (entry.get("slug") instanceof String s) required.add(s);
        }
        for (int i = 0; i < flags.size(); i++) {
            if (!(flags.get(i) instanceof Map<?, ?>)) continue;
            Map<String, Object> flag = Manifest.map(flags.get(i));
            if (!flag.containsKey("requiresModules") || flag.get("requiresModules") == null) continue;
            String at = FILE + ": \"featureFlags[" + i + "].requiresModules\"";
            if (schemaVersion < 3) {
                errors.add(at + " requires \"schemaVersion\": 3 (this manifest is schemaVersion " + schemaVersion + ")");
                continue;
            }
            if (!(flag.get("requiresModules") instanceof List<?> modules)) {
                errors.add(at + " must be an array of module slugs");
                continue;
            }
            for (Object module : modules) {
                if (!(module instanceof String value) || !SLUG.matcher(value).matches()) {
                    errors.add(at + ": " + jsonish(module) + " is not a module slug (must match " + SLUG.pattern() + ")");
                } else if (value.equals(self)) {
                    errors.add(at + ": \"" + value + "\" is this module itself");
                } else if (!required.contains(value)) {
                    errors.add(at + ": \"" + value + "\" is not in requires (declare it there, mandatory or optional)");
                }
            }
        }
    }

    /** The platform's {@code VersionRange.EXPECTED_FORM}, named in every parse error. */
    static final String VERSION_RANGE_EXPECTED_FORM =
            "expected one of: \"*\", \"1.2.3\", \">=1.2.3\", \">1.2.3\", \"<=1.2.3\", \"<1.2.3\", "
                    + "or two bounds separated by a space such as \">=1.2.0 <2.0.0\"";

    /**
     * The platform's grammar of {@code requires[].versionRange} ({@code VersionRange.parse}), copied: {@code *}
     * or blank = any; one or two space-separated bounds {@code >=}, {@code >}, {@code <=}, {@code <}, {@code =} or a bare
     * version (exact); at most one lower and one upper bound, an exact version alone; the bounds must not cross.
     *
     * @return the platform's message when {@code raw} is not a range, else {@code null}
     */
    static String versionRangeProblem(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || "*".equals(text)) return null;
        String lower = null;
        boolean lowerInclusive = false;
        String upper = null;
        boolean upperInclusive = false;
        for (String token : text.split("\\s+")) {
            if (token.isEmpty()) continue;
            String side;
            String version;
            boolean inclusive;
            if (token.startsWith(">=")) { side = "lower"; version = token.substring(2); inclusive = true; }
            else if (token.startsWith("<=")) { side = "upper"; version = token.substring(2); inclusive = true; }
            else if (token.startsWith(">")) { side = "lower"; version = token.substring(1); inclusive = false; }
            else if (token.startsWith("<")) { side = "upper"; version = token.substring(1); inclusive = false; }
            else if (token.startsWith("=")) { side = "exact"; version = token.substring(1); inclusive = true; }
            else { side = "exact"; version = token; inclusive = true; }
            version = version.trim();
            if (!SEMVER.matcher(version).matches()) {
                return rangeMalformed(raw, "'" + version + "' is not a semantic version");
            }
            switch (side) {
                case "lower" -> {
                    if (lower != null) return rangeMalformed(raw, "two lower bounds");
                    lower = version;
                    lowerInclusive = inclusive;
                }
                case "upper" -> {
                    if (upper != null) return rangeMalformed(raw, "two upper bounds");
                    upper = version;
                    upperInclusive = inclusive;
                }
                default -> {
                    if (lower != null || upper != null) {
                        return rangeMalformed(raw, "an exact version combined with another bound");
                    }
                    lower = version;
                    upper = version;
                    lowerInclusive = true;
                    upperInclusive = true;
                }
            }
        }
        if (lower != null && upper != null) {
            int comparison = compareSemver(lower, upper);
            if (comparison > 0 || (comparison == 0 && !(lowerInclusive && upperInclusive))) {
                return "version range is impossible: lower bound " + (lowerInclusive ? ">=" : ">") + lower
                        + " is above upper bound " + (upperInclusive ? "<=" : "<") + upper
                        + ", so no version could ever satisfy it";
            }
        }
        return null;
    }

    private static String rangeMalformed(String raw, String why) {
        return "version range '" + raw + "' could not be parsed (" + why + "); " + VERSION_RANGE_EXPECTED_FORM;
    }

    /** A value as JSON-ish text for a message: a string quoted, anything else as it prints. */
    private static String jsonish(Object value) {
        return quoted(value);
    }

    /** A menu url the host may render as an in-app link (the platform's {@code NavigationContributionRegistry} rule). */
    static boolean isInAppPath(Object raw) {
        if (!(raw instanceof String url) || url.length() > MAX_NAV_URL_LENGTH || !url.startsWith("/")) return false;
        if (url.contains("//") || url.contains("..") || url.contains("%") || url.contains("\\")
                || url.contains("?") || url.contains("#")) {
            return false;
        }
        return url.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    // ---- registration ⊆ manifest -----------------------------------------------------------------------------

    private static void checkRegistration(Manifest manifest, Map<String, Object> body, List<String> errors) {
        List<Map<String, Object>> hooks = Manifest.objects(body.get("hooks"));
        if (!manifest.usesKeys()) {
            for (int i = 0; i < hooks.size(); i++) {
                if ("block".equals(hooks.get(i).get("onFailure"))) {
                    errors.add("registration: hooks[" + i + "]: onFailure \"block\" requires a capability, which a "
                            + "schemaVersion 1 manifest cannot declare; upgrade the module to manifest schemaVersion 2");
                }
            }
            return;
        }
        Manifest.Capabilities caps = manifest.capabilities();
        Set<String> enKeys = manifest.i18n().getOrDefault("en", Map.of()).keySet();

        for (int i = 0; i < hooks.size(); i++) {
            String event = Manifest.text(hooks.get(i).get("event"));
            String at = "registration: hooks[" + i + "]";
            Manifest.HookCapability declared = caps.hooks().stream().filter(h -> Objects.equals(event, h.point()))
                    .findFirst().orElse(null);
            if (declared == null) {
                errors.add(at + ": event \"" + event + "\" is not declared in kf-module-conf.json capabilities.hooks "
                        + "(declared: " + declaredPoints(caps) + ")");
                continue;
            }
            if ("block".equals(hooks.get(i).get("onFailure")) && !"block".equals(declared.onFailure())) {
                errors.add(at + ": onFailure \"block\" on " + event + " requires the capability declared in "
                        + "kf-module-conf.json (capabilities.hooks[].onFailure = \"block\")");
            }
        }

        List<Map<String, Object>> fields = Manifest.objects(body.get("fields"));
        for (int i = 0; i < fields.size(); i++) {
            Map<String, Object> f = fields.get(i);
            String entity = Manifest.text(f.get("entity")) == null ? "model" : Manifest.text(f.get("entity"));
            String name = Manifest.text(f.get("name"));
            boolean declared = caps.fields().stream().anyMatch(c -> entity.equals(c.entity()) && Objects.equals(name, c.name())
                    && Objects.equals(f.get("type"), c.type()) && Objects.equals(f.get("table"), c.table()));
            if (!declared) {
                errors.add("registration: fields[" + i + "]: field \"" + entity + "." + name + "\" (" + f.get("type")
                        + ", table " + f.get("table") + ") is not declared in kf-module-conf.json capabilities.fields");
            }
            labelKey("registration: fields[" + i + "]", f.get("label"), enKeys, errors);
        }

        List<Map<String, Object>> surfaces = Manifest.objects(body.get("surfaces"));
        for (int i = 0; i < surfaces.size(); i++) {
            Map<String, Object> s = surfaces.get(i);
            boolean declared = caps.surfaces().stream().anyMatch(c -> Objects.equals(s.get("slug"), c.slug())
                    && Objects.equals(s.get("kind"), c.kind()));
            if (!declared) {
                errors.add("registration: surfaces[" + i + "]: surface \"" + s.get("slug") + "\" (" + s.get("kind")
                        + ") is not declared in kf-module-conf.json capabilities.surfaces");
            } else if (Manifest.ORDERFLOW_STEP.equals(s.get("slug"))) {
                // protocol 2.0 (§16.5): the step the contribution renders, as declared
                if (!(s.get("key") instanceof String key) || key.isBlank()) {
                    errors.add("registration: surfaces[" + i + "] on \"" + Manifest.ORDERFLOW_STEP + "\" needs a \"key\": "
                            + "the key of the step it renders");
                } else if (caps.surfaces().stream().noneMatch(c -> Manifest.ORDERFLOW_STEP.equals(c.slug())
                        && key.equals(c.key()))) {
                    errors.add("registration: surfaces[" + i + "]: the step \"" + key + "\" of \""
                            + Manifest.ORDERFLOW_STEP + "\" is not declared in kf-module-conf.json capabilities.surfaces");
                }
            }
            labelKey("registration: surfaces[" + i + "]", s.get("label"), enKeys, errors);
        }
    }

    /** a key used at registration has an {@code en} text in the manifest; an absent label stays legal. */
    private static void labelKey(String at, Object label, Set<String> enKeys, List<String> errors) {
        if (label == null) return;
        if (!(label instanceof String key) || !enKeys.contains(key)) {
            errors.add(at + ": label " + quoted(label) + " is not a key of the manifest i18n block with an \"en\" text");
        }
    }

    private static String declaredPoints(Manifest.Capabilities caps) {
        String points = String.join(", ", caps.hooks().stream().map(Manifest.HookCapability::point)
                .filter(Objects::nonNull).toList());
        return points.isEmpty() ? "none" : points;
    }

    private static List<Object> list(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return new ArrayList<>(l);
    }

    private static String quoted(Object value) {
        return value == null ? "null" : value instanceof String s ? "\"" + s + "\"" : String.valueOf(value);
    }
}
