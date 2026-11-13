package ro.kf.sdk;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The typed {@code kf-module-conf.json} (manifest v2; {@code schemaVersion} 2):
 *
 * <pre>{@code
 * {
 *   "schemaVersion": 2, "slug": "api-test", "name": "module.name", "description": "module.description",
 *   "version": "0.5.0", "rollbackFloor": "0.4.0", "platform": ">=4.1",
 *   "featureFlags": [ { "key": "author-tab", "label": "flag.author-tab.label", "default": false } ],
 *   "requires": [ { "slug": "other", "versionRange": ">=1.0.0" } ],
 *   "i18n": { "en": { "module.name": "API Test", ... }, "ro": { ... } },
 *   "capabilities": {
 *     "fields":        [ { "entity": "order", "name": "priority_code", "type": "STRING", "table": "orders_extra", "label": "field.priority", "editable": true } ],
 *     "hooks":         [ { "point": "model.before.update", "onFailure": "block" } ],
 *     "routes":        [ { "method": "POST", "path": "/m/api-test/api/notes", "right": "api.v1.m.api-test.notes.post", "roles": ["QC"], "hookable": true },
 *                        { "method": "PUT", "path": "/m/api-test/admin/keys", "right": "api.v1.m.api-test.admin.keys.put", "admin": "platform" } ],
 *     "surfaces":      [ { "slug": "models.detail.tabs", "kind": "react", "label": "tab.label" } ],
 *     "notifications": [ { "key": "author-changed", "variables": ["modelName"], "channels": ["in_app"], "subject": "n.s", "body": "n.b" } ],
 *     "reads":         [ { "port": "<published id>" } ],
 *     "audit":         [ { "access": "write" }, { "access": "read-own" } ],
 *     "nav":           [ { "surface": "staff", "group": "admin-utils", "key": "notes", "label": "nav.notes", "url": "/modules", "icon": "Boxes", "right": "api.v1.m.api-test.notes.post", "order": 20, "flag": "author-tab" } ],
 *     "settings":      [ { "key": "push-on-save", "type": "BOOLEAN", "default": false, "label": "setting.push" } ],
 *     "secrets":       [ { "name": "r3-client-secret", "scope": "platform", "writeRight": "api.v1.m.api-test.admin.keys.put", "external": ["platform/aas/"] } ],
 *     "provides":      [ { "port": "factory.stations.v1", "path": "/m/api-test/stations" } ],
 *     "storage":       [ { "contentTypes": ["image/jpeg", "image/png"], "maxBytes": 5242880 } ],
 *     "slots":         [ { "key": "viewport.layers.v1", "kind": "B", "type": "layer", "visibility": "public", "layerKinds": ["pin"] } ]
 *   },
 *   "activation": "tenant"
 * }
 * }</pre>
 *
 * <p><strong>Reading is lenient, checking is not.</strong> This class reads what it can and never throws on a shape
 * error: an absent list is {@code []}, a non-list is {@code []}, {@code roles} absent is {@code []} (no role),
 * {@code hookable} and {@code editable} absent or not a boolean are {@code false}. Every shape error is reported by {@link ManifestCheck},
 * which works on the raw JSON, so a manifest the check refuses never reaches the platform.
 *
 * <p>Two capabilities are <strong>declared in protocol 1.0</strong> (operator decisions of 2026-10-19,
 * DECIZII-2026-10-16):
 * <ul>
 *   <li>{@code routes[].hookable}: a route a platform hook may call as the system; boolean, default
 *       {@code false}, only on {@code POST}/{@code PUT}/{@code PATCH}. Delivered by the platform (the strict
 *       check at upload, the approval badge, the catalog of hookable routes and the dispatch); a platform before it
 *       ignores the key.</li>
 *   <li>{@code audit}: {@code [{"access": "write" | "read-own"}]}, each at most once. Declared in protocol 1.0,
 *       delivered by the platform (accepted at upload and shown at approval); an older platform
 *       refuses it as an unknown capability.</li>
 * </ul>
 *
 * <p><strong>Added in protocol 1.1</strong> (additive, no new {@code schemaVersion}):
 * {@code nav}, the module's menu entries on the staff sidebar, the customer portal menu or the platform menu
 * ({@link NavCapability}). An older platform refuses the key at upload (an unknown capability).
 *
 * <p><strong>Added in protocol 1.1</strong> (additive, delivered by the
 * platform in a later release for each, sdk-protocol.md §15): {@code settings} ({@link SettingCapability}: closed
 * scalars only, the rule "settings carry no data"), {@code routes[].admin} ({@link RouteCapability#admin()}), {@code secrets} ({@link SecretCapability}: names and scopes, values only by reference),
 * {@code provides} ({@link ProvideCapability}: reads the module serves to the platform) and
 * {@code surfaces[].page} ({@link SurfaceCapability#page()}). A port id ({@code reads[].port},
 * {@code provides[].port}) matches {@link #PORT_ID}. Absent keys read as {@code []} / {@code null}: a 1.0
 * manifest reads unchanged.
 *
 * <p><strong>Added in protocol 1.2</strong> (additive, no new {@code schemaVersion},
 * delivered by the platform in a later release for each, sdk-protocol.md §1): {@code routes[].audience} and
 * {@code routes[].portal} ({@link RouteCapability#audience()}, {@link RouteCapability#portal()}), the
 * top-level {@code activation} ({@link Activation}) and {@code storage} ({@link StorageCapability}).
 * Absent keys read as {@code null} / {@code false} / {@code []} / {@link Activation#TENANT}: a 1.1 manifest reads
 * unchanged.
 *
 * <p><strong>Added in protocol 2.0</strong> ({@code schemaVersion} 3, a strict
 * superset of 2): {@code slots} ({@link SlotCapability}: the slots a module defines for other modules,
 * sdk-protocol.md §7.12). Refused under {@code schemaVersion} 1–2 by {@link ManifestCheck}; absent reads as
 * {@code []}.
 *
 * @param schemaVersion the declared {@code schemaVersion}, {@code 1} when absent
 * @param slug          the module identity
 * @param name          the name; a translation key under {@code schemaVersion} 2
 * @param description   optional: what the module does, shown by the platform when present; the same
 *                      shape as {@code name} (a translation key under {@code schemaVersion} 2); {@code null} when
 *                      absent
 * @param version       the module's semver
 * @param rollbackFloor optional: the oldest version of the module that runs correctly on the
 *                      data (the schema of the module's own database) this version leaves behind; a semver at or below
 *                      {@code version}, {@code null} when absent (or not a string). Version V declares
 *                      {@code rollbackFloor = F} when every version &ge; F of the module runs correctly on the data left
 *                      by V; between F and V the migrations of the module's own database are only additive (expand).
 *                      The platform computes H = the highest version ever installed and refuses a rollback to a
 *                      version below {@code rollbackFloor(H)}; when H declares no floor, the floor is H itself
 *                      (sdk-protocol.md §3.1, §12)
 * @param author        optional
 * @param platform      the platform versions the module runs on (mandatory under 2)
 * @param featureFlags  the declared flags
 * @param requires      the modules this one depends on
 * @param i18n          {@code language -> (key -> text)}; {@code en} mandatory under 2
 * @param capabilities  what the module asks of the platform; never null
 * @param activation    protocol 1.2: per tenant (the default) or on the whole platform; never null
 */
public record Manifest(int schemaVersion, String slug, String name, String description, String version,
                       String rollbackFloor, String author, String platform,
                       List<FeatureFlag> featureFlags, List<Requirement> requires,
                       Map<String, Map<String, String>> i18n, Capabilities capabilities, Activation activation) {

    public Manifest {
        activation = activation == null ? Activation.TENANT : activation;
    }

    /** A manifest without {@code activation} (the shape before SDK 0.8.0): activated per tenant. */
    public Manifest(int schemaVersion, String slug, String name, String description, String version,
                    String rollbackFloor, String author, String platform, List<FeatureFlag> featureFlags,
                    List<Requirement> requires, Map<String, Map<String, String>> i18n, Capabilities capabilities) {
        this(schemaVersion, slug, name, description, version, rollbackFloor, author, platform, featureFlags, requires,
                i18n, capabilities, Activation.TENANT);
    }

    /** The capability keys this SDK knows, in the documented order ({@code audit}, delivered). */
    public static final List<String> CAPABILITY_KEYS =
            List.of("fields", "hooks", "routes", "surfaces", "notifications", "reads", "audit", "nav", "settings",
                    "secrets", "provides", "storage", "slots", "peerReads", "commands", "events", "peers", "flowRules",
                    "services", "flows");

    /**
     * The keys of {@code capabilities.flowRules[]} (protocol 2.0, sdk-protocol.md §16.4; a closed object). Delivered by
     * the platform with {@code FLOW_RULES}; a platform before it refuses the key at upload. The SDK checks only
     * the grammar (the platform is the gate).
     */
    public static final List<String> FLOW_RULE_KEYS = List.of("key", "path", "timeoutSeconds", "projection");
    /** The keys of {@code capabilities.services[]} (protocol 2.0, §16.6; a closed object). */
    public static final List<String> SERVICE_KEYS = List.of("port");
    /** The only projection of a flow rule in protocol 2.0 (§16.4): the major is in the key. */
    public static final String FLOW_RULE_PROJECTION = "order.flow-rule.v1";
    /** The only port of {@code capabilities.services} in protocol 2.0 (§16.6). */
    public static final String SERVICES_PORT = "order.services.v1";
    /** The longest a flow rule may take, in seconds (§16.4). */
    public static final int MAX_FLOW_RULE_TIMEOUT_SECONDS = 60;
    /** How long the engine waits for a flow rule that declares no {@code timeoutSeconds} (§16.4). */
    public static final int DEFAULT_FLOW_RULE_TIMEOUT_SECONDS = 2;
    /** The surface of a module step in the order flow (§16.5): its contribution names the step in {@code key}. */
    public static final String ORDERFLOW_STEP = "orderflow.step";

    /**
     * The keys of {@code capabilities.flows[]} (protocol 2.1, sdk-protocol.md §17; a closed object): the
     * module's baseline flows, a BPMN file each, in the pack. Delivered by the platform with {@code MODULE_FLOWS}; until
     * then a platform refuses the key at upload (§16.7). The SDK checks only the grammar and, on a pack, the files.
     */
    public static final List<String> FLOW_KEYS = List.of("key", "file", "name");
    /** The key of a module flow (§17.1): the key of the global flow definition the platform makes of it. */
    public static final String FLOW_KEY = "^[a-z][a-z0-9-]{2,62}$";
    /** The file of a module flow (§17.1): directly under {@code flows/} in the pack, a {@code .bpmn}. */
    public static final String FLOW_FILE = "^flows/[a-z0-9][a-z0-9._-]{0,99}\\.bpmn$";
    /** The directory of the module flows in the pack (§17.2). */
    public static final String FLOWS_DIRECTORY = "flows/";
    /** The largest BPMN file of a module flow, in bytes (§17.2): 256 KiB. */
    public static final long MAX_FLOW_FILE_BYTES = 262_144L;
    /** The most flows one module declares (§17.1). */
    public static final int MAX_FLOWS = 20;

    /** The visibilities of a port, a command or an event offered to other modules (protocol 2.0, required). */
    public static final List<String> PEER_VISIBILITIES = List.of("internal", "public");

    /** The methods of a command between modules (protocol 2.0, sdk-protocol.md §15.9). */
    public static final List<String> COMMAND_METHODS = List.of("POST", "PUT", "DELETE");

    /** The keys of {@code capabilities.peerReads[]} (a closed object). */
    public static final List<String> PEER_READ_KEYS = List.of("port", "path", "visibility");
    /** The keys of {@code capabilities.commands[]} (a closed object). */
    public static final List<String> COMMAND_KEYS = List.of("name", "path", "method", "visibility");
    /** The keys of {@code capabilities.events[]} (a closed object). */
    public static final List<String> EVENT_KEYS = List.of("name", "visibility");
    /** The keys of {@code capabilities.peers[]} (a closed object). */
    public static final List<String> PEER_USE_KEYS = List.of("owner", "reads", "commands");

    /**
     * The grammar of a port id (protocol 1.1): an entity, one optional sub-segment, a major version
     * ({@code model.v1}, {@code file.public-url.v1}, {@code factory.stations.v1}).
     */
    public static final String PORT_ID = "^[a-z][a-z0-9-]{1,40}(\\.[a-z][a-z0-9-]{1,40})?\\.v[0-9]+$";

    /** The types of a setting: closed scalars only, settings carry no data (protocol 1.1). */
    public static final List<String> SETTING_TYPES = List.of("BOOLEAN", "NUMBER", "ENUM");

    /** The keys of a setting: a closed object. */
    public static final List<String> SETTING_KEYS =
            List.of("key", "type", "default", "label", "description", "values", "min", "max");

    /** At most this many settings per module (protocol 1.1). */
    public static final int MAX_SETTINGS = 30;

    /** The values of {@code routes[].admin} (protocol 1.1). */
    public static final List<String> ROUTE_ADMIN = List.of("tenant", "platform");

    /**
     * The longest a route may ask the platform to wait for its answer ({@code routes[].timeoutSeconds}, SDK 0.7.3): a whole number of seconds from 1 to this cap; absent = the platform's default (5 s).
     */
    public static final int MAX_ROUTE_TIMEOUT_SECONDS = 60;

    /**
     * The one value of {@code routes[].audience} (protocol 1.2): any member of the active
     * tenant, with any role, calls the route; absent = an ordinary route (the caller holds its right).
     */
    public static final String ROUTE_AUDIENCE_MEMBER = "member";

    /** The keys of a {@code storage} element: a closed object (protocol 1.2). */
    public static final List<String> STORAGE_KEYS = List.of("contentTypes", "maxBytes");

    /** At most this many {@code storage} elements (protocol 1.2). */
    public static final int MAX_STORAGE_ELEMENTS = 1;

    /** At most this many content types in one {@code storage} element (protocol 1.2). */
    public static final int MAX_STORAGE_CONTENT_TYPES = 10;

    /** The largest object a module may declare in {@code storage[].maxBytes}: 10 MiB (protocol 1.2). */
    public static final int MAX_STORAGE_BYTES = 10_485_760;

    /**
     * One content type of {@code storage[].contentTypes} (protocol 1.2): {@code type/subtype} in lower case, the
     * characters of an RFC 6838 restricted name, without parameters and without a wildcard ({@code image/png},
     * {@code image/svg+xml}).
     */
    public static final String STORAGE_CONTENT_TYPE = "^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}$";

    /**
     * How a module is activated (protocol 1.2; the top-level {@code activation}): per tenant
     * ({@code "tenant"}, the default when absent or {@code null}) or on the whole platform ({@code "platform"}: live in
     * every tenant, existing and new, while it is installed with an active version, started, not tampered and not
     * deactivated; no per-tenant activation is asked for or possible).
     */
    public enum Activation {
        TENANT("tenant"),
        PLATFORM("platform");

        private final String wire;

        Activation(String wire) {
            this.wire = wire;
        }

        /** The value in the manifest. */
        public String wire() {
            return wire;
        }

        /** The activation of a manifest value; {@code null} for a value that is not one (the check reports it). */
        public static Activation of(Object value) {
            for (Activation a : values()) if (a.wire.equals(value)) return a;
            return null;
        }

        /** The value in the manifest (what a JSON view of the typed manifest shows). */
        @Override
        public String toString() {
            return wire;
        }
    }

    /** The scopes of a secret (protocol 1.1). */
    public static final List<String> SECRET_SCOPES = List.of("platform", "tenant");

    /** The keys of a secret: a closed object (a value never travels in the manifest). */
    public static final List<String> SECRET_KEYS = List.of("name", "scope", "writeRight", "external");

    /**
     * One prefix of {@code secrets[].external} (SDK 0.7.2): a vault path -- the mount and at
     * least one segment more -- ending with {@code /} ({@code platform/aas/}).
     */
    public static final String SECRET_EXTERNAL_PREFIX = "^[a-z0-9][a-z0-9_-]{0,63}(/[a-z0-9][a-z0-9_-]{0,63})+/$";

    /** At most this many prefixes in one secret's {@code external}. */
    public static final int SECRET_MAX_EXTERNAL = 5;

    /** The keys of a provided read: a closed object. */
    public static final List<String> PROVIDE_KEYS = List.of("port", "path");

    /** The menus a {@code nav} entry may join (protocol 1.1). */
    public static final List<String> NAV_SURFACES = List.of("staff", "customer", "platform");

    /** The keys of a {@code nav} entry: a closed object. */
    public static final List<String> NAV_KEYS =
            List.of("surface", "group", "key", "label", "url", "icon", "right", "order", "flag");

    /** The position of a {@code nav} entry in its group when {@code order} is absent (as {@code surfaces}, §7.7). */
    public static final int NAV_DEFAULT_ORDER = 100;

    /** The methods a {@code hookable} route may have. */
    public static final List<String> HOOKABLE_METHODS = List.of("POST", "PUT", "PATCH");

    /** The values of {@code audit[].access}. */
    public static final List<String> AUDIT_ACCESS = List.of("write", "read-own");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One declared feature flag; {@code default} is false when omitted. */
    public record FeatureFlag(String key, String label, String description, boolean defaultValue,
                              List<String> requiresModules) {
        /**
         * Protocol 2.0 ({@code schemaVersion} 3): {@code requiresModules} -- the flag is on for a
         * tenant only while each of these modules (declared in {@code requires}) is live there; never null.
         */
        public FeatureFlag {
            requiresModules = requiresModules == null ? List.of() : List.copyOf(requiresModules);
        }

        /** A flag without {@code requiresModules} (the shape before SDK 0.10.0). */
        public FeatureFlag(String key, String label, String description, boolean defaultValue) {
            this(key, label, description, defaultValue, List.of());
        }
    }

    /**
     * One declared dependency. {@code optional} (protocol 2.0, {@code schemaVersion} 3): an optional
     * dependency blocks nothing in either direction -- absent, stopped or outside its range it counts as absent; a
     * platform before protocol 2.0 reads every dependency as mandatory. Absent or not {@code true} = mandatory.
     */
    public record Requirement(String slug, String versionRange, boolean optional) {

        /** A mandatory dependency (the shape before SDK 0.10.0). */
        public Requirement(String slug, String versionRange) {
            this(slug, versionRange, false);
        }
    }

    /** The {@code capabilities} block; every list is never null. */
    public record Capabilities(List<FieldCapability> fields, List<HookCapability> hooks, List<RouteCapability> routes,
                               List<SurfaceCapability> surfaces, List<NotificationCapability> notifications,
                               List<ReadCapability> reads, List<AuditCapability> audit, List<NavCapability> nav,
                               List<SettingCapability> settings, List<SecretCapability> secrets,
                               List<ProvideCapability> provides, List<StorageCapability> storage,
                               List<SlotCapability> slots, List<PeerReadCapability> peerReads,
                               List<CommandCapability> commands, List<EventCapability> events,
                               List<PeerUseCapability> peers, List<FlowRuleCapability> flowRules,
                               List<ServiceCapability> services, List<FlowCapability> flows) {

        public Capabilities {
            storage = storage == null ? List.of() : List.copyOf(storage);
            slots = slots == null ? List.of() : List.copyOf(slots);
            peerReads = peerReads == null ? List.of() : List.copyOf(peerReads);
            commands = commands == null ? List.of() : List.copyOf(commands);
            events = events == null ? List.of() : List.copyOf(events);
            peers = peers == null ? List.of() : List.copyOf(peers);
            flowRules = flowRules == null ? List.of() : List.copyOf(flowRules);
            services = services == null ? List.of() : List.copyOf(services);
            flows = flows == null ? List.of() : List.copyOf(flows);
        }

        /** The capabilities without {@code flows} (the shape of protocol 2.0, before 2.1). */
        public Capabilities(List<FieldCapability> fields, List<HookCapability> hooks, List<RouteCapability> routes,
                            List<SurfaceCapability> surfaces, List<NotificationCapability> notifications,
                            List<ReadCapability> reads, List<AuditCapability> audit, List<NavCapability> nav,
                            List<SettingCapability> settings, List<SecretCapability> secrets,
                            List<ProvideCapability> provides, List<StorageCapability> storage,
                            List<SlotCapability> slots, List<PeerReadCapability> peerReads,
                            List<CommandCapability> commands, List<EventCapability> events,
                            List<PeerUseCapability> peers, List<FlowRuleCapability> flowRules,
                            List<ServiceCapability> services) {
            this(fields, hooks, routes, surfaces, notifications, reads, audit, nav, settings, secrets, provides, storage,
                    slots, peerReads, commands, events, peers, flowRules, services, List.of());
        }

        /** The capabilities without the keys of the order flow (the shape of wave V2–V3). */
        public Capabilities(List<FieldCapability> fields, List<HookCapability> hooks, List<RouteCapability> routes,
                            List<SurfaceCapability> surfaces, List<NotificationCapability> notifications,
                            List<ReadCapability> reads, List<AuditCapability> audit, List<NavCapability> nav,
                            List<SettingCapability> settings, List<SecretCapability> secrets,
                            List<ProvideCapability> provides, List<StorageCapability> storage,
                            List<SlotCapability> slots, List<PeerReadCapability> peerReads,
                            List<CommandCapability> commands, List<EventCapability> events,
                            List<PeerUseCapability> peers) {
            this(fields, hooks, routes, surfaces, notifications, reads, audit, nav, settings, secrets, provides, storage,
                    slots, peerReads, commands, events, peers, List.of(), List.of());
        }

        /** The capabilities without the declarations between modules (the shape of wave V1). */
        public Capabilities(List<FieldCapability> fields, List<HookCapability> hooks, List<RouteCapability> routes,
                            List<SurfaceCapability> surfaces, List<NotificationCapability> notifications,
                            List<ReadCapability> reads, List<AuditCapability> audit, List<NavCapability> nav,
                            List<SettingCapability> settings, List<SecretCapability> secrets,
                            List<ProvideCapability> provides, List<StorageCapability> storage,
                            List<SlotCapability> slots) {
            this(fields, hooks, routes, surfaces, notifications, reads, audit, nav, settings, secrets, provides, storage,
                    slots, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        /** The capabilities without {@code slots} (the shape before SDK 0.10.0, protocol 2.0). */
        public Capabilities(List<FieldCapability> fields, List<HookCapability> hooks, List<RouteCapability> routes,
                            List<SurfaceCapability> surfaces, List<NotificationCapability> notifications,
                            List<ReadCapability> reads, List<AuditCapability> audit, List<NavCapability> nav,
                            List<SettingCapability> settings, List<SecretCapability> secrets,
                            List<ProvideCapability> provides, List<StorageCapability> storage) {
            this(fields, hooks, routes, surfaces, notifications, reads, audit, nav, settings, secrets, provides, storage,
                    List.of());
        }

        /** The capabilities without {@code storage} (the shape before SDK 0.8.0). */
        public Capabilities(List<FieldCapability> fields, List<HookCapability> hooks, List<RouteCapability> routes,
                            List<SurfaceCapability> surfaces, List<NotificationCapability> notifications,
                            List<ReadCapability> reads, List<AuditCapability> audit, List<NavCapability> nav,
                            List<SettingCapability> settings, List<SecretCapability> secrets,
                            List<ProvideCapability> provides) {
            this(fields, hooks, routes, surfaces, notifications, reads, audit, nav, settings, secrets, provides, List.of(),
                    List.of());
        }
    }

    /**
     * A field on an extensible entity ({@code model}, {@code order}, {@code customer}).
     *
     * @param editable whether the HOST may render the field generically, when no live contribution of the module is
     *                 mounted on the form; absent = {@code false}. A rule of rendering, not of
     *                 writing: a field that is not editable is still written by the module's own contribution, a hook
     *                 patch or the module's API
     */
    public record FieldCapability(String entity, String name, String type, String table, String label, boolean editable) {
    }

    /** A hook point; {@code onFailure} {@code allow} (default) or {@code block}, only on before points. */
    public record HookCapability(String point, String onFailure) {
    }

    /**
     * An API route the module serves under {@code /m/{slug}/}.
     *
     * @param roles    global role names the right is proposed for; never null, absent = {@code []}
     * @param hookable whether a platform hook may call it as the system; absent = {@code false}
     * @param admin    protocol 1.1: {@code tenant} (the platform also binds the right to TENANT_ADMIN in
     *                 the tenants where the module is active) or {@code platform} ({@code roles} empty: only the
     *                 platform administrator holds it); never on a {@code hookable} route; {@code null} when absent
     * @param timeoutSeconds SDK 0.7.3: how long the platform waits for the module's answer
     *                 on this route, 1 to {@value Manifest#MAX_ROUTE_TIMEOUT_SECONDS} seconds; {@code null} when absent
     *                 (the platform's default, 5 s)
     * @param audience protocol 1.2: {@value Manifest#ROUTE_AUDIENCE_MEMBER} -- any member
     *                 of the active tenant calls the route, with any role (the rights filter checks the membership and
     *                 skips the match of the right); never with {@code roles}, {@code admin} or {@code hookable};
     *                 {@code null} when absent (an ordinary route)
     * @param portal   protocol 1.2: the route is also served on the customer portal, at
     *                 {@code /api/customer/v1/m/{slug}{rest of the path}}, with the customer's tenant; only on
     *                 {@code audience: member}; absent = {@code false}
     */
    public record RouteCapability(String method, String path, String right, List<String> roles, boolean hookable,
                                  String admin, Integer timeoutSeconds, String audience, boolean portal) {

        /** A route without {@code audience} and {@code portal} (the shape before SDK 0.8.0). */
        public RouteCapability(String method, String path, String right, List<String> roles, boolean hookable,
                               String admin, Integer timeoutSeconds) {
            this(method, path, right, roles, hookable, admin, timeoutSeconds, null, false);
        }

        /** A route without {@code timeoutSeconds} (the shape before SDK 0.7.3). */
        public RouteCapability(String method, String path, String right, List<String> roles, boolean hookable,
                               String admin) {
            this(method, path, right, roles, hookable, admin, null, null, false);
        }
    }

    /**
     * A UI surface the module contributes to.
     *
     * @param page protocol 1.1: on a surface of type {@code page} (the host's catalog), the
     *             module page opened at {@code /m/{slug}/{page}}, {@code ^[a-z][a-z0-9-]{0,40}$}; {@code null} when
     *             absent
     * @param key  protocol 2.0 (§16.5): on {@link #ORDERFLOW_STEP}, the key of the step the contribution renders
     *             (mandatory there); {@code null} when absent
     */
    public record SurfaceCapability(String slug, String kind, String label, String page, String key) {

        /** A surface without {@code key} (the shape before SDK 0.10.0). */
        public SurfaceCapability(String slug, String kind, String label, String page) {
            this(slug, kind, label, page, null);
        }
    }

    /** A notification the module may send. */
    public record NotificationCapability(String key, List<String> variables, List<String> channels, String subject,
                                         String body) {
    }

    /** A read through a port the platform publishes. */
    public record ReadCapability(String port, String access) {
    }

    /**
     * One access to the platform's audit trail: {@code write} (rows in the request's tenant,
     * attributed to the module) or {@code read-own} (only the module's own rows). Declared in protocol 1.0,
     * implemented by the platform ({@link AuditClient}).
     */
    public record AuditCapability(String access) {
    }

    /**
     * One menu entry of the module (protocol 1.1): the platform composes it into the menu of
     * {@code surface} while the module is live there, under the public key {@code m.{slug}.{key}}.
     *
     * @param surface {@code staff} (the sidebar), {@code customer} (the portal) or {@code platform} ({@code /setup})
     * @param group   the key of a group of that menu (the platform's list, checked at upload); the SDK checks only the
     *                shape {@code ^[a-z][a-z0-9-]{0,40}$}
     * @param key     {@code ^[a-z][a-z0-9-]{0,40}$}, unique per module and surface
     * @param label   a key of {@code i18n.en}, resolved by the host in the user's language
     * @param url     an in-app path: starts with {@code /}, without {@code //}, {@code ..}, {@code %}, a backslash,
     *                whitespace, a scheme, a query or a fragment
     * @param icon    optional, the name of an icon of the host's registry (PascalCase); {@code null} when absent
     * @param right   optional, the right of one of the module's own routes or a code the platform declares; never on
     *                {@code customer}; {@code null} when absent. Presentation only: the target route keeps its own gate
     * @param order   0 to 10000, {@value #NAV_DEFAULT_ORDER} when absent
     * @param flag    optional, one of the module's {@code featureFlags}: the entry is shown only while it is on;
     *                {@code null} when absent
     */
    public record NavCapability(String surface, String group, String key, String label, String url, String icon,
                                String right, int order, String flag) {
    }

    /**
     * One setting of the module (protocol 1.1): a closed scalar the TENANT_ADMIN
     * edits per tenant in the module's area, read by the module with {@code GET .../settings}. Settings carry no data:
     * {@code BOOLEAN}, {@code NUMBER} or {@code ENUM} only (no free text, list, reference or secret); data lives on
     * surfaces.
     *
     * @param key          {@code ^[a-z][a-z0-9-]{1,40}$}, unique
     * @param type         {@code BOOLEAN}, {@code NUMBER} or {@code ENUM}
     * @param defaultValue the JSON {@code default}: a boolean, a number in {@code min..max}, or one of {@code values}
     * @param label        a key of {@code i18n.en}
     * @param description  optional, a key of {@code i18n.en}; {@code null} when absent
     * @param values       the closed values of an {@code ENUM}; {@code []} otherwise
     * @param min          optional lower bound of a {@code NUMBER}; {@code null} when absent
     * @param max          optional upper bound of a {@code NUMBER}; {@code null} when absent
     */
    public record SettingCapability(String key, String type, Object defaultValue, String label, String description,
                                    List<String> values, Number min, Number max) {
    }

    /**
     * One secret of the module (protocol 1.1): the manifest names it, never its value. The
     * platform stores the value in its vault under the module's path and gives the module a {@code vault://} reference.
     *
     * @param name       {@code ^[a-z][a-z0-9-]{1,40}$}, unique
     * @param scope      {@code platform} (one value) or {@code tenant} (one value per tenant)
     * @param writeRight the right of one of the module's routes; a {@code platform} secret only through a route with
     *                   {@code admin: platform}
     * @param external   the prefixes ({@code platform/aas/}) under which the module may keep an EXISTING reference
     *                   {@code vault://<prefix>…} for this secret instead of a value it writes; the platform resolves
     *                   it and audits every resolve (SDK 0.7.2); only on a {@code platform}
     *                   secret; absent = {@code []}
     */
    public record SecretCapability(String name, String scope, String writeRight, List<String> external) {
        public SecretCapability {
            external = external == null ? List.of() : List.copyOf(external);
        }

        /** A secret without {@code external}. */
        public SecretCapability(String name, String scope, String writeRight) {
            this(name, scope, writeRight, List.of());
        }
    }

    /**
     * One read the module serves to the platform (protocol 1.1): the platform calls
     * {@code path} (a GET route of the module) with the actor {@code {type: system, via: platform}}.
     *
     * @param port a port of the platform's catalog of provided reads ({@link #PORT_ID}), e.g. {@code factory.stations.v1}
     * @param path the path of a GET route this module declares
     */
    public record ProvideCapability(String port, String path) {
    }

    /**
     * The module's storage of files through the platform (protocol 1.2; sdk-protocol.md
     * §15.7): the platform presigns an upload under {@code kf-modules/{slug}/{tenant}/} and a download of a key there,
     * and purges the prefix when the tenant is deleted. At most {@value Manifest#MAX_STORAGE_ELEMENTS} element.
     *
     * @param contentTypes the types the module uploads: 1 to {@value Manifest#MAX_STORAGE_CONTENT_TYPES} distinct
     *                     {@code type/subtype} ({@link Manifest#STORAGE_CONTENT_TYPE}), never a wildcard; never null
     * @param maxBytes     the largest object, 1 to {@value Manifest#MAX_STORAGE_BYTES} bytes; {@code null} when absent
     *                     or not a whole number that fits an {@code int} (the check reports it)
     */
    public record StorageCapability(List<String> contentTypes, Integer maxBytes) {
        public StorageCapability {
            contentTypes = contentTypes == null ? List.of() : List.copyOf(contentTypes);
        }
    }

    /**
     * The grammar of a slot key (protocol 2.0, sdk-protocol.md §7.12): dot-separated lower-case segments,
     * the major as the last segment ({@code viewport.layers.v1}); at most {@value #MAX_SLOT_KEY_LENGTH} characters.
     * The public key is {@code m.{owner}.{key}}.
     */
    public static final String SLOT_KEY = "^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*\\.v[1-9][0-9]*$";

    /** The longest slot key (protocol 2.0). */
    public static final int MAX_SLOT_KEY_LENGTH = 80;

    /** The keys of a slot: a closed object (protocol 2.0, §7.12). */
    public static final List<String> SLOT_KEYS = List.of("key", "kind", "type", "visibility", "label", "publicMembers",
            "actions", "layerKinds", "events", "commands", "dataSchema");

    /**
     * The kind of a slot (protocol 2.0, sdk-protocol.md §7.12): {@code A} UI panels, {@code B} data layers
     * rendered by the owner, {@code D} events and commands in the page, {@code R} catalog resources (data only). There
     * is no kind C (foreign code in the owner's WebGL context).
     */
    public enum SlotKind {
        A(List.of("extension", "tab", "action"), List.of("publicMembers", "actions")),
        B(List.of("layer"), List.of("layerKinds")),
        D(List.of("events", "commands"), List.of("events", "commands")),
        R(List.of("resource"), List.of("dataSchema"));

        private final List<String> types;
        private final List<String> ownKeys;

        SlotKind(List<String> types, List<String> ownKeys) {
            this.types = types;
            this.ownKeys = ownKeys;
        }

        /** The types a slot of this kind may declare. */
        public List<String> types() {
            return types;
        }

        /** The keys only this kind may carry. */
        public List<String> ownKeys() {
            return ownKeys;
        }

        /** The kind of a manifest value; {@code null} for a value that is not one (the check reports it). */
        public static SlotKind of(Object value) {
            for (SlotKind k : values()) if (k.name().equals(value)) return k;
            return null;
        }
    }

    /**
     * Who may contribute to a slot (protocol 2.0), chosen by the owner, without a default: {@code internal} only
     * the modules of the class {@code KF_RELEASE}, {@code public} any module that requires the owner.
     */
    public enum SlotVisibility {
        INTERNAL("internal"),
        PUBLIC("public");

        private final String wire;

        SlotVisibility(String wire) {
            this.wire = wire;
        }

        /** The value in the manifest. */
        public String wire() {
            return wire;
        }

        /** The visibility of a manifest value; {@code null} for a value that is not one (the check reports it). */
        public static SlotVisibility of(Object value) {
            for (SlotVisibility v : values()) if (v.wire.equals(value)) return v;
            return null;
        }

        /** The value in the manifest (what a JSON view of the typed manifest shows). */
        @Override
        public String toString() {
            return wire;
        }
    }

    /**
     * One slot the module defines for other modules (protocol 2.0, {@code schemaVersion} 3 only, sdk-protocol.md
     * §7.12). A contributor declares {@code surfaces[].slug = "m.{owner}.{key}"}.
     *
     * @param key           the versioned key ({@link #SLOT_KEY}), the major as the last segment
     * @param kind          A, B, D or R; {@code null} when absent or unknown (the check reports it)
     * @param type          one of the kind's types ({@link SlotKind#types()})
     * @param visibility    {@code internal} or {@code public}; {@code null} when absent (the check refuses it)
     * @param label         optional, a key of {@code i18n.en}; {@code null} when absent
     * @param publicMembers A: the {@code ctx} members shown to the contributor; never null
     * @param actions       A: the actions given to the contributor; never null
     * @param layerKinds    B: the layer kinds the owner renders; never null
     * @param events        D ({@code type: events}): the event names; never null
     * @param commands      D ({@code type: commands}): the command names; never null
     * @param dataSchema    R: the schema id {@code m.{owner}.{name}.v{N}} the owner validates the data against;
     *                      {@code null} otherwise. Not {@code schema}: that key is forbidden in a manifest (§3.3)
     */
    public record SlotCapability(String key, SlotKind kind, String type, SlotVisibility visibility, String label,
                                 List<String> publicMembers, List<String> actions, List<String> layerKinds,
                                 List<String> events, List<String> commands, String dataSchema) {
        public SlotCapability {
            publicMembers = publicMembers == null ? List.of() : List.copyOf(publicMembers);
            actions = actions == null ? List.of() : List.copyOf(actions);
            layerKinds = layerKinds == null ? List.of() : List.copyOf(layerKinds);
            events = events == null ? List.of() : List.copyOf(events);
            commands = commands == null ? List.of() : List.copyOf(commands);
        }
    }

    /**
     * A read this module OFFERS to other modules (protocol 2.0, {@code capabilities.peerReads[]}, sdk-protocol.md §15.9).
     *
     * @param port       {@code m.{self}.{name}.v{N}}
     * @param path       a {@code GET} route this manifest declares
     * @param visibility {@code internal} (the internal class only) or {@code public}
     */
    public record PeerReadCapability(String port, String path, String visibility) {
    }

    /** A command this module OFFERS ({@code capabilities.commands[]}, §15.9): {@code method path} a declared route. */
    public record CommandCapability(String name, String path, String method, String visibility) {
    }

    /** An event this module EMITS ({@code capabilities.events[]}, §6.3): the point {@code m.{self}.after.{name}}. */
    public record EventCapability(String name, String visibility) {
    }

    /**
     * A rule of a module step in the order flow (protocol 2.0, {@code capabilities.flowRules[]}, sdk-protocol.md §16.4): the engine calls {@code GET {path}?<order.flow-rule.v1>} before the step advances.
     *
     * @param key            {@code ^[a-z][a-z0-9-]{1,40}$}, unique
     * @param path           a {@code GET} route this manifest declares
     * @param timeoutSeconds 1..{@value #MAX_FLOW_RULE_TIMEOUT_SECONDS}; {@code null} = the default
     *                       {@value #DEFAULT_FLOW_RULE_TIMEOUT_SECONDS} s
     * @param projection     {@value #FLOW_RULE_PROJECTION}
     */
    public record FlowRuleCapability(String key, String path, Integer timeoutSeconds, String projection) {

        /** {@link #timeoutSeconds()}, or the default when absent. */
        public int effectiveTimeoutSeconds() {
            return timeoutSeconds == null ? DEFAULT_FLOW_RULE_TIMEOUT_SECONDS : timeoutSeconds;
        }
    }

    /** A platform service this module calls ({@code capabilities.services[]}, §16.6): {@value #SERVICES_PORT}. */
    public record ServiceCapability(String port) {
    }

    /**
     * A baseline flow this module brings (protocol 2.1, {@code capabilities.flows[]}, sdk-protocol.md §17):
     * a BPMN file in the pack that the platform compiles at upload and approval and publishes as a global flow
     * ({@code origin = module}) at install and upgrade, visible to the tenants where the module is active.
     *
     * @param key  {@value #FLOW_KEY}, unique; the key of the global definition (the platform does not prefix it, so it
     *             SHOULD start with the module's slug)
     * @param file {@code flows/<name>.bpmn}, directly under {@value #FLOWS_DIRECTORY}, at most
     *             {@value #MAX_FLOW_FILE_BYTES} bytes
     * @param name a key of {@code i18n.en}: the flow's name
     */
    public record FlowCapability(String key, String file, String name) {
    }

    /** What this module CALLS of another one ({@code capabilities.peers[]}, §15.9); the owner is in {@code requires}. */
    public record PeerUseCapability(String owner, List<String> reads, List<String> commands) {
        public PeerUseCapability {
            reads = reads == null ? List.of() : List.copyOf(reads);
            commands = commands == null ? List.of() : List.copyOf(commands);
        }
    }

    /** Reads {@code kf-module-conf.json}. */
    public static Manifest read(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    /**
     * Parses a manifest leniently (see the class javadoc).
     *
     * @throws IllegalArgumentException when the text is not a JSON object at all
     */
    public static Manifest parse(String json) {
        return of(tree(json));
    }

    /** The raw JSON tree ({@code Map}/{@code List}/scalars), as {@link ManifestCheck} reads it. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> tree(String json) {
        Object parsed;
        try {
            parsed = JSON.readValue(json, Object.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("kf-module-conf.json is not valid JSON: " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map<?, ?> map)) throw new IllegalArgumentException("kf-module-conf.json must be a JSON object");
        return (Map<String, Object>) map;
    }

    /**
     * {@code routes[].timeoutSeconds} as read (SDK 0.7.3): a JSON whole number that fits an {@code int}, else
     * {@code null}. Its range is {@link ManifestCheck}'s rule, not the reader's.
     */
    static Integer wholeSeconds(Object value) {
        if (!(value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof java.math.BigInteger)) {
            return null;
        }
        java.math.BigInteger n = new java.math.BigInteger(value.toString());
        return n.bitLength() < 32 ? n.intValue() : null;
    }

    static Manifest of(Map<String, Object> root) {
        Map<String, Object> caps = map(root.get("capabilities"));
        Capabilities capabilities = new Capabilities(
                objects(caps.get("fields")).stream().map(f -> new FieldCapability(text(f.get("entity")), text(f.get("name")),
                        text(f.get("type")), text(f.get("table")), text(f.get("label")),
                        Boolean.TRUE.equals(f.get("editable")))).toList(),
                objects(caps.get("hooks")).stream().map(h -> new HookCapability(text(h.get("point")), text(h.get("onFailure")))).toList(),
                objects(caps.get("routes")).stream().map(r -> new RouteCapability(text(r.get("method")), text(r.get("path")),
                        text(r.get("right")), texts(r.get("roles")), Boolean.TRUE.equals(r.get("hookable")),
                        text(r.get("admin")), wholeSeconds(r.get("timeoutSeconds")), text(r.get("audience")),
                        Boolean.TRUE.equals(r.get("portal")))).toList(),
                objects(caps.get("surfaces")).stream().map(s -> new SurfaceCapability(text(s.get("slug")), text(s.get("kind")),
                        text(s.get("label")), text(s.get("page")), text(s.get("key")))).toList(),
                objects(caps.get("notifications")).stream().map(n -> new NotificationCapability(text(n.get("key")),
                        texts(n.get("variables")), texts(n.get("channels")), text(n.get("subject")), text(n.get("body")))).toList(),
                objects(caps.get("reads")).stream().map(r -> new ReadCapability(text(r.get("port")), text(r.get("access")))).toList(),
                objects(caps.get("audit")).stream().map(a -> new AuditCapability(text(a.get("access")))).toList(),
                objects(caps.get("nav")).stream().map(n -> new NavCapability(text(n.get("surface")), text(n.get("group")),
                        text(n.get("key")), text(n.get("label")), text(n.get("url")), text(n.get("icon")),
                        text(n.get("right")), n.get("order") instanceof Number o ? o.intValue() : NAV_DEFAULT_ORDER,
                        text(n.get("flag")))).toList(),
                objects(caps.get("settings")).stream().map(s -> new SettingCapability(text(s.get("key")), text(s.get("type")),
                        s.get("default"), text(s.get("label")), text(s.get("description")), texts(s.get("values")),
                        s.get("min") instanceof Number min ? min : null,
                        s.get("max") instanceof Number max ? max : null)).toList(),
                objects(caps.get("secrets")).stream().map(s -> new SecretCapability(text(s.get("name")), text(s.get("scope")),
                        text(s.get("writeRight")), texts(s.get("external")))).toList(),
                objects(caps.get("provides")).stream().map(p -> new ProvideCapability(text(p.get("port")),
                        text(p.get("path")))).toList(),
                objects(caps.get("storage")).stream().map(s -> new StorageCapability(texts(s.get("contentTypes")),
                        wholeSeconds(s.get("maxBytes")))).toList(),
                objects(caps.get("slots")).stream().map(s -> new SlotCapability(text(s.get("key")),
                        SlotKind.of(s.get("kind")), text(s.get("type")), SlotVisibility.of(s.get("visibility")),
                        text(s.get("label")), texts(s.get("publicMembers")), texts(s.get("actions")),
                        texts(s.get("layerKinds")), texts(s.get("events")), texts(s.get("commands")),
                        text(s.get("dataSchema")))).toList(),
                objects(caps.get("peerReads")).stream().map(p -> new PeerReadCapability(text(p.get("port")),
                        text(p.get("path")), text(p.get("visibility")))).toList(),
                objects(caps.get("commands")).stream().map(c -> new CommandCapability(text(c.get("name")),
                        text(c.get("path")), text(c.get("method")), text(c.get("visibility")))).toList(),
                objects(caps.get("events")).stream().map(e -> new EventCapability(text(e.get("name")),
                        text(e.get("visibility")))).toList(),
                objects(caps.get("peers")).stream().map(p -> new PeerUseCapability(text(p.get("owner")),
                        texts(p.get("reads")), texts(p.get("commands")))).toList(),
                objects(caps.get("flowRules")).stream().map(r -> new FlowRuleCapability(text(r.get("key")),
                        text(r.get("path")), wholeSeconds(r.get("timeoutSeconds")), text(r.get("projection")))).toList(),
                objects(caps.get("services")).stream().map(sv -> new ServiceCapability(text(sv.get("port")))).toList(),
                objects(caps.get("flows")).stream().map(f -> new FlowCapability(text(f.get("key")), text(f.get("file")),
                        text(f.get("name")))).toList());
        Map<String, Map<String, String>> i18n = new LinkedHashMap<>();
        map(root.get("i18n")).forEach((lang, entries) -> {
            Map<String, String> texts = new LinkedHashMap<>();
            map(entries).forEach((k, v) -> {
                if (v instanceof String s) texts.put(k, s);
            });
            i18n.put(lang, Collections.unmodifiableMap(texts));
        });
        return new Manifest(
                root.get("schemaVersion") instanceof Number n ? n.intValue() : 1,
                text(root.get("slug")), text(root.get("name")), text(root.get("description")), text(root.get("version")),
                text(root.get("rollbackFloor")), text(root.get("author")),
                text(root.get("platform")),
                objects(root.get("featureFlags")).stream().map(f -> new FeatureFlag(text(f.get("key")), text(f.get("label")),
                        text(f.get("description")), Boolean.TRUE.equals(f.get("default")),
                        texts(f.get("requiresModules")))).toList(),
                objects(root.get("requires")).stream().map(r -> new Requirement(text(r.get("slug")), text(r.get("versionRange")),
                        Boolean.TRUE.equals(r.get("optional")))).toList(),
                Collections.unmodifiableMap(i18n), capabilities, activation(root.get("activation")));
    }

    /** {@code activation} as read (protocol 1.2): absent, {@code null} or not a known value = {@link Activation#TENANT}. */
    static Activation activation(Object value) {
        Activation a = Activation.of(value);
        return a == null ? Activation.TENANT : a;
    }

    /** Whether the text fields are translation keys ({@code schemaVersion} 2 and above). */
    public boolean usesKeys() {
        return schemaVersion >= 2;
    }

    /** The {@code en} text of a key, or null. */
    public String enText(String key) {
        Map<String, String> en = i18n.get("en");
        return en == null || key == null ? null : en.get(key);
    }

    // ---- lenient readers --------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** The objects of a list; a non-list is empty and non-object elements are skipped (the check reports both). */
    static List<Map<String, Object>> objects(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object e : list) if (e instanceof Map<?, ?>) out.add(map(e));
        }
        return List.copyOf(out);
    }

    static List<String> texts(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object e : list) if (e instanceof String s) out.add(s);
        }
        return List.copyOf(out);
    }

    static String text(Object o) {
        return o instanceof String s ? s : null;
    }
}
