<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

use Kf\Sdk\Manifest\Activation;
use Kf\Sdk\Manifest\AuditCapability;
use Kf\Sdk\Manifest\Capabilities;
use Kf\Sdk\Manifest\FeatureFlag;
use Kf\Sdk\Manifest\FieldCapability;
use Kf\Sdk\Manifest\HookCapability;
use Kf\Sdk\Manifest\NavCapability;
use Kf\Sdk\Manifest\NotificationCapability;
use Kf\Sdk\Manifest\ProvideCapability;
use Kf\Sdk\Manifest\ReadCapability;
use Kf\Sdk\Manifest\Requirement;
use Kf\Sdk\Manifest\RouteCapability;
use Kf\Sdk\Manifest\SecretCapability;
use Kf\Sdk\Manifest\SettingCapability;
use Kf\Sdk\Manifest\SlotCapability;
use Kf\Sdk\Manifest\SlotKind;
use Kf\Sdk\Manifest\SlotVisibility;
use Kf\Sdk\Manifest\StorageCapability;
use Kf\Sdk\Manifest\SurfaceCapability;

/**
 * The typed `kf-module-conf.json` (manifest v2; `schemaVersion` 2):
 *
 *   {
 *     "schemaVersion": 2, "slug": "api-test", "name": "module.name", "description": "module.description",
 *     "version": "0.5.0", "rollbackFloor": "0.4.0", "platform": ">=4.1",
 *     "featureFlags": [ { "key": "author-tab", "label": "flag.author-tab.label", "default": false } ],
 *     "requires": [ { "slug": "other", "versionRange": ">=1.0.0" } ],
 *     "i18n": { "en": { "module.name": "API Test", ... }, "ro": { ... } },
 *     "capabilities": {
 *       "fields":        [ { "entity": "order", "name": "priority_code", "type": "STRING", "table": "orders_extra", "label": "field.priority", "editable": true } ],
 *       "hooks":         [ { "point": "model.before.update", "onFailure": "block" } ],
 *       "routes":        [ { "method": "POST", "path": "/m/api-test/api/notes", "right": "api.v1.m.api-test.notes.post", "roles": ["QC"], "hookable": true },
 *                          { "method": "PUT", "path": "/m/api-test/admin/keys", "right": "api.v1.m.api-test.admin.keys.put", "admin": "platform" } ],
 *       "surfaces":      [ { "slug": "models.detail.tabs", "kind": "react", "label": "tab.label" } ],
 *       "notifications": [ { "key": "author-changed", "variables": ["modelName"], "channels": ["in_app"], "subject": "n.s", "body": "n.b" } ],
 *       "reads":         [ { "port": "<published id>" } ],
 *       "audit":         [ { "access": "write" }, { "access": "read-own" } ],
 *       "nav":           [ { "surface": "staff", "group": "admin-utils", "key": "notes", "label": "nav.notes", "url": "/modules", "icon": "Boxes", "right": "api.v1.m.api-test.notes.post", "order": 20, "flag": "author-tab" } ],
 *       "settings":      [ { "key": "push-on-save", "type": "BOOLEAN", "default": false, "label": "setting.push" } ],
 *       "secrets":       [ { "name": "r3-client-secret", "scope": "platform", "writeRight": "api.v1.m.api-test.admin.keys.put", "external": ["platform/aas/"] } ],
 *       "provides":      [ { "port": "factory.stations.v1", "path": "/m/api-test/stations" } ],
 *       "storage":       [ { "contentTypes": ["image/jpeg", "image/png"], "maxBytes": 5242880 } ],
 *       "slots":         [ { "key": "viewport.layers.v1", "kind": "B", "type": "layer", "visibility": "public", "layerKinds": ["pin"] } ]
 *     }
 *   }
 *
 * **Reading is lenient, checking is not.** This class reads what it can and never throws on a shape error: an absent
 * list is [], a non-list is [], `roles` absent is [] (no role), `hookable` and `editable` absent or not a
 * boolean are false. Every shape error is reported by ManifestCheck, which works on the raw JSON, so a manifest the
 * check refuses never reaches the platform.
 *
 * Two capabilities are **declared in protocol 1.0** (operator decisions of 2026-10-19, DECIZII-2026-10-16):
 * - `routes[].hookable`: a route a platform hook may call as the system; boolean, default false, only on
 *   POST/PUT/PATCH. Delivered by the platform (the strict check at upload, the approval badge, the catalog of
 *   hookable routes and the dispatch); a platform before it ignores the key.
 * - `audit`: `[{"access": "write" | "read-own"}]`, each at most once. Declared in protocol 1.0, delivered by
 *   the platform (accepted at upload and shown at approval); an older platform refuses it as an
 *   unknown capability.
 *
 * **Added in protocol 1.1** (additive, no new `schemaVersion`): `nav`, the module's menu
 * entries on the staff sidebar, the customer portal menu or the platform menu (NavCapability). An older platform refuses the key at upload (an unknown capability).
 *
 * **Added in protocol 1.1** (additive, delivered by the platform in the plan
 * named for each, sdk-protocol.md §15): `settings` (SettingCapability: closed scalars only, the rule "settings carry
 * no data"), `routes[].admin` (RouteCapability::$admin), `secrets` (SecretCapability: names and
 * scopes, values only by reference), `provides` (ProvideCapability: reads the module serves to the platform) and
 * `surfaces[].page` (SurfaceCapability::$page). A port id (`reads[].port`, `provides[].port`) matches
 * Manifest::PORT_ID. Absent keys read as [] / null: a 1.0 manifest reads unchanged.
 *
 * **Added in protocol 2.0** (`schemaVersion` 3, a strict superset of 2): `slots`
 * (SlotCapability: the slots a module defines for other modules, sdk-protocol.md §7.12). Refused under
 * `schemaVersion` 1–2 by ManifestCheck; absent reads as [].
 */
