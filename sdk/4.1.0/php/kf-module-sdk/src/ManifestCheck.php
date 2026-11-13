<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestCheck.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The local check of `kf-module-conf.json` and of the registration against it, BEFORE the first request.
 *
 * The same rules the platform applies at upload (`ModuleManifestValidator`) and at registration
 * (`ManifestCapabilityGuard`), minus everything that needs the platform's catalogs (the extensible entities,
 * the hook points, the surfaces, the global roles, the configured languages): those stay the platform's. A violation
 * found here is a **permanent** error: `KfModule::registerWithRetry()` sends nothing and does not retry, so a module
 * author sees the cause in the module's own log instead of a 422 in the platform's journal.
 *
 * Messages are in English, start with the path of the element and name the rule, like the platform's.
 *
 * Declared in protocol 1.0 (operator decisions of 2026-10-19):
 * - `routes[].hookable`: a boolean, only on POST/PUT/PATCH; a non-boolean value or `true` on another
 *   method is refused, naming the route. The platform's strict check, the approval badge, the catalog and the dispatch
 *   are delivered by the platform.
 * - `audit`: a list of `{"access": "write" | "read-own"}`, each value at most once. Delivered by the platform.
 *
 * The optional top-level `description` is checked like `name`: a key defined in `i18n.en`; absent or
 * null means none.
 *
 * The optional top-level `rollbackFloor` is a semver 2.0 version at or below `version`
 * (semver precedence, not text order); absent or null means none. It is checked under every `schemaVersion`: it does
 * not depend on `i18n` or `capabilities`.
 *
 * The capability `nav` (protocol 1.1) is checked as far as the manifest alone allows: the surface, the
 * keys, the label key, the url, the icon name, the order, the flag, and the right (never on `customer`; under
 * `api.v1.m.` only the right of one of the module's routes). The groups of a surface and the platform's declared codes
 * are the platform's catalogs: checked at upload.
 *
 * The keys added to protocol 1.1 are checked as far as the manifest alone allows: `settings`
 * (closed objects; `BOOLEAN`, `NUMBER` or `ENUM` only -- "settings carry no data"; the default coherent with the type,
 * `values` and `min..max`; unique keys; at most Manifest::MAX_SETTINGS), `routes[].admin` (`tenant` | `platform`; no
 * roles on `platform`; never on a `hookable` route), `routes[].timeoutSeconds` (SDK 0.7.3: a whole number, 1 to
 * Manifest::MAX_ROUTE_TIMEOUT_SECONDS), `secrets` (closed objects, never a value; unique names; the scope;
 * `writeRight` = the right of a declared route, of an `admin: platform` route for a platform secret), `provides` (closed
 * objects; the port grammar; `path` = a declared GET route) and `surfaces[].page` (its grammar). A port id, in `reads`
 * and `provides`, matches Manifest::PORT_ID. Which ports the platform publishes or consumes and the extensible
 * entities (`model_version`) stay the platform's catalogs: checked at upload.
 *
 * The type of a surface (SDK 0.7.1) is checked against the release list this SDK pins (Surfaces::TYPES,
 * generated from `sdk/catalog/surfaces.public.json`), with the platform's rule and messages (`ModuleManifestValidator`): `page` only on a surface of type `page`, always on one, and each page of a surface once. A slug the pinned
 * list does not know is not judged here (a platform newer than this SDK may declare it): the platform decides at upload.
 *
 * Protocol 2.0: `schemaVersion` 3 is accepted, a strict superset of 2. The key it adds here,
 * `capabilities.slots` (sdk-protocol.md §7.12), is refused under 1–2 (`requires "schemaVersion": 3`) and
 * checked as far as the manifest alone allows.
 *
 * Protocol 2.1 (sdk-protocol.md §17): `capabilities.flows` (schema 3 only; closed objects; the key, the
 * file directly under `flows/` ending in `.bpmn`, the name a key of `i18n.en`; at most Manifest::MAX_FLOWS).
 * `checkPack` adds what only the pack shows: each file present and at most Manifest::MAX_FLOW_FILE_BYTES bytes. The
 * BPMN itself is the platform's (it compiles it at upload and approval); whether a platform delivers `MODULE_FLOWS` is
 * its gate, never the SDK's.
 */
final class ManifestCheck
{
    /** Translation key grammar (the platform's `ModuleManifestValidator.KEY`). */
    public const KEY = '/^[a-z][a-z0-9_.-]{0,119}$/';
    private const LANGUAGE_CODE = '/^[a-z]{2,3}([-_][A-Za-z0-9]{2,8})?$/';
    private const NAME = '/^[a-z][a-z0-9_]{0,40}$/';
    private const SLUG = '/^[a-z0-9][a-z0-9-]{1,40}$/';
    private const NOTIFICATION_KEY = '/^[a-z][a-z0-9_-]{0,40}$/';
    private const RIGHT_SEGMENT = '/^[a-z][a-z0-9-]{0,40}$/';
    /** Semver 2.0 (the platform's `SemVer`): `major.minor.patch[-pre][+build]`, no leading zeros. */
    private const SEMVER = '/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)'
        . '(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?'
        . '(?:\+[0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*)?$/D';
    private const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
    private const FIELD_TYPES = ['STRING', 'NUMBER', 'DATE', 'BOOLEAN', 'LIST'];
    private const SURFACE_KINDS = ['react', 'wc'];
    private const CHANNELS = ['in_app', 'email'];
    private const ON_FAILURE = ['allow', 'block'];
    private const FORBIDDEN_ROLES = ['TENANT_ADMIN', 'SUPER_ADMIN'];
    private const DDL_KEYS = ['migrations', 'ddl', 'sql', 'schema'];
    private const DICTIONARY_KEYS = ['dictionary', 'translations', 'writes'];
    private const MAX_ROLES_PER_ROUTE = 10;
    private const MAX_ROLE_NAME_LENGTH = 100;
    private const MAX_TEXT_LENGTH = 4000;
    /** The key of a `nav` entry and of its group (protocol 1.1). */
    private const NAV_KEY = '/^[a-z][a-z0-9-]{0,40}$/D';
    /** An icon name of the host's registry (Lucide, PascalCase). */
    private const NAV_ICON = '/^[A-Z][A-Za-z0-9]{0,60}$/D';
    /** The shape of a platform right code (`api.v1.customers.get`, `api.customer.v1.get`). */
    private const PLATFORM_RIGHT = '/^api(\.[a-z0-9][a-z0-9-]*){2,10}$/D';
    private const MAX_NAV_ORDER = 10000;
    private const MAX_NAV_URL_LENGTH = 255;
    private const FILE = 'kf-module-conf.json';
    private const HOOKABLE_RULE = 'hookable only on POST, PUT or PATCH';
    /** A port id (protocol 1.1); the raw grammars below are matched whole (see full()). */
    private const PORT = Manifest::PORT_ID;
    /** The key of a setting and the name of a secret (protocol 1.1). */
    private const SETTING_KEY = '^[a-z][a-z0-9-]{1,40}$';
    /** A value of an `ENUM` setting: a code, not a text. */
    private const ENUM_VALUE = '^[a-z0-9][a-z0-9_-]{0,40}$';
    /** The page of a `page` surface, under `/m/{slug}/`. */
    private const PAGE = '^[a-z][a-z0-9-]{0,40}$';
    /** The surface type that takes a `page` (the platform's `ModuleManifestValidator.PAGE_SURFACE_TYPE`). */
    private const PAGE_SURFACE_TYPE = 'page';
    private const SETTINGS_RULE = 'settings carry no data: BOOLEAN, NUMBER or ENUM';
    /** A layer kind of a B slot (protocol 2.0). */
    private const SLOT_LAYER_KIND = '^[a-z][a-z0-9-]{1,30}$';
    /** An action, an event or a command of a slot (protocol 2.0). */
    private const SLOT_NAME = '^[a-z][a-z0-9-]{1,40}$';
    /** A public `ctx` member of an A slot (protocol 2.0). */
    private const SLOT_MEMBER = '^[a-z][A-Za-z0-9]{0,40}$';
    /** What follows `m.{owner}.` in the `dataSchema` of an R slot: `{name}.v{N}`. */
    private const SLOT_SCHEMA_TAIL = '^[a-z][a-z0-9-]{0,40}\\.v[1-9][0-9]*$';
    /** The platform's `VersionRange.EXPECTED_FORM`, named in every parse error. */
    private const VERSION_RANGE_EXPECTED_FORM = 'expected one of: "*", "1.2.3", ">=1.2.3", ">1.2.3", "<=1.2.3", "<1.2.3", '
        . 'or two bounds separated by a space such as ">=1.2.0 <2.0.0"';

    private function __construct()
    {
    }

    /**
     * The manifest and the files of its pack (protocol 2.1, sdk-protocol.md §17.2): the manifest's own rules
     * (`validate`), then each `capabilities.flows[].file` present in the pack and at most Manifest::MAX_FLOW_FILE_BYTES
     * bytes -- the platform's upload rule, so a module author sees it before the upload (`check-pack`).
     *
     * @param array<string, int>|null $packFiles the pack's files, by their path from the pack's root (`flows/x.bpmn`),
     *        with their size in bytes; null = an empty pack
     */
    public static function checkPack(string $manifestJson, ?array $packFiles): ManifestCheckResult
    {
        $errors = [];
        $warnings = [];
        $root = self::parseOrReport($manifestJson, $errors);
        if ($root === null) return new ManifestCheckResult($errors, $warnings);
        self::validateTree($root, [], $errors, $warnings);
        self::packFlowFiles($root, $packFiles ?? [], $errors);
        return new ManifestCheckResult($errors, $warnings);
    }

    /**
     * The manifest alone.
     *
     * @param list<string> $knownLanguages the languages the platform serves, when the caller knows them; empty =
     *        unknown (no language warning). A language outside a non-empty set is a WARNING, never an error
     */
    public static function validate(string $manifestJson, array $knownLanguages = []): ManifestCheckResult
    {
        $errors = [];
        $warnings = [];
        $root = self::parseOrReport($manifestJson, $errors);
        if ($root !== null) self::validateTree($root, $knownLanguages, $errors, $warnings);
        return new ManifestCheckResult($errors, $warnings);
    }

    /**
     * The manifest and the registration body (`KfModule::registrationBody()`): the manifest's own rules, then
     * registration ⊆ manifest (the platform's rule without its catalog) and every label key with an `en` text
     * (`en` mandatory).
     */
    public static function check(string $manifestJson, ?array $registrationBody, array $knownLanguages = []): ManifestCheckResult
    {
        $errors = [];
        $warnings = [];
        $root = self::parseOrReport($manifestJson, $errors);
        if ($root === null) return new ManifestCheckResult($errors, $warnings);
        self::validateTree($root, $knownLanguages, $errors, $warnings);
        $body = Json::decode(Json::encode(Json::obj($registrationBody ?? [])));
        self::checkRegistration(Manifest::of($root), $body, $errors);
        return new ManifestCheckResult($errors, $warnings);
    }

    private static function parseOrReport(string $json, array &$errors): ?\stdClass
    {
        try {
            return Manifest::tree($json);
        } catch (\InvalidArgumentException $e) {
            $errors[] = $e->getMessage();
            return null;
        }
    }

    private static function matches(string $pattern, ?string $value): bool
    {
        return $value !== null && preg_match($pattern, $value) === 1;
    }

    /** Java's `Pattern.matcher(value).matches()` on a raw grammar (anchored, no final newline). */
    private static function full(string $raw, mixed $value): bool
    {
        return is_string($value) && preg_match('/' . $raw . '/D', $value) === 1;
    }

    private static function isNumber(mixed $value): bool
    {
        return is_int($value) || is_float($value);
    }

    private static function blank(mixed $s): bool
    {
        return !is_string($s) || trim($s) === '';
    }

    // ---- the manifest ----------------------------------------------------------------------------------------

    private static function validateTree(\stdClass $root, array $knownLanguages, array &$errors, array &$warnings): void
    {
        $f = self::FILE;
        $rawVersion = Json::get($root, 'schemaVersion');
        if ($rawVersion === null) {
            $schemaVersion = 1;
        } elseif (is_int($rawVersion)) {
            $schemaVersion = $rawVersion;
        } else {
            $errors[] = "$f: \"schemaVersion\" must be an integer";
            return;
        }
        if ($schemaVersion > SdkVersion::PROTOCOL_SCHEMA_VERSION || $schemaVersion < 1) {
            $errors[] = "$f: \"schemaVersion\" $schemaVersion is not supported by kf-module-sdk " . SdkVersion::sdkVersion()
                . ' (supported: 1..' . SdkVersion::PROTOCOL_SCHEMA_VERSION . ')';
            return;
        }
        self::rollbackFloor($root, $errors);
        self::activation($root, $errors);
        // Protocol 2.0: requires and featureFlags[].requiresModules, under every schemaVersion.
        self::requires($root, $schemaVersion, $errors);
        self::flagRequiresModules($root, $schemaVersion, $errors);
        if ($schemaVersion < 2) {
            foreach (['i18n', 'capabilities'] as $block) {
                if (Json::has($root, $block)) $errors[] = "$f: \"$block\" requires \"schemaVersion\": 2 (this manifest is schemaVersion 1)";
            }
            $warnings[] = "$f: schemaVersion 1 declares no capabilities; only the platform catalog limits the registration"
                . ' (upgrade to "schemaVersion": 2)';
            return;
        }

        $slug = Manifest::text(Json::get($root, 'slug'));
        if (!self::matches(self::SLUG, $slug)) {
            $errors[] = "$f: \"slug\" must match " . trim(self::SLUG, '/') . ' (got ' . self::quoted(Json::get($root, 'slug')) . ')';
        }
        if (self::blank(Json::get($root, 'platform'))) {
            $errors[] = "$f: \"platform\" is required for \"schemaVersion\": 2 (e.g. \">=4.1\")";
        }
        self::forbiddenKeys($root, '', $errors);
        $enKeys = self::i18n(Json::get($root, 'i18n'), $knownLanguages, $errors, $warnings);

        $name = Json::get($root, 'name');
        if (!self::blank($name)) self::requireKey("$f: \"name\"", $name, $enKeys, $errors);
        // description: optional, the same shape as name; absent or null = none
        if (Json::has($root, 'description') && Json::get($root, 'description') !== null) {
            self::requireKey("$f: \"description\"", Manifest::text(Json::get($root, 'description')), $enKeys, $errors);
        }
        foreach (Manifest::objects(Json::get($root, 'featureFlags')) as $flag) {
            $key = Manifest::text(Json::get($flag, 'key'));
            $at = "$f: featureFlags[\"" . ($key ?? 'null') . '"]';
            if (!self::blank(Json::get($flag, 'label'))) self::requireKey("$at.label", Json::get($flag, 'label'), $enKeys, $errors);
            if (Json::has($flag, 'description') && Json::get($flag, 'description') !== null) {
                self::requireKey("$at.description", Manifest::text(Json::get($flag, 'description')), $enKeys, $errors);
            }
        }

        if (!Json::has($root, 'capabilities') || Json::get($root, 'capabilities') === null) return;
        $caps = Json::get($root, 'capabilities');
        if (!Json::isObject($caps)) {
            $errors[] = "$f: \"capabilities\" must be an object";
            return;
        }
        foreach (Json::entries($caps) as $key => $value) {
            $key = (string) $key;
            $rule = self::forbiddenRule($key);
            if ($rule !== null) {
                $errors[] = "$f: capabilities.$key is not allowed: $rule";
            } elseif (!in_array($key, Manifest::CAPABILITY_KEYS, true)) {
                $errors[] = "$f: capabilities.$key is not a capability this SDK knows (known: " . implode(', ', Manifest::CAPABILITY_KEYS) . ')';
            } elseif ($value !== null && !is_array($value)) {
                $errors[] = "$f: capabilities.$key must be an array";
            }
        }
        self::fields(self::items(Json::get($caps, 'fields')), $enKeys, $errors);
        self::hooks($root, $schemaVersion, self::items(Json::get($caps, 'hooks')), $errors);
        self::routes($slug, self::items(Json::get($caps, 'routes')), $errors);
        self::surfaces($slug, $root, $schemaVersion, self::items(Json::get($caps, 'surfaces')), $enKeys, $errors);
        self::notifications(self::items(Json::get($caps, 'notifications')), $enKeys, $errors);
        self::reads(self::items(Json::get($caps, 'reads')), $errors);
        self::audit(self::items(Json::get($caps, 'audit')), $errors);
        self::nav($slug, $root, self::items(Json::get($caps, 'nav')), $enKeys, $errors);
        self::settings(self::items(Json::get($caps, 'settings')), $enKeys, $errors);
        self::secrets($root, self::items(Json::get($caps, 'secrets')), $errors);
        self::provides($root, self::items(Json::get($caps, 'provides')), $errors);
        self::storage(Json::get($caps, 'storage'), $errors);
        if (Json::get($caps, 'slots') !== null) {
            if ($schemaVersion < 3) {
                $errors[] = "$f: capabilities.slots requires \"schemaVersion\": 3 (this manifest is schemaVersion $schemaVersion)";
            } else {
                self::slots($slug, self::items(Json::get($caps, 'slots')), $enKeys, $errors);
            }
        }
        // Protocol 2.0 (the platform's rules and messages): between modules, schema 3 only.
        $betweenModules = false;
        foreach (['peerReads', 'commands', 'events', 'peers'] as $key) {
            if (Json::get($caps, $key) === null) continue;
            if ($schemaVersion < 3) {
                $errors[] = "$f: capabilities.$key requires \"schemaVersion\": 3 (this manifest is schemaVersion $schemaVersion)";
            } else {
                $betweenModules = true;
            }
        }
        if ($betweenModules) {
            self::peerReads($slug, $root, self::items(Json::get($caps, 'peerReads')), $errors);
            self::commands($root, self::items(Json::get($caps, 'commands')), $errors);
            self::events(self::items(Json::get($caps, 'events')), $errors);
            self::peers($slug, $root, self::items(Json::get($caps, 'peers')), $errors);
        }
        // Protocol 2.0 (§16): the keys of the order flow, schema 3 only, the platform's rules and messages. Only the grammar: whether this platform delivers them is the platform's gate, never the SDK's.
        if (Json::get($caps, 'flowRules') !== null) {
            if ($schemaVersion < 3) {
                $errors[] = self::FILE . ": capabilities.flowRules requires \"schemaVersion\": 3 (this manifest is schemaVersion $schemaVersion)";
            } else {
                self::flowRules($root, self::items(Json::get($caps, 'flowRules')), $errors);
            }
        }
        if (Json::get($caps, 'services') !== null) {
            if ($schemaVersion < 3) {
                $errors[] = self::FILE . ": capabilities.services requires \"schemaVersion\": 3 (this manifest is schemaVersion $schemaVersion)";
            } else {
                self::services(self::items(Json::get($caps, 'services')), $errors);
            }
        }
        // Protocol 2.1 (§17): the module flows, schema 3 only, the platform's rules and messages.
        if (Json::get($caps, 'flows') !== null) {
            if ($schemaVersion < 3) {
                $errors[] = self::FILE . ": capabilities.flows requires \"schemaVersion\": 3 (this manifest is schemaVersion $schemaVersion)";
            } else {
                self::flows(self::items(Json::get($caps, 'flows')), $enKeys, $errors);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The module flows (protocol 2.1, sdk-protocol.md §17; the platform's ModuleManifestValidator)
    // ---------------------------------------------------------------------------------------------------------------

    /** Whether `$file` matches Manifest::FLOW_FILE (the pattern holds `/`, so another delimiter). */
    private static function flowFile(mixed $file): bool
    {
        return is_string($file) && preg_match('#' . Manifest::FLOW_FILE . '#D', $file) === 1;
    }

    /**
     * `capabilities.flows[]` (§17.1): closed objects; `key` unique, Manifest::FLOW_KEY; `file` unique, without `..`,
     * directly under `flows/` and ending in `.bpmn`; `name` a key of `i18n.en`; at most Manifest::MAX_FLOWS.
     *
     * @param array<string, true> $enKeys
     * @param list<string> $errors
     */
    private static function flows(array $list, array $enKeys, array &$errors): void
    {
        if (count($list) > Manifest::MAX_FLOWS) {
            $errors[] = self::FILE . ': capabilities.flows holds at most ' . Manifest::MAX_FLOWS . ' flows (got ' . count($list) . ')';
        }
        $keys = [];
        $files = [];
        foreach ($list as $i => $raw) {
            $at = self::FILE . ": capabilities.flows[$i]";
            $f = self::closedObject($at, $raw, Manifest::FLOW_KEYS, 'a module flow', $errors);
            if ($f === null) continue;
            $key = Manifest::text(Json::get($f, 'key'));
            if ($key === null || !self::full(Manifest::FLOW_KEY, $key)) {
                $errors[] = "$at.key " . self::quoted(Json::get($f, 'key')) . ' must match ' . Manifest::FLOW_KEY;
            } elseif (isset($keys[$key])) {
                $errors[] = "$at.key \"$key\" is declared twice";
            } else {
                $keys[$key] = true;
            }
            $file = Manifest::text(Json::get($f, 'file'));
            if ($file !== null && str_contains($file, '..')) {
                $errors[] = "$at.file \"$file\" must not contain \"..\"";
            } elseif ($file === null || !self::flowFile($file)) {
                $errors[] = "$at.file " . self::quoted(Json::get($f, 'file')) . ' must be a .bpmn file directly under '
                    . Manifest::FLOWS_DIRECTORY . ' (' . Manifest::FLOW_FILE . ')';
            } elseif (isset($files[$file])) {
                $errors[] = "$at.file \"$file\" is declared twice";
            } else {
                $files[$file] = true;
            }
            self::requireKey("$at.name", Manifest::text(Json::get($f, 'name')), $enKeys, $errors);
        }
    }

    /**
     * `checkPack`: each well-formed `flows[].file` present in the pack and not over the cap (§17.2).
     *
     * @param array<string, int> $packFiles
     * @param list<string> $errors
     */
    private static function packFlowFiles(\stdClass $root, array $packFiles, array &$errors): void
    {
        $caps = Json::get($root, 'capabilities');
        if (!$caps instanceof \stdClass) return;
        foreach (self::items(Json::get($caps, 'flows')) as $i => $raw) {
            if (!$raw instanceof \stdClass) continue;
            $file = Manifest::text(Json::get($raw, 'file'));
            if ($file === null || str_contains($file, '..') || !self::flowFile($file)) continue; // reported above
            $at = self::FILE . ": capabilities.flows[$i].file \"$file\"";
            $size = $packFiles[$file] ?? null;
            if ($size === null) {
                $errors[] = "$at is not in the pack";
            } elseif ($size > Manifest::MAX_FLOW_FILE_BYTES) {
                $errors[] = "$at is larger than " . Manifest::MAX_FLOW_FILE_BYTES . " bytes (got $size)";
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The order flow (protocol 2.0, sdk-protocol.md §16; the platform's ModuleManifestValidator)
    // ---------------------------------------------------------------------------------------------------------------

    /** The key of a step rendered by `orderflow.step` (§16.5) and of a flow rule (§16.4). */
    private const STEP_KEY = '^[a-z][a-z0-9-]{1,40}$';

    /**
     * The platform's refusal of a declared path with a `{param}` segment.
     *
     * @param list<string> $errors
     */
    private static function literalPath(string $at, string $path, array &$errors): void
    {
        $errors[] = "$at.path " . self::quoted($path) . ' must be a route with literal segments only (no {param} segment)';
    }

    /**
     * `capabilities.flowRules[]` (§16.4): closed objects; `key` a name, unique; `path` a declared GET route;
     * `timeoutSeconds` 1..60 when present; `projection` = `order.flow-rule.v1`.
     *
     * @param list<string> $errors
     */
    private static function flowRules(mixed $root, array $list, array &$errors): void
    {
        $getPaths = self::declaredPaths($root, 'GET');
        $seen = [];
        foreach ($list as $i => $raw) {
            $at = self::FILE . ": capabilities.flowRules[$i]";
            $r = self::closedObject($at, $raw, Manifest::FLOW_RULE_KEYS, 'a flow rule', $errors);
            if ($r === null) continue;
            $key = Manifest::text(Json::get($r, 'key'));
            if ($key === null || !self::full(self::PEER_NAME, $key)) {
                $errors[] = "$at.key " . self::quoted(Json::get($r, 'key')) . ' must match ' . self::PEER_NAME;
            } elseif (isset($seen[$key])) {
                $errors[] = "$at.key \"$key\" is declared twice";
            } else {
                $seen[$key] = true;
            }
            $path = Manifest::text(Json::get($r, 'path'));
            if ($path === null || !isset($getPaths[$path])) {
                $errors[] = "$at.path " . self::quoted(Json::get($r, 'path')) . ' is not a GET route this module declares '
                    . '(capabilities.routes[] with method GET)';
            } elseif (str_contains($path, '{')) {
                self::literalPath($at, $path, $errors); //
            }
            $timeout = Json::get($r, 'timeoutSeconds');
            if ($timeout !== null) {
                $seconds = Manifest::wholeSeconds($timeout);
                if ($seconds === null || $seconds < 1 || $seconds > Manifest::MAX_FLOW_RULE_TIMEOUT_SECONDS) {
                    $errors[] = "$at.timeoutSeconds " . self::quoted($timeout) . ' must be a whole number from 1 to '
                        . Manifest::MAX_FLOW_RULE_TIMEOUT_SECONDS;
                }
            }
            if (Json::get($r, 'projection') !== Manifest::FLOW_RULE_PROJECTION) {
                $errors[] = "$at.projection " . self::quoted(Json::get($r, 'projection')) . ' must be "'
                    . Manifest::FLOW_RULE_PROJECTION . '"';
            }
        }
    }

    /**
     * `capabilities.services[]` (§16.6): closed objects; `port` = `order.services.v1`, once.
     *
     * @param list<string> $errors
     */
    private static function services(array $list, array &$errors): void
    {
        $seen = [];
        foreach ($list as $i => $raw) {
            $at = self::FILE . ": capabilities.services[$i]";
            $sv = self::closedObject($at, $raw, Manifest::SERVICE_KEYS, 'a service', $errors);
            if ($sv === null) continue;
            $port = Manifest::text(Json::get($sv, 'port'));
            if ($port !== Manifest::SERVICES_PORT) {
                $errors[] = "$at.port " . self::quoted(Json::get($sv, 'port')) . ' must be "' . Manifest::SERVICES_PORT . '"';
            } elseif (isset($seen[$port])) {
                $errors[] = "$at.port \"$port\" is declared twice";
            } else {
                $seen[$port] = true;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Between modules (protocol 2.0, sdk-protocol.md §15.9 / §6.3; the platform's ModuleManifestValidator)
    // ---------------------------------------------------------------------------------------------------------------

    /** The name of a command or an event (and of a peer's command): `^[a-z][a-z0-9-]{1,40}$`. */
    public const PEER_NAME = '^[a-z][a-z0-9-]{1,40}$';
    private const PEER_PORT_TAIL = '[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*\\.v[1-9][0-9]*';

    /**
     * @internal The grammar of a port offered by `owner`, `m.{owner}.{name}.v{N}`, as Java prints it
     * (`Pattern.pattern()`: the owner between `\Q` and `\E`); PCRE reads `\Q…\E` the same way.
     */
    public static function peerPort(?string $owner): string
    {
        return '^m\\.\\Q' . ($owner ?? '') . '\\E\\.' . self::PEER_PORT_TAIL . '$';
    }

    /** @internal Java's `matcher(value).matches()` on a raw grammar of this class. */
    public static function fullMatch(string $raw, mixed $value): bool
    {
        return self::full($raw, $value);
    }

    /** @param list<string> $errors */
    private static function closedObject(string $at, mixed $raw, array $keys, string $what, array &$errors): ?\stdClass
    {
        if (!Json::isObject($raw)) {
            $errors[] = "$at must be an object";
            return null;
        }
        foreach (array_keys(Json::entries($raw)) as $key) {
            $key = (string) $key;
            if (!in_array($key, $keys, true)) {
                $errors[] = "$at: \"$key\" is not a key of $what (known: " . implode(', ', $keys) . ')';
            }
        }
        return $raw;
    }

    /** @param list<string> $errors */
    private static function visibility(string $at, \stdClass $object, array &$errors): void
    {
        $v = Json::get($object, 'visibility');
        if (!is_string($v) || !in_array($v, Manifest::PEER_VISIBILITIES, true)) {
            $errors[] = "$at.visibility " . self::quoted($v) . ' must be one of ' . implode(', ', Manifest::PEER_VISIBILITIES)
                . ' (required, no default)';
        }
    }

    /**
     * The paths of the routes this manifest declares with `method`.
     *
     * @return array<string, true>
     */
    private static function declaredPaths(mixed $root, string $method): array
    {
        $paths = [];
        $caps = Json::get($root, 'capabilities');
        if (!Json::isObject($caps)) return $paths;
        foreach (Manifest::objects(Json::get($caps, 'routes')) as $route) {
            $path = Manifest::text(Json::get($route, 'path'));
            if ($method === Manifest::text(Json::get($route, 'method')) && $path !== null) $paths[$path] = true;
        }
        return $paths;
    }

    /** @param list<string> $errors */
    private static function peerReads(?string $slug, mixed $root, array $list, array &$errors): void
    {
        $own = self::peerPort($slug);
        $getPaths = self::declaredPaths($root, 'GET');
        $seen = [];
        foreach ($list as $i => $raw) {
            $at = self::FILE . ": capabilities.peerReads[$i]";
            $p = self::closedObject($at, $raw, Manifest::PEER_READ_KEYS, 'a peer read', $errors);
            if ($p === null) continue;
            $port = Manifest::text(Json::get($p, 'port'));
            if ($port === null || !self::full($own, $port)) {
                $errors[] = "$at.port " . self::quoted(Json::get($p, 'port')) . " must be m.$slug.{name}.v{N} (must match $own)";
            } elseif (isset($seen[$port])) {
                $errors[] = "$at.port \"$port\" is declared twice";
            } else {
                $seen[$port] = true;
            }
            $path = Manifest::text(Json::get($p, 'path'));
            if ($path === null || !isset($getPaths[$path])) {
                $errors[] = "$at.path " . self::quoted(Json::get($p, 'path')) . ' is not a GET route this module declares '
                    . '(capabilities.routes[] with method GET)';
            } elseif (str_contains($path, '{')) {
                self::literalPath($at, $path, $errors); //
            }
            self::visibility($at, $p, $errors);
        }
    }

    /** @param list<string> $errors */
    private static function commands(mixed $root, array $list, array &$errors): void
    {
        $seen = [];
        foreach ($list as $i => $raw) {
            $at = self::FILE . ": capabilities.commands[$i]";
            $c = self::closedObject($at, $raw, Manifest::COMMAND_KEYS, 'a command', $errors);
            if ($c === null) continue;
            $name = Manifest::text(Json::get($c, 'name'));
            if ($name === null || !self::full(self::PEER_NAME, $name)) {
                $errors[] = "$at.name " . self::quoted(Json::get($c, 'name')) . ' must match ' . self::PEER_NAME;
            } elseif (isset($seen[$name])) {
                $errors[] = "$at.name \"$name\" is declared twice";
            } else {
                $seen[$name] = true;
            }
            $method = Manifest::text(Json::get($c, 'method'));
            if ($method === null || !in_array($method, Manifest::COMMAND_METHODS, true)) {
                $errors[] = "$at.method " . self::quoted(Json::get($c, 'method')) . ' must be one of '
                    . implode(', ', Manifest::COMMAND_METHODS);
            } else {
                $path = Manifest::text(Json::get($c, 'path'));
                if ($path === null || !isset(self::declaredPaths($root, $method)[$path])) {
                    $errors[] = "$at.path " . self::quoted(Json::get($c, 'path')) . " is not a $method route this module "
                        . "declares (capabilities.routes[] with method $method)";
                } elseif (str_contains($path, '{')) {
                    self::literalPath($at, $path, $errors); //
                }
            }
            self::visibility($at, $c, $errors);
        }
    }

    /** @param list<string> $errors */
    private static function events(array $list, array &$errors): void
    {
        $seen = [];
        foreach ($list as $i => $raw) {
            $at = self::FILE . ": capabilities.events[$i]";
            $e = self::closedObject($at, $raw, Manifest::EVENT_KEYS, 'an event', $errors);
            if ($e === null) continue;
            $name = Manifest::text(Json::get($e, 'name'));
            if ($name === null || !self::full(self::PEER_NAME, $name)) {
                $errors[] = "$at.name " . self::quoted(Json::get($e, 'name')) . ' must match ' . self::PEER_NAME;
            } elseif (isset($seen[$name])) {
                $errors[] = "$at.name \"$name\" is declared twice";
            } else {
                $seen[$name] = true;
            }
            self::visibility($at, $e, $errors);
        }
    }

    /** @param list<string> $errors */
    private static function peers(?string $slug, mixed $root, array $list, array &$errors): void
    {
        $required = [];
        foreach (Manifest::objects(Json::get($root, 'requires')) as $entry) {
            $s = Json::get($entry, 'slug');
            if (is_string($s)) $required[$s] = true;
        }
        $owners = [];
        foreach ($list as $i => $raw) {
            $at = self::FILE . ": capabilities.peers[$i]";
            $use = self::closedObject($at, $raw, Manifest::PEER_USE_KEYS, 'a use of another module', $errors);
            if ($use === null) continue;
            $owner = Manifest::text(Json::get($use, 'owner'));
            if ($owner === null || !self::matches(self::SLUG, $owner)) {
                $errors[] = "$at.owner " . self::quoted(Json::get($use, 'owner')) . ' is not a module slug (must match '
                    . trim(self::SLUG, '/') . ')';
                $owner = null;
            } elseif ($owner === $slug) {
                $errors[] = "$at.owner \"$owner\" is this module itself";
            } elseif (isset($owners[$owner])) {
                $errors[] = "$at.owner \"$owner\" is declared twice";
            } else {
                $owners[$owner] = true;
                if (!isset($required[$owner])) {
                    $errors[] = "$at.owner \"$owner\" is not in requires (declare it there, mandatory or optional)";
                }
            }
            $reads = self::peerNames("$at.reads", Json::get($use, 'reads'), $owner === null ? null : self::peerPort($owner), $errors);
            $commands = self::peerNames("$at.commands", Json::get($use, 'commands'), self::PEER_NAME, $errors);
            if ($reads === 0 && $commands === 0) {
                $errors[] = "$at must name at least one of reads, commands";
            }
        }
    }

    /** @param list<string> $errors */
    private static function peerNames(string $at, mixed $names, ?string $grammar, array &$errors): int
    {
        if ($names === null) return 0;
        if (!is_array($names)) {
            $errors[] = "$at must be an array";
            return 0;
        }
        $seen = [];
        $valid = 0;
        foreach (array_values($names) as $i => $name) {
            if (!is_string($name) || ($grammar !== null && !self::full($grammar, $name))) {
                $errors[] = "$at" . "[$i] " . self::quoted($name) . ($grammar === null ? ' must be a string' : " must match $grammar");
            } elseif (isset($seen[$name])) {
                $errors[] = "$at" . "[$i] \"$name\" is named twice";
            } else {
                $seen[$name] = true;
                $valid++;
            }
        }
        return $valid;
    }

    /**
     * `capabilities.slots` (protocol 2.0, `schemaVersion` 3 only, sdk-protocol.md §7.12): closed objects; the
     * key grammar (Manifest::SLOT_KEY, at most Manifest::MAX_SLOT_KEY_LENGTH characters, the major as the last segment),
     * unique; `kind` A/B/D/R and a `type` of the kind; `visibility` mandatory (`internal` | `public`); `label` a
     * key of `i18n.en`; the keys of one kind only on that kind; `layerKinds` on B, `events` / `commands` on D after its
     * type, `dataSchema` on R (`m.{owner}.{name}.v{N}`, the owner being this module).
     *
     * @param list<string> $errors
     */
    private static function slots(?string $slug, array $slots, array $enKeys, array &$errors): void
    {
        $seen = [];
        foreach ($slots as $i => $s) {
            $at = self::FILE . ": capabilities.slots[$i]";
            if (!Json::isObject($s)) {
                $errors[] = "$at must be an object";
                continue;
            }
            foreach (array_keys(Json::entries($s)) as $key) {
                $key = (string) $key;
                if (!in_array($key, Manifest::SLOT_KEYS, true)) {
                    $errors[] = "$at: \"$key\" is not a key of a slot (known: " . implode(', ', Manifest::SLOT_KEYS) . ')';
                }
            }
            $key = Manifest::text(Json::get($s, 'key'));
            if ($key === null || self::length($key) > Manifest::MAX_SLOT_KEY_LENGTH || !self::full(Manifest::SLOT_KEY, $key)) {
                $errors[] = "$at.key " . self::quoted(Json::get($s, 'key')) . ' must match ' . Manifest::SLOT_KEY . ' (at most '
                    . Manifest::MAX_SLOT_KEY_LENGTH . ' characters; the major is the last segment, e.g. viewport.layers.v1)';
            } elseif (isset($seen[$key])) {
                $errors[] = "$at.key \"$key\" is declared twice";
            } else {
                $seen[$key] = true;
            }
            $kind = Manifest\SlotKind::of(Json::get($s, 'kind'));
            $type = Json::get($s, 'type');
            if ($kind === null) {
                $errors[] = "$at.kind must be \"A\", \"B\", \"D\" or \"R\" (got " . self::quoted(Json::get($s, 'kind')) . ')';
            } elseif (!is_string($type) || !in_array($type, $kind->types(), true)) {
                $errors[] = "$at.type must be one of [" . implode(', ', $kind->types()) . "] on kind {$kind->value} (got "
                    . self::quoted($type) . ')';
            }
            if (Manifest\SlotVisibility::of(Json::get($s, 'visibility')) === null) {
                $errors[] = "$at.visibility must be \"internal\" or \"public\" (got " . self::quoted(Json::get($s, 'visibility'))
                    . '): the owner chooses it, there is no default';
            }
            if (Json::has($s, 'label') && Json::get($s, 'label') !== null) {
                self::requireKey("$at.label", Manifest::text(Json::get($s, 'label')), $enKeys, $errors);
            }
            if ($kind === null) continue;
            foreach (Manifest\SlotKind::cases() as $other) {
                if ($other === $kind) continue;
                foreach ($other->ownKeys() as $own) {
                    if (Json::has($s, $own) && !in_array($own, $kind->ownKeys(), true)) {
                        $errors[] = "$at.$own is only on kind {$other->value}";
                    }
                }
            }
            switch ($kind) {
                case Manifest\SlotKind::A:
                    if (Json::has($s, 'publicMembers')) self::names("$at.publicMembers", Json::get($s, 'publicMembers'), self::SLOT_MEMBER, false, $errors);
                    if (Json::has($s, 'actions')) self::names("$at.actions", Json::get($s, 'actions'), self::SLOT_NAME, false, $errors);
                    break;
                case Manifest\SlotKind::B:
                    self::names("$at.layerKinds", Json::get($s, 'layerKinds'), self::SLOT_LAYER_KIND, true, $errors);
                    break;
                case Manifest\SlotKind::D:
                    $events = $type === 'events';
                    $own = $events ? 'events' : 'commands';
                    $foreign = $events ? 'commands' : 'events';
                    self::names("$at.$own", Json::get($s, $own), self::SLOT_NAME, true, $errors);
                    if (Json::has($s, $foreign)) {
                        $errors[] = "$at.$foreign is not allowed on a slot of type " . self::printed($type);
                    }
                    break;
                case Manifest\SlotKind::R:
                    $schema = Manifest::text(Json::get($s, 'dataSchema'));
                    if ($schema === null || $slug === null
                        || !str_starts_with($schema, "m.$slug.")
                        || !self::full(self::SLOT_SCHEMA_TAIL, substr($schema, strlen($slug) + 3))) {
                        $errors[] = "$at.dataSchema " . self::quoted(Json::get($s, 'dataSchema')) . ' is required on kind R: the id '
                            . 'm.{owner}.{name}.v{N} of the schema the owner validates the data against, the owner '
                            . "being this module (\"$slug\")";
                    }
                    break;
            }
        }
    }

    /**
     * A list of distinct names, each matching `$grammar`; `$required` = present and non-empty.
     *
     * @param list<string> $errors
     */
    private static function names(string $at, mixed $raw, string $grammar, bool $required, array &$errors): void
    {
        if ($raw === null && !$required) return;
        if (!is_array($raw) || ($required && count($raw) === 0)) {
            $errors[] = "$at is required: a non-empty array of names matching $grammar";
            return;
        }
        $seen = [];
        foreach ($raw as $name) {
            if (!is_string($name) || !self::full($grammar, $name)) {
                $errors[] = "$at: " . self::quoted($name) . " must match $grammar";
            } elseif (isset($seen[$name])) {
                $errors[] = "$at: \"$name\" is declared twice";
            } else {
                $seen[$name] = true;
            }
        }
    }

    /**
     * `requires` (protocol 2.0): the shape the platform checks at upload
     * (`ModulePackValidator.validateRequires`), with its messages -- a slug, never the module itself, once, a
     * non-empty range in the platform's grammar (`VersionRange`); `requires[i].optional` only under `schemaVersion` 3,
     * and then a boolean. Whether the required module exists, its range is satisfied, a cycle appears or holds
     * are the platform's (they need the registry). Checked under every `schemaVersion`.
     *
     * @param list<string> $errors
     */
    private static function requires(\stdClass $root, int $schemaVersion, array &$errors): void
    {
        $f = self::FILE;
        $requires = Json::get($root, 'requires');
        if (!is_array($requires)) return;
        $self = Manifest::text(Json::get($root, 'slug'));
        $seen = [];
        foreach (array_values($requires) as $i => $entry) {
            if (!Json::isObject($entry)) {
                $errors[] = "$f: \"requires\" contains a non-object entry";
                continue;
            }
            if (Json::has($entry, 'optional')) {
                if ($schemaVersion < 3) {
                    $errors[] = "$f: \"requires[$i].optional\" requires \"schemaVersion\": 3 (this manifest is "
                        . "schemaVersion $schemaVersion)";
                } elseif (!is_bool(Json::get($entry, 'optional'))) {
                    $errors[] = "$f: \"requires[$i].optional\" must be a boolean (got " . self::quoted(Json::get($entry, 'optional')) . ')';
                }
            }
            $slug = Manifest::text(Json::get($entry, 'slug'));
            if (!self::matches(self::SLUG, $slug)) {
                $errors[] = "$f: \"requires\" slug must match " . trim(self::SLUG, '/') . ' but was ' . self::quoted(Json::get($entry, 'slug'));
                continue;
            }
            if ($slug === $self) $errors[] = "$f: a module cannot require itself (\"$slug\")";
            if (isset($seen[$slug])) $errors[] = "$f: duplicate \"requires\" entry for \"$slug\"";
            $seen[$slug] = true;
            $range = Manifest::text(Json::get($entry, 'versionRange'));
            if (self::blank($range)) {
                $errors[] = "$f: \"requires\" entry for \"$slug\" needs a non-empty \"versionRange\"";
                continue;
            }
            $problem = self::versionRangeProblem($range);
            if ($problem !== null) $errors[] = "$f: \"requires\" entry for \"$slug\": $problem";
        }
    }

    /**
     * `featureFlags[i].requiresModules` (protocol 2.0): only under `schemaVersion` 3; an array of module
     * slugs, never the module itself, each one declared in `requires` (mandatory or optional). The platform's messages
     * (`ModuleManifestValidator`).
     *
     * @param list<string> $errors
     */
    private static function flagRequiresModules(\stdClass $root, int $schemaVersion, array &$errors): void
    {
        $flags = Json::get($root, 'featureFlags');
        if (!is_array($flags)) return;
        $self = Manifest::text(Json::get($root, 'slug'));
        $required = [];
        foreach (Manifest::objects(Json::get($root, 'requires')) as $entry) {
            if (is_string(Json::get($entry, 'slug'))) $required[Json::get($entry, 'slug')] = true;
        }
        foreach (array_values($flags) as $i => $flag) {
            if (!Json::isObject($flag)) continue;
            if (!Json::has($flag, 'requiresModules') || Json::get($flag, 'requiresModules') === null) continue;
            $at = self::FILE . ": \"featureFlags[$i].requiresModules\"";
            if ($schemaVersion < 3) {
                $errors[] = "$at requires \"schemaVersion\": 3 (this manifest is schemaVersion $schemaVersion)";
                continue;
            }
            $modules = Json::get($flag, 'requiresModules');
            if (!is_array($modules)) {
                $errors[] = "$at must be an array of module slugs";
                continue;
            }
            foreach ($modules as $module) {
                if (!is_string($module) || !self::matches(self::SLUG, $module)) {
                    $errors[] = "$at: " . self::quoted($module) . ' is not a module slug (must match ' . trim(self::SLUG, '/') . ')';
                } elseif ($module === $self) {
                    $errors[] = "$at: \"$module\" is this module itself";
                } elseif (!isset($required[$module])) {
                    $errors[] = "$at: \"$module\" is not in requires (declare it there, mandatory or optional)";
                }
            }
        }
    }

    /**
     * The platform's grammar of `requires[].versionRange` (`VersionRange.parse`), copied: `*` or blank = any; one
     * or two space-separated bounds `>=`, `>`, `<=`, `<`, `=` or a bare version (exact); at most one lower and one upper
     * bound, an exact version alone; the bounds must not cross.
     *
     * @return ?string the platform's message when `$raw` is not a range, else null
     */
    private static function versionRangeProblem(?string $raw): ?string
    {
        $text = $raw === null ? '' : self::javaTrim($raw);
        if ($text === '' || $text === '*') return null;
        $lower = null;
        $lowerInclusive = false;
        $upper = null;
        $upperInclusive = false;
        foreach (preg_split('/[ \t\n\x0B\f\r]+/', $text) as $token) {
            if ($token === '') continue;
            if (str_starts_with($token, '>=')) { $side = 'lower'; $version = substr($token, 2); $inclusive = true; }
            elseif (str_starts_with($token, '<=')) { $side = 'upper'; $version = substr($token, 2); $inclusive = true; }
            elseif (str_starts_with($token, '>')) { $side = 'lower'; $version = substr($token, 1); $inclusive = false; }
            elseif (str_starts_with($token, '<')) { $side = 'upper'; $version = substr($token, 1); $inclusive = false; }
            elseif (str_starts_with($token, '=')) { $side = 'exact'; $version = substr($token, 1); $inclusive = true; }
            else { $side = 'exact'; $version = $token; $inclusive = true; }
            $version = self::javaTrim($version);
            if (!self::matches(self::SEMVER, $version)) {
                return self::rangeMalformed($raw, "'$version' is not a semantic version");
            }
            switch ($side) {
                case 'lower':
                    if ($lower !== null) return self::rangeMalformed($raw, 'two lower bounds');
                    $lower = $version;
                    $lowerInclusive = $inclusive;
                    break;
                case 'upper':
                    if ($upper !== null) return self::rangeMalformed($raw, 'two upper bounds');
                    $upper = $version;
                    $upperInclusive = $inclusive;
                    break;
                default:
                    if ($lower !== null || $upper !== null) {
                        return self::rangeMalformed($raw, 'an exact version combined with another bound');
                    }
                    $lower = $version;
                    $upper = $version;
                    $lowerInclusive = true;
                    $upperInclusive = true;
            }
        }
        if ($lower !== null && $upper !== null) {
            $comparison = self::compareSemver($lower, $upper);
            if ($comparison > 0 || ($comparison === 0 && !($lowerInclusive && $upperInclusive))) {
                return 'version range is impossible: lower bound ' . ($lowerInclusive ? '>=' : '>') . $lower
                    . ' is above upper bound ' . ($upperInclusive ? '<=' : '<') . $upper
                    . ', so no version could ever satisfy it';
            }
        }
        return null;
    }

    private static function rangeMalformed(?string $raw, string $why): string
    {
        return "version range '$raw' could not be parsed ($why); " . self::VERSION_RANGE_EXPECTED_FORM;
    }

    /** Java's `String.trim()`: the characters up to U+0020 at both ends. */
    private static function javaTrim(string $s): string
    {
        return trim($s, "\x00..\x20");
    }

    /** A value as Java's `String.valueOf` prints it in a message (null as "null", a string as it is). */
    private static function printed(mixed $value): string
    {
        if ($value === null) return 'null';
        if (is_string($value)) return $value;
        return Json::text($value) ?? 'null';
    }

    /**
     * `rollbackFloor`: optional; a semver at or below `version`. When `version` itself is not
     * semver, only the floor's own shape is checked (the version is the platform's check at upload).
     *
     * @param list<string> $errors
     */
    private static function rollbackFloor(\stdClass $root, array &$errors): void
    {
        if (!Json::has($root, 'rollbackFloor') || Json::get($root, 'rollbackFloor') === null) return;
        $f = self::FILE;
        $raw = Json::get($root, 'rollbackFloor');
        if (!is_string($raw) || !self::matches(self::SEMVER, $raw)) {
            $errors[] = "$f: \"rollbackFloor\" must be a semver 2.0 version, e.g. \"1.1.0\" (got " . self::quoted($raw) . ')';
            return;
        }
        $floor = $raw;
        $version = Manifest::text(Json::get($root, 'version'));
        if ($version !== null && self::matches(self::SEMVER, $version) && self::compareSemver($floor, $version) > 0) {
            $errors[] = "$f: \"rollbackFloor\" \"$floor\" is above \"version\" \"$version\""
                . ": the floor is the oldest version that runs on this version's data, so at most the version itself";
        }
    }

    /**
     * Semver 2.0 precedence of two versions that match `SEMVER` (the platform's `SemVer.compareTo`): the numeric core
     * first, then a pre-release below its release, then the pre-release identifiers one by one (numeric below
     * alphanumeric, numeric by value, alphanumeric by text, a shorter list below a longer one). The build part is
     * ignored. Numbers are compared by length, then by text: semver forbids leading zeros, so no size limit applies.
     */
    private static function compareSemver(string $a, string $b): int
    {
        if (preg_match(self::SEMVER, $a, $ma) !== 1 || preg_match(self::SEMVER, $b, $mb) !== 1) {
            throw new \InvalidArgumentException("not semver: $a, $b");
        }
        for ($i = 1; $i <= 3; $i++) {
            $c = self::compareNumeric($ma[$i], $mb[$i]);
            if ($c !== 0) return $c;
        }
        $preA = isset($ma[4]) && $ma[4] !== '' ? $ma[4] : null;
        $preB = isset($mb[4]) && $mb[4] !== '' ? $mb[4] : null;
        if ($preA === null || $preB === null) {
            if ($preA === null && $preB === null) return 0;
            return $preA === null ? 1 : -1;
        }
        $idsA = explode('.', $preA);
        $idsB = explode('.', $preB);
        for ($i = 0; $i < min(count($idsA), count($idsB)); $i++) {
            $numA = preg_match('/^\d+$/D', $idsA[$i]) === 1;
            $numB = preg_match('/^\d+$/D', $idsB[$i]) === 1;
            if ($numA && $numB) $c = self::compareNumeric($idsA[$i], $idsB[$i]);
            elseif ($numA) $c = -1;
            elseif ($numB) $c = 1;
            else $c = strcmp($idsA[$i], $idsB[$i]) <=> 0;
            if ($c !== 0) return $c;
        }
        return count($idsA) <=> count($idsB);
    }

    private static function compareNumeric(string $a, string $b): int
    {
        return strlen($a) !== strlen($b) ? strlen($a) <=> strlen($b) : strcmp($a, $b) <=> 0;
    }

    private static function forbiddenKeys(\stdClass $object, string $prefix, array &$errors): void
    {
        foreach (array_keys(Json::entries($object)) as $key) {
            $rule = self::forbiddenRule((string) $key);
            if ($rule !== null) $errors[] = self::FILE . ": \"$prefix$key\" is not allowed: $rule";
        }
    }

    private static function forbiddenRule(string $key): ?string
    {
        $lower = strtolower($key);
        if (in_array($lower, self::DDL_KEYS, true)) {
            return 'a module cannot run DDL outside kf_modules; extension tables are declared in capabilities.fields and created by the platform';
        }
        if (in_array($lower, self::DICTIONARY_KEYS, true)) {
            return 'a module cannot write the platform dictionary; texts are declared in "i18n" and the platform writes them at install';
        }
        return null;
    }

    /** @return array<string, true> the `en` keys with a non-blank text */
    private static function i18n(mixed $raw, array $knownLanguages, array &$errors, array &$warnings): array
    {
        $f = self::FILE;
        $enKeys = [];
        if ($raw === null) {
            $errors[] = "$f: \"i18n\" is required for \"schemaVersion\": 2 and must contain \"en\"";
            return $enKeys;
        }
        if (!Json::isObject($raw)) {
            $errors[] = "$f: \"i18n\" must be an object of language -> (key -> text)";
            return $enKeys;
        }
        $languages = Json::entries($raw);
        if (!array_key_exists('en', $languages)) {
            $errors[] = "$f: \"i18n\" must contain \"en\": every text needs an English version";
        }
        $unknown = [];
        foreach ($languages as $lang => $value) {
            $lang = (string) $lang;
            $at = "$f: i18n.$lang";
            if (!self::matches(self::LANGUAGE_CODE, $lang)) {
                $errors[] = "$at is not a language code (expected e.g. \"en\", \"ro\", \"pt-BR\")";
                continue;
            }
            if (!Json::isObject($value)) {
                $errors[] = "$at must be an object of key -> text";
                continue;
            }
            if ($knownLanguages !== [] && !in_array($lang, $knownLanguages, true)) $unknown[$lang] = true;
            foreach (Json::entries($value) as $key => $text) {
                $key = (string) $key;
                if (!self::matches(self::KEY, $key)) {
                    $errors[] = "$at.$key: a key must match " . trim(self::KEY, '/');
                } elseif (!is_string($text)) {
                    $errors[] = "$at.$key must be a string";
                } elseif (trim($text) === '') {
                    $errors[] = "$at.$key must not be blank";
                } elseif (self::length($text) > self::MAX_TEXT_LENGTH) {
                    $errors[] = "$at.$key exceeds " . self::MAX_TEXT_LENGTH . ' characters';
                } elseif ($lang === 'en') {
                    $enKeys[$key] = true;
                }
            }
        }
        foreach ($languages as $lang => $value) {
            $lang = (string) $lang;
            if ($lang === 'en' || !self::matches(self::LANGUAGE_CODE, $lang)) continue;
            foreach (array_keys(Json::entries($value)) as $key) {
                $key = (string) $key;
                if (self::matches(self::KEY, $key) && !isset($enKeys[$key])) {
                    $errors[] = "$f: i18n.$lang.$key has no \"en\" text; every key must be defined in \"en\"";
                }
            }
        }
        if ($unknown !== []) {
            $u = array_keys($unknown);
            sort($u, SORT_STRING);
            $served = $knownLanguages;
            sort($served, SORT_STRING);
            $warnings[] = "$f: i18n declares " . implode(', ', $u) . ', not served by this platform (served: '
                . implode(', ', $served) . '); those texts stay in the manifest';
        }
        return $enKeys;
    }

    private static function requireKey(string $at, mixed $key, array $enKeys, array &$errors): void
    {
        if (self::blank($key)) {
            $errors[] = "$at is required: a translation key defined in i18n.en";
        } elseif (!self::matches(self::KEY, $key)) {
            $errors[] = "$at \"$key\" is not a translation key (must match " . trim(self::KEY, '/') . '); in schemaVersion 2 texts live in "i18n"';
        } elseif (!isset($enKeys[$key])) {
            $errors[] = "$at \"$key\" is not defined in i18n.en";
        }
    }

    private static function fields(array $fields, array $enKeys, array &$errors): void
    {
        $seen = [];
        foreach ($fields as $i => $f) {
            $at = self::FILE . ": capabilities.fields[$i]";
            if (!Json::isObject($f)) {
                $errors[] = "$at must be an object";
                continue;
            }
            $entity = Manifest::text(Json::get($f, 'entity'));
            $name = Manifest::text(Json::get($f, 'name'));
            if (self::blank($entity)) {
                $errors[] = "$at.entity is required (model, order, customer, ...: an extensible entity of the platform)";
            }
            if (!self::matches(self::NAME, $name)) {
                $errors[] = "$at.name must match " . trim(self::NAME, '/') . ' (got ' . self::quoted(Json::get($f, 'name')) . ')';
            } elseif ($entity !== null) {
                $id = "$entity.$name";
                if (isset($seen[$id])) $errors[] = "$at: field \"$id\" is declared twice";
                $seen[$id] = true;
            }
            $table = Manifest::text(Json::get($f, 'table'));
            if ($table !== null && str_contains($table, '.')) {
                $errors[] = "$at.table " . self::quoted($table) . ' is not allowed: a module cannot run DDL outside kf_modules';
            } elseif (!self::matches(self::NAME, $table)) {
                $errors[] = "$at.table must match " . trim(self::NAME, '/') . ' (got ' . self::quoted(Json::get($f, 'table')) . ')';
            }
            if (!in_array(Manifest::text(Json::get($f, 'type')), self::FIELD_TYPES, true)) {
                $errors[] = "$at.type must be one of " . implode(', ', self::FIELD_TYPES) . ' (got ' . self::quoted(Json::get($f, 'type')) . ')';
            }
            self::requireKey("$at.label", Manifest::text(Json::get($f, 'label')), $enKeys, $errors);
            // editable, when present, is a JSON boolean (null is refused too)
            if (Json::has($f, 'editable') && !is_bool(Json::get($f, 'editable'))) {
                $errors[] = "$at.editable must be a boolean (got " . self::quoted(Json::get($f, 'editable')) . ')';
            }
        }
    }

    private static function hooks(mixed $root, int $schemaVersion, array $hooks, array &$errors): void
    {
        $seen = [];
        foreach ($hooks as $i => $h) {
            $at = self::FILE . ": capabilities.hooks[$i]";
            if (!Json::isObject($h)) {
                $errors[] = "$at must be an object";
                continue;
            }
            $point = Manifest::text(Json::get($h, 'point'));
            if ($point !== null && str_starts_with($point, HookEvents::MODULE_EVENT_PREFIX)) {
                self::moduleEventHook($root, $schemaVersion, $at, $point, $h, $seen, $errors); //
                continue;
            }
            if (self::blank($point)) {
                $errors[] = "$at.point is required (a hook point id, e.g. model.before.update)";
            } elseif (isset($seen[$point])) {
                $errors[] = "$at.point \"$point\" is declared twice";
            } else {
                $seen[$point] = true;
            }
            if (Json::get($h, 'onFailure') !== null && $point !== null && in_array($point, FlowEvents::TYPES, true)) {
                // protocol 2.0 (§16.3, the platform's text): an event of the flow engine blocks nothing
                $errors[] = "$at.onFailure is not allowed on the flow event \"$point\": it runs after the step and cannot block it";
            } elseif (Json::get($h, 'onFailure') !== null) {
                $onFailure = Manifest::text(Json::get($h, 'onFailure'));
                if (!in_array($onFailure, self::ON_FAILURE, true)) {
                    $errors[] = "$at.onFailure must be one of allow, block (got " . self::quoted(Json::get($h, 'onFailure')) . ')';
                } elseif ($point !== null && !str_contains($point, '.before.')) {
                    $errors[] = "$at.onFailure only on before points: \"$point\" runs after the mutation and cannot block it";
                }
            }
        }
    }

    /**
     * `capabilities.hooks[i]` on `m.{owner}.after.{name}` (the platform's
     * `validateModuleEventHook`): schema 3, the grammar, the owner in `requires` (mandatory or optional), no
     * `onFailure`; the point declared once.
     *
     * @param array<string, bool> $seen
     * @param list<string> $errors
     */
    private static function moduleEventHook(mixed $root, int $schemaVersion, string $at, string $point, mixed $hook,
        array &$seen, array &$errors): void
    {
        if ($schemaVersion < 3) {
            $errors[] = "$at.point " . self::quoted($point) . ': an event of a module requires "schemaVersion": 3';
            return;
        }
        if (preg_match('/' . HookEvents::MODULE_EVENT_POINT . '/D', $point, $m) !== 1) {
            $errors[] = "$at.point " . self::quoted($point) . ' is not an event of a module (m.{owner}.after.{name})';
            return;
        }
        $owner = $m[1];
        if (isset($seen[$point])) {
            $errors[] = "$at.point \"$point\" is declared twice";
        } else {
            $seen[$point] = true;
        }
        $required = false;
        $requires = Json::get($root, 'requires');
        if (is_array($requires)) {
            foreach ($requires as $r) {
                if (Json::isObject($r) && Manifest::text(Json::get($r, 'slug')) === $owner) $required = true;
            }
        }
        if (!$required) {
            $errors[] = "$at.point \"$point\" is an event of $owner, which this manifest does not require (requires, mandatory or optional)";
        }
        if (Json::get($hook, 'onFailure') !== null) {
            $errors[] = "$at.onFailure is not allowed on an event of a module: it runs after the fact";
        }
    }

    private static function routes(?string $slug, array $routes, array &$errors): void
    {
        $seen = [];
        $prefix = '/m/' . ($slug ?? 'null') . '/';
        $rightPrefix = 'api.v1.m.' . ($slug ?? 'null') . '.';
        foreach ($routes as $i => $r) {
            $at = self::FILE . ": capabilities.routes[$i]";
            if (!Json::isObject($r)) {
                $errors[] = "$at must be an object";
                continue;
            }
            $method = Manifest::text(Json::get($r, 'method'));
            $path = Manifest::text(Json::get($r, 'path'));
            $route = "$at (" . ($method ?? 'null') . ' ' . ($path ?? 'null') . ')';
            if ($method === null || !in_array($method, self::HTTP_METHODS, true)) {
                $errors[] = "$route.method must be one of " . implode(', ', self::HTTP_METHODS);
            }
            if ($path === null || !str_starts_with($path, $prefix) || strlen($path) === strlen($prefix) || str_ends_with($path, '/')
                || str_contains($path, '..') || str_contains($path, '//') || str_contains($path, '?') || str_contains($path, '#')
                || str_contains($path, '*') || str_contains($path, '%')) {
                $errors[] = "$route.path must be under $prefix without \"..\", \"//\", \"%\", \"*\", a query, a fragment or a trailing \"/\"";
            }
            $right = Manifest::text(Json::get($r, 'right'));
            if ($right === null || !str_starts_with($right, $rightPrefix)) {
                $errors[] = "$route.right must be in $rightPrefix* (got " . self::quoted(Json::get($r, 'right')) . ')';
            } else {
                $segments = explode('.', substr($right, strlen($rightPrefix)));
                $ok = count($segments) >= 1 && count($segments) <= 4;
                foreach ($segments as $s) $ok = $ok && self::matches(self::RIGHT_SEGMENT, $s);
                if (!$ok) {
                    $errors[] = "$route.right " . self::quoted($right) . " must be $rightPrefix followed by 1 to 4 segments matching "
                        . trim(self::RIGHT_SEGMENT, '/');
                }
            }
            if ($method !== null && $path !== null) {
                $id = "$method $path";
                if (isset($seen[$id])) $errors[] = "$route is declared twice";
                $seen[$id] = true;
            }
            self::roles($route, $r, $errors);
            self::admin($route, $r, $errors);
            self::timeoutSeconds($route, $r, $errors);
            self::audience($route, $r, $errors);
            self::portal($route, $r, $errors);
            // hookable is a boolean, false when absent, and only on the methods that write
            if (Json::has($r, 'hookable')) {
                $hookable = Json::get($r, 'hookable');
                if (!is_bool($hookable)) {
                    $errors[] = "$route.hookable must be a boolean (got " . self::quoted($hookable) . ')';
                } elseif ($hookable && !in_array($method, Manifest::HOOKABLE_METHODS, true)) {
                    $errors[] = "$route.hookable: " . self::HOOKABLE_RULE . ' (a hook calls a route that writes; '
                        . ($method ?? 'null') . ' cannot be hookable)';
                }
            }
        }
    }

    /**
     * `routes[].admin` (protocol 1.1): `tenant` or `platform`; a `platform` route declares no roles (only
     * the platform administrator holds its right); never on a `hookable` route (a hook calls as the system, an admin
     * route is a person's).
     */
    private static function admin(string $route, \stdClass $r, array &$errors): void
    {
        $admin = Json::get($r, 'admin');
        if ($admin === null) return;
        if (!in_array(Manifest::text($admin), Manifest::ROUTE_ADMIN, true)) {
            $errors[] = "$route.admin must be one of tenant, platform (got " . self::quoted($admin) . ')';
            return;
        }
        $roles = Json::get($r, 'roles');
        if ($admin === 'platform' && is_array($roles) && $roles !== []) {
            $errors[] = "$route.admin \"platform\" declares no roles: only the platform administrator holds the right";
        }
        if (Json::get($r, 'hookable') === true) {
            $errors[] = "$route: admin and hookable exclude each other (a hook calls a route as the system; an admin "
                . "route is a person's)";
        }
    }

    /**
     * `routes[].timeoutSeconds` (SDK 0.7.3): when present and not null, a JSON whole number
     * from 1 to Manifest::MAX_ROUTE_TIMEOUT_SECONDS -- how long the platform waits for the module's answer on this
     * route. The platform's rule and text (ModuleManifestValidator).
     */
    private static function timeoutSeconds(string $route, \stdClass $r, array &$errors): void
    {
        $value = Json::get($r, 'timeoutSeconds');
        if ($value === null) return;
        $seconds = Manifest::wholeSeconds($value);
        if ($seconds === null || $seconds < 1 || $seconds > Manifest::MAX_ROUTE_TIMEOUT_SECONDS) {
            $errors[] = "$route.timeoutSeconds must be a whole number of seconds between 1 and "
                . Manifest::MAX_ROUTE_TIMEOUT_SECONDS . ' (got ' . self::quoted($value) . ')';
        }
    }

    /**
     * `activation` (protocol 1.2): `"tenant"` or `"platform"`; absent or null = `tenant`.
     * Checked under every `schemaVersion`, like `rollbackFloor`. The platform's text (ModuleManifestValidator).
     */
    private static function activation(\stdClass $root, array &$errors): void
    {
        $value = Json::get($root, 'activation');
        if ($value === null || $value === 'tenant' || $value === 'platform') return;
        $errors[] = self::FILE . ': activation must be "tenant" or "platform" (got ' . self::quoted($value) . ')';
    }

    /**
     * `capabilities.storage` (protocol 1.2): at most Manifest::MAX_STORAGE_ELEMENTS element, a
     * closed object `{contentTypes, maxBytes}`; `contentTypes` 1 to Manifest::MAX_STORAGE_CONTENT_TYPES distinct
     * `type/subtype` without a wildcard (Manifest::STORAGE_CONTENT_TYPE); `maxBytes` a whole number from 1 to
     * Manifest::MAX_STORAGE_BYTES. The platform's texts (ModuleManifestValidator).
     */
    private static function storage(mixed $raw, array &$errors): void
    {
        if (!is_array($raw)) return; // absent = []; a non-array is reported with the capabilities
        if (count($raw) > Manifest::MAX_STORAGE_ELEMENTS) {
            $errors[] = self::FILE . ': capabilities.storage holds at most one element';
        }
        foreach (array_values($raw) as $i => $s) {
            $at = self::FILE . ": capabilities.storage[$i]";
            if (!Json::isObject($s)) {
                $errors[] = "$at must be an object";
                continue;
            }
            foreach (array_keys(Json::entries($s)) as $key) {
                $key = (string) $key;
                if (!in_array($key, Manifest::STORAGE_KEYS, true)) {
                    $errors[] = "$at: \"$key\" is not a key of a storage element (known: " . implode(', ', Manifest::STORAGE_KEYS) . ')';
                }
            }
            if (!self::contentTypes(Json::get($s, 'contentTypes'))) {
                $errors[] = "$at.contentTypes must be 1 to " . Manifest::MAX_STORAGE_CONTENT_TYPES
                    . ' distinct type/subtype values, without wildcards';
            }
            $bytes = Manifest::wholeSeconds(Json::get($s, 'maxBytes'));
            if ($bytes === null || $bytes < 1 || $bytes > Manifest::MAX_STORAGE_BYTES) {
                $errors[] = "$at.maxBytes must be a whole number of bytes between 1 and " . Manifest::MAX_STORAGE_BYTES
                    . ' (got ' . self::quoted(Json::get($s, 'maxBytes')) . ')';
            }
        }
    }

    private static function contentTypes(mixed $raw): bool
    {
        if (!is_array($raw) || count($raw) === 0 || count($raw) > Manifest::MAX_STORAGE_CONTENT_TYPES) return false;
        $seen = [];
        foreach ($raw as $type) {
            if (!is_string($type) || !self::full(Manifest::STORAGE_CONTENT_TYPE, $type) || isset($seen[$type])) return false;
            $seen[$type] = true;
        }
        return true;
    }

    /**
     * `routes[].audience` (protocol 1.2): `"member"` -- any member of the active tenant calls the
     * route; absent or null = an ordinary route. A member route declares no `roles` (a non-empty list), no `admin` and is
     * not `hookable`: no role is proposed for it, it is not an administration and a hook does not call it. The platform's
     * texts.
     */
    private static function audience(string $route, \stdClass $r, array &$errors): void
    {
        $value = Json::get($r, 'audience');
        if ($value === null) return;
        if ($value !== Manifest::ROUTE_AUDIENCE_MEMBER) {
            $errors[] = "$route.audience must be \"member\" (got " . self::quoted($value) . ')';
            return;
        }
        $roles = Json::get($r, 'roles');
        if ((is_array($roles) && count($roles) > 0) || Json::get($r, 'admin') !== null || Json::get($r, 'hookable') === true) {
            $errors[] = "$route: audience member excludes roles, admin and hookable (any member of the tenant calls it)";
        }
    }

    /**
     * `routes[].portal` (protocol 1.2): when present, a JSON boolean (null is refused), and true
     * only on `audience: member`; absent = false. The platform's text.
     */
    private static function portal(string $route, \stdClass $r, array &$errors): void
    {
        if (!Json::has($r, 'portal')) return;
        $value = Json::get($r, 'portal');
        if (!is_bool($value)) {
            $errors[] = "$route.portal must be a boolean, and only on audience member (got " . self::quoted($value) . ')';
        } elseif ($value && Json::get($r, 'audience') !== Manifest::ROUTE_AUDIENCE_MEMBER) {
            $errors[] = "$route.portal must be a boolean, and only on audience member (got true, audience "
                . self::quoted(Json::get($r, 'audience')) . ')';
        }
    }

    private static function roles(string $route, \stdClass $r, array &$errors): void
    {
        if (!Json::has($r, 'roles') || Json::get($r, 'roles') === null) return; // absent = [] = no role
        $roles = Json::get($r, 'roles');
        if (!is_array($roles)) {
            $errors[] = "$route.roles must be an array of role names";
            return;
        }
        $at = "$route.roles";
        if (count($roles) > self::MAX_ROLES_PER_ROUTE) {
            $errors[] = "$at declares " . count($roles) . ' roles; at most ' . self::MAX_ROLES_PER_ROUTE . ' are allowed per route';
        }
        $seen = [];
        foreach ($roles as $role) {
            if (self::blank($role)) {
                $errors[] = "$at: a role name must be a non-empty string";
            } elseif (self::length($role) > self::MAX_ROLE_NAME_LENGTH) {
                $errors[] = "$at: a role name exceeds " . self::MAX_ROLE_NAME_LENGTH . ' characters';
            } elseif (preg_match('/[\x{0000}-\x{001F}\x{007F}-\x{009F}]/u', $role) === 1) {
                $errors[] = "$at: a role name must not contain control characters";
            } elseif (isset($seen[$role])) {
                $errors[] = "$at: \"$role\" is declared twice";
            } else {
                $seen[$role] = true;
                if (in_array(strtoupper($role), self::FORBIDDEN_ROLES, true)) {
                    $errors[] = "$at: \"$role\" cannot be declared: module routes are excluded from TENANT_ADMIN and never name SUPER_ADMIN";
                }
            }
            // whether the name is an existing global role is the platform's check (a warning at upload)
        }
    }

    /** The kinds a contribution to a slot of a module may have (§7.12): A react|wc, B layer, R data. */
    private const SLOT_CONTRIBUTION_KINDS = ['react', 'wc', 'layer', 'data'];

    private static function surfaces(?string $moduleSlug, mixed $root, int $schemaVersion, array $surfaces, array $enKeys,
                                     array &$errors): void
    {
        $pages = [];
        foreach ($surfaces as $i => $s) {
            $at = self::FILE . ": capabilities.surfaces[$i]";
            if (!Json::isObject($s)) {
                $errors[] = "$at must be an object";
                continue;
            }
            $slotSlug = Json::get($s, 'slug');
            if (is_string($slotSlug) && str_starts_with($slotSlug, 'm.')) {
                // protocol 2.0 (§7.12): a contribution to the slot of another module, the platform's
                // rules and messages (ModuleManifestValidator); whether the owner declares it is, the
                // platform's at registration
                self::slotContribution($at, $moduleSlug, $root, $schemaVersion, $slotSlug, Json::get($s, 'kind'), $errors);
                if (Json::get($s, 'label') !== null) self::requireKey("$at.label", Manifest::text(Json::get($s, 'label')), $enKeys, $errors);
                continue;
            }
            if (self::blank(Json::get($s, 'slug'))) $errors[] = "$at.slug is required";
            $kind = Manifest::text(Json::get($s, 'kind'));
            if (in_array($kind, self::SLOT_CONTRIBUTION_KINDS, true) && !in_array($kind, self::SURFACE_KINDS, true)) {
                $errors[] = "$at.kind \"$kind\" is only for a slot of a module (m.{owner}.{key}); a host surface takes react or wc";
            } elseif (!in_array($kind, self::SURFACE_KINDS, true)) {
                $errors[] = "$at.kind must be one of react, wc (got " . self::quoted(Json::get($s, 'kind')) . ')';
            }
            if (Json::get($s, 'label') !== null) self::requireKey("$at.label", Manifest::text(Json::get($s, 'label')), $enKeys, $errors);
            if (Json::get($s, 'slug') === Manifest::ORDERFLOW_STEP) {
                // protocol 2.0 (§16.5, the platform's text): the contribution names the step it renders
                $stepKey = Json::get($s, 'key');
                if ($stepKey === null || (is_string($stepKey) && trim($stepKey) === '')) {
                    $errors[] = "$at on \"" . Manifest::ORDERFLOW_STEP . '" needs a "key": the key of the step it renders';
                } elseif (!self::full(self::STEP_KEY, $stepKey)) {
                    $errors[] = "$at.key " . self::quoted($stepKey) . ' must match ' . self::STEP_KEY;
                }
            }
            // protocol 1.1: the page of a page surface — its grammar, then (0.7.1) the type
            // of the surface in the pinned release list
            $page = Json::get($s, 'page');
            if ($page !== null && !self::full(self::PAGE, $page)) {
                $errors[] = "$at.page " . self::quoted($page) . ' must match ' . self::PAGE;
                continue;
            }
            $surfaceSlug = Json::get($s, 'slug');
            self::pageType($at, is_string($surfaceSlug) ? $surfaceSlug : null, $page, $moduleSlug, $pages, $errors);
        }
    }

    /**
     * A contribution to `m.{owner}.{key}` (§7.12): schema 3 only; a well-formed key whose owner is the second
     * segment; never the module itself; the owner in `requires` (mandatory or optional); a kind of the protocol.
     */
    private static function slotContribution(string $at, ?string $moduleSlug, mixed $root, int $schemaVersion,
                                             string $slotSlug, mixed $kind, array &$errors): void
    {
        if ($schemaVersion < 3) {
            $errors[] = "$at.slug \"$slotSlug\": a contribution to a slot of a module requires \"schemaVersion\": 3 "
                . "(this manifest is schemaVersion $schemaVersion)";
            return;
        }
        $rest = substr($slotSlug, 2);
        $dot = strpos($rest, '.');
        $owner = $dot !== false && $dot > 0 ? substr($rest, 0, $dot) : null;
        $key = $dot !== false && $dot > 0 ? substr($rest, $dot + 1) : null;
        if ($owner === null || $key === null || !self::matches(self::SLUG, $owner)
            || self::length($key) > Manifest::MAX_SLOT_KEY_LENGTH || !self::full(Manifest::SLOT_KEY, $key)) {
            $errors[] = "$at.slug \"$slotSlug\" is not a slot key m.{owner}.{key} (the owner is a module slug, the "
                . 'key ends with its major, e.g. m.kf-3d.viewport.layers.v1)';
        } elseif ($owner === $moduleSlug) {
            $errors[] = "$at.slug \"$slotSlug\" is a slot of this module itself; a module does not contribute to its "
                . 'own slots';
        } elseif (!in_array($owner, self::requiredSlugs($root), true)) {
            $errors[] = "$at.slug \"$slotSlug\": the owner \"$owner\" is not in requires (declare it there, "
                . 'mandatory or optional)';
        }
        if (!in_array(Manifest::text($kind), self::SLOT_CONTRIBUTION_KINDS, true)) {
            $errors[] = "$at.kind must be one of react, wc, layer, data (got " . self::quoted($kind) . ')';
        }
    }

    /**
     * The slugs of `requires[]`, mandatory or optional.
     *
     * @return list<string>
     */
    private static function requiredSlugs(mixed $root): array
    {
        $required = [];
        foreach (self::items(Json::get($root, 'requires')) as $entry) {
            if (Json::isObject($entry) && is_string(Json::get($entry, 'slug'))) $required[] = Json::get($entry, 'slug');
        }
        return $required;
    }

    /**
     * `surfaces[].page` against the type of its surface (0.7.1; the platform's rule): only a
     * surface of type `page` takes one, it always does, and one module serves each page once (the host opens
     * `/m/{slug}/{page}`). A surface the pinned release list does not declare is left to the platform.
     */
    private static function pageType(string $at, ?string $surfaceSlug, ?string $page, ?string $moduleSlug, array &$pages,
        array &$errors): void
    {
        $type = $surfaceSlug === null ? null : (Surfaces::TYPES[$surfaceSlug] ?? null);
        if ($type === null) {
            return;
        }
        if ($type !== self::PAGE_SURFACE_TYPE) {
            if ($page !== null) {
                $errors[] = "$at.page: page only on surfaces of type page (" . self::quoted($surfaceSlug) . " is of type $type)";
            }
            return;
        }
        if ($page === null) {
            $errors[] = "$at.page is required on a surface of type page (" . self::quoted($surfaceSlug) . '): the host '
                . 'opens it at /m/' . ($moduleSlug ?? 'null') . '/<page>';
            return;
        }
        $seen = "$surfaceSlug|$page";
        if (isset($pages[$seen])) {
            $errors[] = "$at.page \"$page\" is declared twice on " . self::quoted($surfaceSlug);
        } else {
            $pages[$seen] = true;
        }
    }

    private static function notifications(array $notifications, array $enKeys, array &$errors): void
    {
        $seen = [];
        foreach ($notifications as $i => $n) {
            $at = self::FILE . ": capabilities.notifications[$i]";
            if (!Json::isObject($n)) {
                $errors[] = "$at must be an object";
                continue;
            }
            $key = Manifest::text(Json::get($n, 'key'));
            if (!self::matches(self::NOTIFICATION_KEY, $key)) {
                $errors[] = "$at.key " . self::quoted(Json::get($n, 'key')) . ' must match ' . trim(self::NOTIFICATION_KEY, '/');
            } elseif (isset($seen[$key])) {
                $errors[] = "$at.key \"$key\" is declared twice";
            } else {
                $seen[$key] = true;
            }
            $channels = Manifest::texts(Json::get($n, 'channels'));
            if ($channels === []) $errors[] = "$at.channels must not be empty (one or more of in_app, email)";
            foreach ($channels as $channel) {
                if (!in_array($channel, self::CHANNELS, true)) $errors[] = "$at.channels: \"$channel\" is not a channel (one of in_app, email)";
            }
            self::requireKey("$at.subject", Manifest::text(Json::get($n, 'subject')), $enKeys, $errors);
            self::requireKey("$at.body", Manifest::text(Json::get($n, 'body')), $enKeys, $errors);
        }
    }

    private static function reads(array $reads, array &$errors): void
    {
        foreach ($reads as $i => $r) {
            $at = self::FILE . ": capabilities.reads[$i]";
            if (!Json::isObject($r)) {
                $errors[] = "$at must be an object";
                continue;
            }
            $port = Json::get($r, 'port');
            if (self::blank($port)) {
                $errors[] = "$at.port is required";
            } elseif (!self::full(self::PORT, $port)) {
                $errors[] = "$at.port \"$port\" must match " . self::PORT . ' (a port id: entity, optional '
                    . 'sub-segment, major version, e.g. model.v1)';
            }
            if (Json::get($r, 'access') !== null && Json::get($r, 'access') !== 'read') {
                $errors[] = "$at.access must be absent or \"read\": no module writes the platform's data";
            }
        }
    }

    /** `[{"access": "write" | "read-own"}]`, each value at most once. */
    private static function audit(array $audit, array &$errors): void
    {
        $seen = [];
        foreach ($audit as $i => $a) {
            $at = self::FILE . ": capabilities.audit[$i]";
            if (!Json::isObject($a)) {
                $errors[] = "$at must be an object {\"access\": \"write\" | \"read-own\"}";
                continue;
            }
            $access = Manifest::text(Json::get($a, 'access'));
            if (!in_array($access, Manifest::AUDIT_ACCESS, true)) {
                $errors[] = "$at.access must be one of write, read-own (got " . self::quoted(Json::get($a, 'access')) . ')';
            } elseif (isset($seen[$access])) {
                $errors[] = "$at.access \"$access\" is declared twice";
            } else {
                $seen[$access] = true;
            }
        }
    }

    /**
     * `nav` (protocol 1.1): the module's menu entries, each a closed object. Checked here: the
     * surface, the shape of the group and of the key, a key once per surface, the label key, the url (an in-app path),
     * the icon name, the order, the flag (one of the module's), and the right -- never on `customer`, and a right under
     * `api.v1.m.` only when it is the right of one of the module's own routes. Whether the group is a group of the
     * surface and whether a platform code is declared stay the platform's check at upload.
     */
    private static function nav(?string $slug, \stdClass $root, array $nav, array $enKeys, array &$errors): void
    {
        $routeRights = [];
        foreach (Manifest::objects(Json::get(Json::get($root, 'capabilities'), 'routes')) as $route) {
            $right = Manifest::text(Json::get($route, 'right'));
            if ($right !== null) $routeRights[$right] = true;
        }
        $flags = [];
        foreach (Manifest::objects(Json::get($root, 'featureFlags')) as $flag) {
            $key = Manifest::text(Json::get($flag, 'key'));
            if ($key !== null) $flags[$key] = true;
        }
        $seen = [];
        foreach ($nav as $i => $n) {
            $at = self::FILE . ": capabilities.nav[$i]";
            if (!Json::isObject($n)) {
                $errors[] = "$at must be an object";
                continue;
            }
            foreach (array_keys(Json::entries($n)) as $key) {
                $key = (string) $key;
                if (!in_array($key, Manifest::NAV_KEYS, true)) {
                    $errors[] = "$at: \"$key\" is not a key of a nav entry (known: " . implode(', ', Manifest::NAV_KEYS) . ')';
                }
            }
            $surface = Manifest::text(Json::get($n, 'surface'));
            if ($surface === null || !in_array($surface, Manifest::NAV_SURFACES, true)) {
                $errors[] = "$at.surface must be one of " . implode(', ', Manifest::NAV_SURFACES) . ' (got '
                    . self::quoted(Json::get($n, 'surface')) . ')';
            }
            $group = Manifest::text(Json::get($n, 'group'));
            if (!self::matches(self::NAV_KEY, $group)) {
                $errors[] = "$at.group must match " . trim(self::NAV_KEY, '/') . ' (got ' . self::quoted(Json::get($n, 'group')) . ')';
            }
            $key = Manifest::text(Json::get($n, 'key'));
            if (!self::matches(self::NAV_KEY, $key)) {
                $errors[] = "$at.key must match " . trim(self::NAV_KEY, '/') . ' (got ' . self::quoted(Json::get($n, 'key')) . ')';
            } elseif ($surface !== null) {
                $id = "$surface $key";
                if (isset($seen[$id])) $errors[] = "$at.key \"$key\" is declared twice on the surface $surface";
                $seen[$id] = true;
            }
            self::requireKey("$at.label", Manifest::text(Json::get($n, 'label')), $enKeys, $errors);
            if (!self::isInAppPath(Json::get($n, 'url'))) {
                $errors[] = "$at.url must be an in-app path: it starts with \"/\" and has no \"//\", \"..\", \"%\", "
                    . 'backslash, whitespace, scheme, query or fragment (got ' . self::quoted(Json::get($n, 'url')) . ')';
            }
            $icon = Json::get($n, 'icon');
            if ($icon !== null && !(is_string($icon) && self::matches(self::NAV_ICON, $icon))) {
                $errors[] = "$at.icon must be the name of an icon of the host, " . trim(self::NAV_ICON, '/')
                    . ' (e.g. "Boxes"; got ' . self::quoted($icon) . ')';
            }
            $order = Json::get($n, 'order');
            if ($order !== null && !(is_int($order) && $order >= 0 && $order <= self::MAX_NAV_ORDER)) {
                $errors[] = "$at.order must be an integer from 0 to " . self::MAX_NAV_ORDER . ' (got ' . self::quoted($order) . ')';
            }
            if (Json::get($n, 'flag') !== null) {
                $flag = Manifest::text(Json::get($n, 'flag'));
                if ($flag === null || !isset($flags[$flag])) {
                    $errors[] = "$at.flag " . self::quoted(Json::get($n, 'flag')) . ' is not a feature flag of this module '
                        . '(featureFlags[].key)';
                }
            }
            if (Json::get($n, 'right') !== null) {
                self::navRight($at, $slug, $surface, Json::get($n, 'right'), $routeRights, $errors);
            }
        }
    }

    private static function navRight(string $at, ?string $slug, ?string $surface, mixed $raw, array $routeRights,
                                     array &$errors): void
    {
        if ($surface === 'customer') {
            $errors[] = "$at.right is not allowed on the customer surface: the customer menu is filtered on the "
                . "customer's identity, not on rights";
            return;
        }
        $right = Manifest::text($raw);
        if ($right !== null && str_starts_with($right, 'api.v1.m.')) {
            if (!isset($routeRights[$right])) {
                $errors[] = "$at.right \"$right\" is not the right of a route this module declares "
                    . '(capabilities.routes[].right of api.v1.m.' . ($slug ?? 'null') . '.*)';
            }
        } elseif (!self::matches(self::PLATFORM_RIGHT, $right)) {
            $errors[] = "$at.right " . self::quoted($raw) . " is not a right code: the right of one of the module's routes "
                . 'or a code the platform declares (' . trim(self::PLATFORM_RIGHT, '/') . ')';
        }
    }

    /** @internal A menu url the host may render as an in-app link (the platform's `NavigationContributionRegistry` rule). */
    public static function isInAppPath(mixed $raw): bool
    {
        if (!is_string($raw) || self::length($raw) > self::MAX_NAV_URL_LENGTH || !str_starts_with($raw, '/')) return false;
        if (str_contains($raw, '//') || str_contains($raw, '..') || str_contains($raw, '%') || str_contains($raw, '\\')
            || str_contains($raw, '?') || str_contains($raw, '#')) {
            return false;
        }
        // Java's Character.isWhitespace(c) || Character.isISOControl(c)
        return preg_match('/[\x{0000}-\x{0020}\x{007f}-\x{009f}\x{1680}\x{2000}-\x{2006}\x{2008}-\x{200a}\x{2028}\x{2029}\x{205f}\x{3000}]/u', $raw) === 0;
    }

    // ---- registration ⊆ manifest -----------------------------------------------------------------------------

    private static function checkRegistration(Manifest $manifest, mixed $body, array &$errors): void
    {
        $hooks = Manifest::objects(Json::get($body, 'hooks'));
        if (!$manifest->usesKeys()) {
            foreach ($hooks as $i => $h) {
                if (Json::get($h, 'onFailure') === 'block') {
                    $errors[] = "registration: hooks[$i]: onFailure \"block\" requires a capability, which a schemaVersion 1 manifest"
                        . ' cannot declare; upgrade the module to manifest schemaVersion 2';
                }
            }
            return;
        }
        $caps = $manifest->capabilities;
        $enKeys = array_fill_keys(array_map('strval', array_keys($manifest->i18n['en'] ?? [])), true);

        foreach ($hooks as $i => $h) {
            $event = Manifest::text(Json::get($h, 'event'));
            $at = "registration: hooks[$i]";
            $declared = null;
            foreach ($caps->hooks as $c) {
                if ($event === $c->point) {
                    $declared = $c;
                    break;
                }
            }
            if ($declared === null) {
                $errors[] = "$at: event \"" . ($event ?? 'null') . '" is not declared in kf-module-conf.json capabilities.hooks (declared: '
                    . self::declaredPoints($caps) . ')';
                continue;
            }
            if (Json::get($h, 'onFailure') === 'block' && $declared->onFailure !== 'block') {
                $errors[] = "$at: onFailure \"block\" on $event requires the capability declared in kf-module-conf.json"
                    . ' (capabilities.hooks[].onFailure = "block")';
            }
        }

        foreach (Manifest::objects(Json::get($body, 'fields')) as $i => $f) {
            $entity = Manifest::text(Json::get($f, 'entity')) ?? 'model';
            $name = Manifest::text(Json::get($f, 'name'));
            $declared = false;
            foreach ($caps->fields as $c) {
                $declared = $declared || ($entity === $c->entity && $name === $c->name
                    && Json::get($f, 'type') === $c->type && Json::get($f, 'table') === $c->table);
            }
            if (!$declared) {
                $errors[] = "registration: fields[$i]: field \"$entity." . ($name ?? 'null') . '" (' . Json::text(Json::get($f, 'type'))
                    . ', table ' . Json::text(Json::get($f, 'table')) . ') is not declared in kf-module-conf.json capabilities.fields';
            }
            self::labelKey("registration: fields[$i]", Json::get($f, 'label'), $enKeys, $errors);
        }

        foreach (Manifest::objects(Json::get($body, 'surfaces')) as $i => $s) {
            $declared = false;
            foreach ($caps->surfaces as $c) {
                $declared = $declared || (Json::get($s, 'slug') === $c->slug && Json::get($s, 'kind') === $c->kind);
            }
            if (!$declared) {
                $errors[] = "registration: surfaces[$i]: surface \"" . Json::text(Json::get($s, 'slug')) . '" (' . Json::text(Json::get($s, 'kind'))
                    . ') is not declared in kf-module-conf.json capabilities.surfaces';
            } elseif (Json::get($s, 'slug') === Manifest::ORDERFLOW_STEP) {
                // protocol 2.0 (§16.5): the step the contribution renders, as declared
                $key = Json::get($s, 'key');
                if (!is_string($key) || trim($key) === '') {
                    $errors[] = "registration: surfaces[$i] on \"" . Manifest::ORDERFLOW_STEP . '" needs a "key": the key of the step it renders';
                } else {
                    $stepDeclared = false;
                    foreach ($caps->surfaces as $c) {
                        $stepDeclared = $stepDeclared || ($c->slug === Manifest::ORDERFLOW_STEP && $c->key === $key);
                    }
                    if (!$stepDeclared) {
                        $errors[] = "registration: surfaces[$i]: the step \"$key\" of \"" . Manifest::ORDERFLOW_STEP
                            . '" is not declared in kf-module-conf.json capabilities.surfaces';
                    }
                }
            }
            self::labelKey("registration: surfaces[$i]", Json::get($s, 'label'), $enKeys, $errors);
        }
    }

    /** a key used at registration has an `en` text in the manifest; an absent label stays legal. */
    private static function labelKey(string $at, mixed $label, array $enKeys, array &$errors): void
    {
        if ($label === null) return;
        if (!is_string($label) || !isset($enKeys[$label])) {
            $errors[] = "$at: label " . self::quoted($label) . ' is not a key of the manifest i18n block with an "en" text';
        }
    }

    /**
     * `settings` (protocol 1.1): closed scalars only. Each a closed object
     * `{key, type, default, label, description?, values?, min?, max?}`; `type` `BOOLEAN`, `NUMBER` or `ENUM` ("settings
     * carry no data": no free text, list, reference or secret); the default coherent with the type; `values` only and
     * always on `ENUM`; `min`/`max` only on `NUMBER`; unique keys; at most Manifest::MAX_SETTINGS.
     */
    private static function settings(array $settings, array $enKeys, array &$errors): void
    {
        if (count($settings) > Manifest::MAX_SETTINGS) {
            $errors[] = self::FILE . ': capabilities.settings declares ' . count($settings) . ' settings; at most '
                . Manifest::MAX_SETTINGS . ' settings are allowed';
        }
        $seen = [];
        foreach ($settings as $i => $s) {
            $at = self::FILE . ": capabilities.settings[$i]";
            if (!Json::isObject($s)) {
                $errors[] = "$at must be an object";
                continue;
            }
            foreach (array_keys(Json::entries($s)) as $key) {
                $key = (string) $key;
                if (!in_array($key, Manifest::SETTING_KEYS, true)) {
                    $errors[] = "$at: \"$key\" is not a key of a setting (known: " . implode(', ', Manifest::SETTING_KEYS) . ')';
                }
            }
            $key = Manifest::text(Json::get($s, 'key'));
            if (!self::full(self::SETTING_KEY, $key)) {
                $errors[] = "$at.key must match " . self::SETTING_KEY . ' (got ' . self::quoted(Json::get($s, 'key')) . ')';
            } elseif (isset($seen[$key])) {
                $errors[] = "$at.key \"$key\" is declared twice";
            } else {
                $seen[$key] = true;
            }
            self::requireKey("$at.label", Manifest::text(Json::get($s, 'label')), $enKeys, $errors);
            if (Json::get($s, 'description') !== null) {
                self::requireKey("$at.description", Manifest::text(Json::get($s, 'description')), $enKeys, $errors);
            }
            $type = Manifest::text(Json::get($s, 'type'));
            if (!in_array($type, Manifest::SETTING_TYPES, true)) {
                $errors[] = "$at.type " . self::quoted(Json::get($s, 'type')) . ' is not allowed: ' . self::SETTINGS_RULE
                    . ' (free text, lists, references and secrets are data: they live on surfaces or in secrets)';
                continue;
            }
            if ($type !== 'ENUM' && Json::get($s, 'values') !== null) $errors[] = "$at.values only on an ENUM setting";
            if ($type !== 'NUMBER' && (Json::get($s, 'min') !== null || Json::get($s, 'max') !== null)) {
                $errors[] = "$at.min and .max only on a NUMBER setting";
            }
            $default = Json::get($s, 'default');
            if ($default === null) {
                $errors[] = "$at.default is required (the value before the tenant administrator sets one)";
                continue;
            }
            if ($type === 'BOOLEAN') {
                if (!is_bool($default)) $errors[] = "$at.default must be a boolean (got " . self::quoted($default) . ')';
            } elseif ($type === 'NUMBER') {
                self::numberSetting($at, $s, $default, $errors);
            } else {
                self::enumSetting($at, $s, $default, $errors);
            }
        }
    }

    private static function numberSetting(string $at, \stdClass $s, mixed $default, array &$errors): void
    {
        $min = null;
        $max = null;
        $rawMin = Json::get($s, 'min');
        $rawMax = Json::get($s, 'max');
        if ($rawMin !== null) {
            if (self::isNumber($rawMin)) $min = $rawMin;
            else $errors[] = "$at.min must be a number (got " . self::quoted($rawMin) . ')';
        }
        if ($rawMax !== null) {
            if (self::isNumber($rawMax)) $max = $rawMax;
            else $errors[] = "$at.max must be a number (got " . self::quoted($rawMax) . ')';
        }
        if ($min !== null && $max !== null && $min > $max) $errors[] = "$at.min must not be above .max";
        if (!self::isNumber($default)) {
            $errors[] = "$at.default must be a number (got " . self::quoted($default) . ')';
            return;
        }
        if (($min !== null && $default < $min) || ($max !== null && $default > $max)) {
            $errors[] = "$at.default must be between min and max (got " . self::quoted($default) . ')';
        }
    }

    private static function enumSetting(string $at, \stdClass $s, mixed $default, array &$errors): void
    {
        $values = Json::get($s, 'values');
        if (!is_array($values) || $values === []) {
            $errors[] = "$at.values must be a non-empty list of codes on an ENUM setting (a closed choice)";
            return;
        }
        $seen = [];
        foreach ($values as $v) {
            if (!self::full(self::ENUM_VALUE, $v)) {
                $errors[] = "$at.values: " . self::quoted($v) . ' must match ' . self::ENUM_VALUE;
            } elseif (isset($seen[$v])) {
                $errors[] = "$at.values: \"$v\" is declared twice";
            } else {
                $seen[$v] = true;
            }
        }
        if (!is_string($default) || !isset($seen[$default])) {
            $errors[] = "$at.default must be one of the values (got " . self::quoted($default) . ')';
        }
    }

    /**
     * `secrets` (protocol 1.1): closed objects `{name, scope, writeRight}` -- a value never
     * travels in the manifest; unique names; `scope` `platform` or `tenant`; `writeRight` the right of a declared route,
     * and for a `platform` secret a route with `admin: platform`.
     */
    private static function secrets(\stdClass $root, array $secrets, array &$errors): void
    {
        $adminByRight = [];
        foreach (Manifest::objects(Json::get(Json::get($root, 'capabilities'), 'routes')) as $route) {
            $right = Manifest::text(Json::get($route, 'right'));
            if ($right !== null) $adminByRight[$right] = Json::get($route, 'admin') ?? '';
        }
        $seen = [];
        foreach ($secrets as $i => $s) {
            $at = self::FILE . ": capabilities.secrets[$i]";
            if (!Json::isObject($s)) {
                $errors[] = "$at must be an object";
                continue;
            }
            foreach (array_keys(Json::entries($s)) as $key) {
                $key = (string) $key;
                if (!in_array($key, Manifest::SECRET_KEYS, true)) {
                    $errors[] = "$at: \"$key\" is not a key of a secret (known: " . implode(', ', Manifest::SECRET_KEYS)
                        . '; a value is written through the platform, never in the manifest)';
                }
            }
            $name = Manifest::text(Json::get($s, 'name'));
            if (!self::full(self::SETTING_KEY, $name)) {
                $errors[] = "$at.name must match " . self::SETTING_KEY . ' (got ' . self::quoted(Json::get($s, 'name')) . ')';
            } elseif (isset($seen[$name])) {
                $errors[] = "$at.name \"$name\" is declared twice";
            } else {
                $seen[$name] = true;
            }
            $scope = Manifest::text(Json::get($s, 'scope'));
            if (!in_array($scope, Manifest::SECRET_SCOPES, true)) {
                $errors[] = "$at.scope must be one of platform, tenant (got " . self::quoted(Json::get($s, 'scope')) . ')';
            }
            $writeRight = Manifest::text(Json::get($s, 'writeRight'));
            if ($writeRight === null || !array_key_exists($writeRight, $adminByRight)) {
                $errors[] = "$at.writeRight " . self::quoted(Json::get($s, 'writeRight')) . ' is not the right of a route '
                    . 'this module declares (capabilities.routes[].right)';
            } elseif ($scope === 'platform' && $adminByRight[$writeRight] !== 'platform') {
                $errors[] = "$at.writeRight \"$writeRight\": a platform secret is written only through a route "
                    . 'with "admin": "platform"';
            }
            if (Json::has($s, 'external')) self::secretExternal($at, $scope, Json::get($s, 'external'), $errors);
        }
    }

    /**
     * `secrets[].external` (SDK 0.7.2): a non-empty array of at most
     * Manifest::SECRET_MAX_EXTERNAL distinct prefixes of Manifest::SECRET_EXTERNAL_PREFIX, none reaching the modules'
     * secrets (`kf-modules`), only on a `platform` secret -- the platform's rules and texts.
     *
     * @param list<string> $errors
     */
    private static function secretExternal(string $at, ?string $scope, mixed $external, array &$errors): void
    {
        $max = Manifest::SECRET_MAX_EXTERNAL;
        $isList = is_array($external) && array_is_list($external);
        if (!$isList || count($external) === 0 || count($external) > $max) {
            $errors[] = "$at.external must be a non-empty array of at most $max prefixes";
            if (!$isList) return;
        }
        if ($scope !== 'platform') $errors[] = "$at.external is allowed only on a platform secret";
        $seen = [];
        foreach ($external as $j => $item) {
            $prefix = Manifest::text($item);
            if ($prefix === null || preg_match('#' . Manifest::SECRET_EXTERNAL_PREFIX . '#D', $prefix) !== 1) {
                $errors[] = "$at.external[$j] must match " . Manifest::SECRET_EXTERNAL_PREFIX . ' (got '
                    . self::quoted($item) . ')';
            } elseif (in_array('kf-modules', explode('/', $prefix), true)) {
                $errors[] = "$at.external[$j] \"$prefix\" must not reach the modules' secrets (kf-modules)";
            } elseif (isset($seen[$prefix])) {
                $errors[] = "$at.external[$j] \"$prefix\" is declared twice";
            } else {
                $seen[$prefix] = true;
            }
        }
    }

    /**
     * `provides` (protocol 1.1): closed objects `{port, path}`; the port grammar and once per
     * port; `path` the path of a GET route this module declares. Whether the platform consumes the port is its catalog.
     */
    private static function provides(\stdClass $root, array $provides, array &$errors): void
    {
        $getPaths = [];
        foreach (Manifest::objects(Json::get(Json::get($root, 'capabilities'), 'routes')) as $route) {
            $path = Json::get($route, 'path');
            if (Json::get($route, 'method') === 'GET' && is_string($path)) $getPaths[$path] = true;
        }
        $seen = [];
        foreach ($provides as $i => $p) {
            $at = self::FILE . ": capabilities.provides[$i]";
            if (!Json::isObject($p)) {
                $errors[] = "$at must be an object";
                continue;
            }
            foreach (array_keys(Json::entries($p)) as $key) {
                $key = (string) $key;
                if (!in_array($key, Manifest::PROVIDE_KEYS, true)) {
                    $errors[] = "$at: \"$key\" is not a key of a provided read (known: " . implode(', ', Manifest::PROVIDE_KEYS) . ')';
                }
            }
            $port = Manifest::text(Json::get($p, 'port'));
            if (!self::full(self::PORT, $port)) {
                $errors[] = "$at.port " . self::quoted(Json::get($p, 'port')) . ' must match ' . self::PORT;
            } elseif (isset($seen[$port])) {
                $errors[] = "$at.port \"$port\" is declared twice";
            } else {
                $seen[$port] = true;
            }
            $path = Manifest::text(Json::get($p, 'path'));
            if ($path === null || !isset($getPaths[$path])) {
                $errors[] = "$at.path " . self::quoted(Json::get($p, 'path')) . ' is not a GET route this module declares '
                    . '(capabilities.routes[] with method GET)';
            }
        }
    }

    private static function declaredPoints(Manifest\Capabilities $caps): string
    {
        $points = array_filter(array_map(static fn ($h) => $h->point, $caps->hooks), static fn ($p) => $p !== null);
        return $points === [] ? 'none' : implode(', ', $points);
    }

    private static function items(mixed $o): array
    {
        return is_array($o) ? array_values($o) : [];
    }

    /** Characters in UTF-16 code units, like Java's String.length(), without ext-mbstring. */
    private static function length(string $s): int
    {
        $n = preg_match_all('/./us', $s);
        $astral = preg_match_all('/[\x{10000}-\x{10FFFF}]/u', $s);
        return ($n === false ? strlen($s) : $n) + ($astral === false ? 0 : $astral);
    }

    private static function quoted(mixed $value): string
    {
        if ($value === null) return 'null';
        if (is_string($value)) return '"' . $value . '"';
        return Json::text($value) ?? 'null';
    }
}