final class Manifest
{
    /** The capability keys this SDK knows, in the documented order (`audit`, delivered). */
    public const CAPABILITY_KEYS = ['fields', 'hooks', 'routes', 'surfaces', 'notifications', 'reads', 'audit', 'nav', 'settings',
        'secrets', 'provides', 'storage', 'slots', 'peerReads', 'commands', 'events', 'peers', 'flowRules', 'services', 'flows'];

    /**
     * The keys of `capabilities.flowRules[]` (protocol 2.0, sdk-protocol.md §16.4; a closed object). Delivered by the
     * platform with `FLOW_RULES`; a platform before it refuses the key at upload. The SDK checks only the grammar
     * (the platform is the gate).
     */
    public const FLOW_RULE_KEYS = ['key', 'path', 'timeoutSeconds', 'projection'];
    /** The keys of `capabilities.services[]` (protocol 2.0, §16.6; a closed object). */
    public const SERVICE_KEYS = ['port'];
    /** The only projection of a flow rule in protocol 2.0 (§16.4): the major is in the key. */
    public const FLOW_RULE_PROJECTION = 'order.flow-rule.v1';
    /** The only port of `capabilities.services` in protocol 2.0 (§16.6). */
    public const SERVICES_PORT = 'order.services.v1';
    /** The longest a flow rule may take, in seconds (§16.4). */
    public const MAX_FLOW_RULE_TIMEOUT_SECONDS = 60;
    /** How long the engine waits for a flow rule that declares no `timeoutSeconds` (§16.4). */
    public const DEFAULT_FLOW_RULE_TIMEOUT_SECONDS = 2;
    /** The surface of a module step in the order flow (§16.5): its contribution names the step in `key`. */
    public const ORDERFLOW_STEP = 'orderflow.step';

    /**
     * The keys of `capabilities.flows[]` (protocol 2.1, sdk-protocol.md §17; a closed object): the module's
     * baseline flows, a BPMN file each, in the pack. Delivered by the platform with `MODULE_FLOWS`; until then a platform
     * refuses the key at upload (§16.7). The SDK checks only the grammar and, on a pack, the files.
     */
    public const FLOW_KEYS = ['key', 'file', 'name'];
    /** The key of a module flow (§17.1): the key of the global flow definition the platform makes of it. */
    public const FLOW_KEY = '^[a-z][a-z0-9-]{2,62}$';
    /** The file of a module flow (§17.1): directly under `flows/` in the pack, a `.bpmn`. */
    public const FLOW_FILE = '^flows/[a-z0-9][a-z0-9._-]{0,99}\.bpmn$';
    /** The directory of the module flows in the pack (§17.2). */
    public const FLOWS_DIRECTORY = 'flows/';
    /** The largest BPMN file of a module flow, in bytes (§17.2): 256 KiB. */
    public const MAX_FLOW_FILE_BYTES = 262144;
    /** The most flows one module declares (§17.1). */
    public const MAX_FLOWS = 20;

    /** The visibilities of a port, a command or an event offered to other modules (protocol 2.0, required). */
    public const PEER_VISIBILITIES = ['internal', 'public'];

    /** The methods of a command between modules (protocol 2.0, sdk-protocol.md §15.9). */
    public const COMMAND_METHODS = ['POST', 'PUT', 'DELETE'];

    /** The keys of `capabilities.peerReads[]` (a closed object). */
    public const PEER_READ_KEYS = ['port', 'path', 'visibility'];
    /** The keys of `capabilities.commands[]` (a closed object). */
    public const COMMAND_KEYS = ['name', 'path', 'method', 'visibility'];
    /** The keys of `capabilities.events[]` (a closed object). */
    public const EVENT_KEYS = ['name', 'visibility'];
    /** The keys of `capabilities.peers[]` (a closed object). */
    public const PEER_USE_KEYS = ['owner', 'reads', 'commands'];

    /**
     * The grammar of a port id (protocol 1.1): an entity, one optional sub-segment, a major version
     * (`model.v1`, `file.public-url.v1`, `factory.stations.v1`).
     */
    public const PORT_ID = '^[a-z][a-z0-9-]{1,40}(\\.[a-z][a-z0-9-]{1,40})?\\.v[0-9]+$';

    /** The types of a setting: closed scalars only, settings carry no data (protocol 1.1). */
    public const SETTING_TYPES = ['BOOLEAN', 'NUMBER', 'ENUM'];

    /** The keys of a setting: a closed object. */
    public const SETTING_KEYS = ['key', 'type', 'default', 'label', 'description', 'values', 'min', 'max'];

    /** At most this many settings per module (protocol 1.1). */
    public const MAX_SETTINGS = 30;

    /** The values of `routes[].admin` (protocol 1.1). */
    public const ROUTE_ADMIN = ['tenant', 'platform'];

    /**
     * The one value of `routes[].audience` (protocol 1.2): any member of the active tenant, with
     * any role, calls the route; absent = an ordinary route (the caller holds its right).
     */
    public const ROUTE_AUDIENCE_MEMBER = 'member';

    /** The keys of a `storage` element: a closed object (protocol 1.2). */
    public const STORAGE_KEYS = ['contentTypes', 'maxBytes'];

    /** At most this many `storage` elements (protocol 1.2). */
    public const MAX_STORAGE_ELEMENTS = 1;

    /** At most this many content types in one `storage` element (protocol 1.2). */
    public const MAX_STORAGE_CONTENT_TYPES = 10;

    /** The largest object a module may declare in `storage[].maxBytes`: 10 MiB (protocol 1.2). */
    public const MAX_STORAGE_BYTES = 10485760;

    /**
     * One content type of `storage[].contentTypes` (protocol 1.2): `type/subtype` in lower case, the characters of an
     * RFC 6838 restricted name, without parameters and without a wildcard (`image/png`, `image/svg+xml`). The `/` is
     * escaped: the pattern runs between `/` delimiters (`preg_match`).
     */
    public const STORAGE_CONTENT_TYPE = '^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}\/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}$';

    /**
     * The longest a route may ask the platform to wait for its answer (`routes[].timeoutSeconds`, SDK 0.7.3): a whole number of seconds from 1 to this cap; absent = the platform's default (5 s).
     */
    public const MAX_ROUTE_TIMEOUT_SECONDS = 60;

    /** The scopes of a secret (protocol 1.1). */
    public const SECRET_SCOPES = ['platform', 'tenant'];

    /** The keys of a secret: a closed object (a value never travels in the manifest). */
    public const SECRET_KEYS = ['name', 'scope', 'writeRight', 'external'];

    /**
     * One prefix of `secrets[].external` (SDK 0.7.2): a vault path -- the mount and at least
     * one segment more -- ending with `/` (`platform/aas/`).
     */
    public const SECRET_EXTERNAL_PREFIX = '^[a-z0-9][a-z0-9_-]{0,63}(/[a-z0-9][a-z0-9_-]{0,63})+/$';

    /** At most this many prefixes in one secret's `external`. */
    public const SECRET_MAX_EXTERNAL = 5;

    /** The keys of a provided read: a closed object. */
    public const PROVIDE_KEYS = ['port', 'path'];

    /** The menus a `nav` entry may join (protocol 1.1). */
    public const NAV_SURFACES = ['staff', 'customer', 'platform'];

    /** The keys of a `nav` entry: a closed object. */
    public const NAV_KEYS = ['surface', 'group', 'key', 'label', 'url', 'icon', 'right', 'order', 'flag'];

    /** The position of a `nav` entry in its group when `order` is absent (as `surfaces`, §7.7). */
    public const NAV_DEFAULT_ORDER = 100;

    /** The methods a `hookable` route may have. */
    public const HOOKABLE_METHODS = ['POST', 'PUT', 'PATCH'];

    /** The values of `audit[].access`. */
    public const AUDIT_ACCESS = ['write', 'read-own'];

    /**
     * The grammar of a slot key (protocol 2.0, sdk-protocol.md §7.12): dot-separated lower-case segments, the
     * major as the last segment (`viewport.layers.v1`); at most Manifest::MAX_SLOT_KEY_LENGTH characters. The public key
     * is `m.{owner}.{key}`.
     */
    public const SLOT_KEY = '^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*\\.v[1-9][0-9]*$';

    /** The longest slot key (protocol 2.0). */
    public const MAX_SLOT_KEY_LENGTH = 80;

    /** The keys of a slot: a closed object (protocol 2.0, §7.12). */
    public const SLOT_KEYS = ['key', 'kind', 'type', 'visibility', 'label', 'publicMembers',
        'actions', 'layerKinds', 'events', 'commands', 'dataSchema'];

    /**
     * @param int $schemaVersion the declared `schemaVersion`, 1 when absent
     * @param ?string $description optional: what the module does, shown by the platform when present; the
     *                             same shape as `name` (a translation key under `schemaVersion` 2); null when absent
     * @param ?string $rollbackFloor optional: the oldest version of the module that runs
     *                               correctly on the data (the schema of the module's own database) this version leaves
     *                               behind; a semver at or below `version`, null when absent (or not a string). Version V
     *                               declares `rollbackFloor = F` when every version ≥ F of the module runs correctly on
     *                               the data left by V; between F and V the migrations of the module's own database are
     *                               only additive (expand). The platform computes H = the highest version ever installed
     *                               and refuses a rollback to a version below `rollbackFloor(H)`; when H declares no
     *                               floor, the floor is H itself (sdk-protocol.md §3.1, §12)
     * @param list<FeatureFlag> $featureFlags
     * @param list<Requirement> $requires
     * @param array<string, array<string, string>> $i18n `language -> (key -> text)`; `en` mandatory under 2
     */
    public function __construct(
        public readonly int $schemaVersion,
        public readonly ?string $slug,
        public readonly ?string $name,
        public readonly ?string $description,
        public readonly ?string $version,
        public readonly ?string $rollbackFloor,
        public readonly ?string $author,
        public readonly ?string $platform,
        public readonly array $featureFlags,
        public readonly array $requires,
        public readonly array $i18n,
        public readonly Capabilities $capabilities,
        public readonly Activation $activation = Activation::TENANT,
    ) {
    }

    /** Reads `kf-module-conf.json`. */
    public static function read(string $path): self
    {
        $json = @file_get_contents($path);
        if ($json === false) throw new \InvalidArgumentException("kf-module-conf.json cannot be read at $path");
        return self::parse($json);
    }

    /**
     * Parses a manifest leniently (see the class comment).
     *
     * @throws \InvalidArgumentException when the text is not a JSON object at all
     */
    public static function parse(string $json): self
    {
        return self::of(self::tree($json));
    }

    /** @internal The raw JSON tree, as ManifestCheck reads it. */
    public static function tree(string $json): \stdClass
    {
        try {
            $parsed = Json::decode($json);
        } catch (\JsonException $e) {
            throw new \InvalidArgumentException('kf-module-conf.json is not valid JSON: ' . $e->getMessage(), 0, $e);
        }
        if (!Json::isObject($parsed)) throw new \InvalidArgumentException('kf-module-conf.json must be a JSON object');
        return $parsed;
    }

    /** @internal */
    public static function of(\stdClass $root): self
    {
        $caps = Json::get($root, 'capabilities');
        $t = static fn (\stdClass $o, string $k): ?string => self::text(Json::get($o, $k));
        $capabilities = new Capabilities(
            array_map(static fn (\stdClass $f) => new FieldCapability($t($f, 'entity'), $t($f, 'name'), $t($f, 'type'),
                $t($f, 'table'), $t($f, 'label'), Json::get($f, 'editable') === true), self::objects(Json::get($caps, 'fields'))),
            array_map(static fn (\stdClass $h) => new HookCapability($t($h, 'point'), $t($h, 'onFailure')), self::objects(Json::get($caps, 'hooks'))),
            array_map(static fn (\stdClass $r) => new RouteCapability($t($r, 'method'), $t($r, 'path'), $t($r, 'right'),
                self::texts(Json::get($r, 'roles')), Json::get($r, 'hookable') === true, $t($r, 'admin'),
                self::wholeSeconds(Json::get($r, 'timeoutSeconds')), $t($r, 'audience'), Json::get($r, 'portal') === true),
                self::objects(Json::get($caps, 'routes'))),
            array_map(static fn (\stdClass $s) => new SurfaceCapability($t($s, 'slug'), $t($s, 'kind'), $t($s, 'label'), $t($s, 'page'),
                $t($s, 'key')),
                self::objects(Json::get($caps, 'surfaces'))),
            array_map(static fn (\stdClass $n) => new NotificationCapability($t($n, 'key'), self::texts(Json::get($n, 'variables')),
                self::texts(Json::get($n, 'channels')), $t($n, 'subject'), $t($n, 'body')), self::objects(Json::get($caps, 'notifications'))),
            array_map(static fn (\stdClass $r) => new ReadCapability($t($r, 'port'), $t($r, 'access')), self::objects(Json::get($caps, 'reads'))),
            array_map(static fn (\stdClass $a) => new AuditCapability($t($a, 'access')), self::objects(Json::get($caps, 'audit'))),
            array_map(static fn (\stdClass $n) => new NavCapability($t($n, 'surface'), $t($n, 'group'), $t($n, 'key'),
                $t($n, 'label'), $t($n, 'url'), $t($n, 'icon'), $t($n, 'right'),
                is_int(Json::get($n, 'order')) || is_float(Json::get($n, 'order')) ? (int) Json::get($n, 'order') : self::NAV_DEFAULT_ORDER,
                $t($n, 'flag')), self::objects(Json::get($caps, 'nav'))),
            array_map(static fn (\stdClass $s) => new SettingCapability($t($s, 'key'), $t($s, 'type'), Json::get($s, 'default'),
                $t($s, 'label'), $t($s, 'description'), self::texts(Json::get($s, 'values')),
                is_int(Json::get($s, 'min')) || is_float(Json::get($s, 'min')) ? Json::get($s, 'min') : null,
                is_int(Json::get($s, 'max')) || is_float(Json::get($s, 'max')) ? Json::get($s, 'max') : null),
                self::objects(Json::get($caps, 'settings'))),
            array_map(static fn (\stdClass $s) => new SecretCapability($t($s, 'name'), $t($s, 'scope'), $t($s, 'writeRight'),
                self::texts(Json::get($s, 'external'))),
                self::objects(Json::get($caps, 'secrets'))),
            array_map(static fn (\stdClass $p) => new ProvideCapability($t($p, 'port'), $t($p, 'path')),
                self::objects(Json::get($caps, 'provides'))),
            array_map(static fn (\stdClass $s) => new StorageCapability(self::texts(Json::get($s, 'contentTypes')),
                self::wholeSeconds(Json::get($s, 'maxBytes'))), self::objects(Json::get($caps, 'storage'))),
            array_map(static fn (\stdClass $s) => new SlotCapability($t($s, 'key'), SlotKind::of(Json::get($s, 'kind')),
                $t($s, 'type'), SlotVisibility::of(Json::get($s, 'visibility')), $t($s, 'label'),
                self::texts(Json::get($s, 'publicMembers')), self::texts(Json::get($s, 'actions')),
                self::texts(Json::get($s, 'layerKinds')), self::texts(Json::get($s, 'events')),
                self::texts(Json::get($s, 'commands')), $t($s, 'dataSchema')), self::objects(Json::get($caps, 'slots'))),
            array_map(static fn (\stdClass $p) => new Manifest\PeerReadCapability($t($p, 'port'), $t($p, 'path'), $t($p, 'visibility')),
                self::objects(Json::get($caps, 'peerReads'))),
            array_map(static fn (\stdClass $c) => new Manifest\CommandCapability($t($c, 'name'), $t($c, 'path'), $t($c, 'method'),
                $t($c, 'visibility')), self::objects(Json::get($caps, 'commands'))),
            array_map(static fn (\stdClass $e) => new Manifest\EventCapability($t($e, 'name'), $t($e, 'visibility')),
                self::objects(Json::get($caps, 'events'))),
            array_map(static fn (\stdClass $p) => new Manifest\PeerUseCapability($t($p, 'owner'), self::texts(Json::get($p, 'reads')),
                self::texts(Json::get($p, 'commands'))), self::objects(Json::get($caps, 'peers'))),
            array_map(static fn (\stdClass $r) => new Manifest\FlowRuleCapability($t($r, 'key'), $t($r, 'path'),
                self::wholeSeconds(Json::get($r, 'timeoutSeconds')), $t($r, 'projection')), self::objects(Json::get($caps, 'flowRules'))),
            array_map(static fn (\stdClass $sv) => new Manifest\ServiceCapability($t($sv, 'port')),
                self::objects(Json::get($caps, 'services'))),
            array_map(static fn (\stdClass $f) => new Manifest\FlowCapability($t($f, 'key'), $t($f, 'file'), $t($f, 'name')),
                self::objects(Json::get($caps, 'flows'))),
        );
        $i18n = [];
        foreach (Json::entries(Json::get($root, 'i18n')) as $lang => $entries) {
            $texts = [];
            foreach (Json::entries($entries) as $k => $v) if (is_string($v)) $texts[(string) $k] = $v;
            $i18n[(string) $lang] = $texts;
        }
        $version = Json::get($root, 'schemaVersion');
        return new self(
            is_int($version) || is_float($version) ? (int) $version : 1,
            $t($root, 'slug'), $t($root, 'name'), $t($root, 'description'), $t($root, 'version'),
            $t($root, 'rollbackFloor'), $t($root, 'author'), $t($root, 'platform'),
            array_map(static fn (\stdClass $f) => new FeatureFlag($t($f, 'key'), $t($f, 'label'), $t($f, 'description'),
                Json::get($f, 'default') === true, self::texts(Json::get($f, 'requiresModules'))),
                self::objects(Json::get($root, 'featureFlags'))),
            array_map(static fn (\stdClass $r) => new Requirement($t($r, 'slug'), $t($r, 'versionRange'),
                Json::get($r, 'optional') === true), self::objects(Json::get($root, 'requires'))),
            $i18n,
            $capabilities,
            self::activation(Json::get($root, 'activation')),
        );
    }

    /** @internal `activation` as read (protocol 1.2): absent, `null` or not a known value = Activation::TENANT. */
    public static function activation(mixed $value): Activation
    {
        return (is_string($value) ? Activation::tryFrom($value) : null) ?? Activation::TENANT;
    }

    /** Whether the text fields are translation keys (`schemaVersion` 2 and above). */
    public function usesKeys(): bool
    {
        return $this->schemaVersion >= 2;
    }

    /** The `en` text of a key, or null. */
    public function enText(?string $key): ?string
    {
        return $key === null ? null : ($this->i18n['en'][$key] ?? null);
    }

    // ---- lenient readers --------------------------------------------------------------------------------------

    /** @internal The objects of a list; a non-list is empty and non-object elements are skipped (the check reports both). */
    public static function objects(mixed $o): array
    {
        return is_array($o) ? array_values(array_filter($o, static fn (mixed $e): bool => $e instanceof \stdClass)) : [];
    }

    /** @internal */
    public static function texts(mixed $o): array
    {
        return is_array($o) ? array_values(array_filter($o, 'is_string')) : [];
    }

    /** @internal */
    public static function text(mixed $o): ?string
    {
        return is_string($o) ? $o : null;
    }

    /**
     * @internal `routes[].timeoutSeconds` as read (SDK 0.7.3): a JSON whole number that fits a 32-bit int, else null.
     * Its range is ManifestCheck's rule, not the reader's.
     */
    public static function wholeSeconds(mixed $o): ?int
    {
        return is_int($o) && $o >= -2147483648 && $o <= 2147483647 ? $o : null;
    }
}
